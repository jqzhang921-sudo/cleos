package com.cleo.cleos.data

import androidx.room.withTransaction
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manual and explicitly permitted background fetches share cancellation and a two-download bound. */
class WorldPictures(private val db: AppDatabase, private val images: ImageStore, private val scope: CoroutineScope,
    private val source: WorldPictureSource = WorldPictureSource(), private val discard: suspend (String) -> Unit,
    private val beforeRemove: suspend (Long, Long) -> Unit = { _, _ -> },
) {
    data class State(val busy: Boolean = false, val message: String = "")
    private data class Active(val ta: Long, val job: Job)
    private val jobs = mutableMapOf<Long, Active>()
    private val removed = mutableSetOf<Long>()
    private val removedTas = mutableSetOf<Long>()
    private var paused = false
    @Volatile private var generation = java.util.UUID.randomUUID().mostSignificantBits
    private val permits = Mutex()
    fun revision(): Long = generation
    suspend fun permit(ta: Long, createdAt: Long, revision: Long, action: suspend () -> Unit) = permits.withLock {
        check(synchronized(jobs) { !paused && ta !in removedTas && generation == revision } && db.companions().get(ta)?.createdAt == createdAt) { "世界资料已变化，请重新打开窗外" }
        action()
    }
    private val updates = MutableStateFlow<Map<Long, State>>(emptyMap())
    val states = updates.asStateFlow()
    fun fetch(ta: Long, createdAt: Long, article: Long, fingerprint: String) = synchronized(jobs) {
        if (paused || article in removed || ta in removedTas || article in jobs) return@synchronized
        if (jobs.values.any { it.ta == ta } || jobs.size >= 2) {
            updates.update { it + (article to State(message = "另一次配图获取正在进行，请稍后再试")) }; return@synchronized
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val a = requireNotNull(db.world().article(article)); check(a.fingerprint == fingerprint)
                val picture = load(ta, createdAt, a) { true }
                updates.update { it + (article to State(message = if (picture == null) "这份条目暂没有可确认署名与许可的配图，文字保留。" else "配图已保存，只供查看；本次没有发给模型。")) }
            } catch (e: CancellationException) {
                updates.update { it + (article to State(message = "配图获取已停止，原有文字保留")) }; throw e
            } catch (_: Exception) {
                updates.update { it + (article to State(message = "这次没有取得可用配图，请检查网络后重试。原有文字与阅读位置保留。")) }
            } finally { synchronized(jobs) { jobs.remove(article) } }
        }
        jobs[article] = Active(ta, job)
        job.invokeOnCompletion {
            synchronized(jobs) { if (jobs[article]?.job === job) jobs.remove(article) }
            updates.update { state -> if (state[article]?.busy == true) state + (article to State(message = "配图获取已停止，原有文字保留")) else state }
        }
        updates.update { it + (article to State(true, "正在核对条目配图与署名")) }; job.start()
    }
    /** Runs inside the caller's reading lease, so stopping its Worker drains the download too. */
    suspend fun obtain(ta: Long, createdAt: Long, expected: WorldArticleEntity, permission: suspend () -> Boolean): WorldPictureEntity? {
        val job = currentCoroutineContext().job
        synchronized(jobs) {
            check(!paused && ta !in removedTas && expected.id !in removed && expected.id !in jobs && jobs.size < 2 && jobs.values.none { it.ta == ta }) { "另一次配图获取正在进行" }
            jobs[expected.id] = Active(ta, job)
        }
        return try { load(ta, createdAt, expected, permission) }
        finally { synchronized(jobs) { if (jobs[expected.id]?.job === job) jobs.remove(expected.id) } }
    }
    private suspend fun load(ta: Long, createdAt: Long, a: WorldArticleEntity, permission: suspend () -> Boolean): WorldPictureEntity? {
        suspend fun allowed() {
            currentCoroutineContext().ensureActive()
            check(synchronized(jobs) { !paused && a.id !in removed && ta !in removedTas } && permission()) { "配图获取已关闭或资料已变化" }
            check(a.companionId == ta && a.space == "outside" && WorldTopicSearch.validUrl(a.originUrl) && db.world().article(a.id) == a && db.companions().get(ta)?.createdAt == createdAt) { "条目或角色已变化" }
        }
        allowed()
        db.world().picture(a.id)?.let { check(it.companionId == ta && it.fingerprint == a.fingerprint); return it }
        var temporary: String? = null
        try {
            val picture = source.fetch(a.originUrl, ::allowed); allowed()
            if (picture == null) return null
            val stored = withContext(NonCancellable) { images.import(picture.bytes, picture.credit.mime, 960, "world-") }
            temporary = stored.file; allowed()
            val c = picture.credit
            val row = WorldPictureEntity(a.id, ta, a.fingerprint, stored.file, stored.width, stored.height, c.title, c.author,
                c.credit, c.description, c.license, c.licenseUrl, c.sourceUrl, c.imageUrl, System.currentTimeMillis(),
                withContext(Dispatchers.IO) { WorldPictureData.digest(images.file(stored.file)) })
            db.withTransaction { allowed(); check(db.world().picture(a.id) == null); WorldPictureData.validate(listOf(a), listOf(row)); db.world().add(row) }
            temporary = null
            return row
        } finally { temporary?.let { withContext(NonCancellable) { discard(it) } } }
    }
    fun cancel(article: Long) { synchronized(jobs) { jobs[article]?.job }?.cancel() }
    suspend fun remove(ta: Long, createdAt: Long, expected: WorldPictureEntity) {
        check(db.companions().get(ta)?.createdAt == createdAt && expected.companionId == ta && db.world().picture(expected.articleId) == expected)
        beforeRemove(ta, expected.articleId)
        synchronized(jobs) { jobs[expected.articleId]?.job }?.cancelAndJoin()
        db.withTransaction {
            check(synchronized(jobs) { !paused } && db.companions().get(ta)?.createdAt == createdAt && expected.companionId == ta && db.world().picture(expected.articleId) == expected)
            db.world().deletePicture(expected.articleId, ta)
        }
        discard(expected.file)
        updates.update { it - expected.articleId }
    }
    suspend fun beforeArticleDelete(ta: Long, article: Long): List<String> {
        val a = db.world().article(article) ?: return emptyList()
        if (a.companionId != ta) return emptyList()
        val job = synchronized(jobs) { removed += article; jobs[article]?.job }
        job?.cancelAndJoin(); updates.update { it - article }
        return listOfNotNull(db.world().picture(article)?.file)
    }
    /** Stop selected transfers without marking surviving rows as permanently removed on a stale plan. */
    suspend fun stopArticles(ta: Long, articles: Set<Long>) {
        val active = synchronized(jobs) { jobs.filter { (id, job) -> id in articles && job.ta == ta }.values.map { it.job } }
        active.forEach { it.cancel() }; active.forEach { it.join() }
        updates.update { state -> state.filterKeys { it !in articles } }
    }
    suspend fun forget(ta: Long) {
        val active = synchronized(jobs) { removedTas += ta; jobs.values.filter { it.ta == ta }.map { it.job } }
        active.forEach { it.cancel() }; active.forEach { it.join() }
        val ids = db.world().articles(ta).map { it.id }.toSet(); updates.update { state -> state.filterKeys { it !in ids } }
    }
    suspend fun stopAll() {
        val active = synchronized(jobs) { paused = true; generation++; jobs.values.map { it.job } }
        permits.withLock { }
        active.forEach { it.cancel() }; active.forEach { it.join() }; updates.value = emptyMap()
    }
    fun resume() = synchronized(jobs) { removed.clear(); removedTas.clear(); paused = false }
}
