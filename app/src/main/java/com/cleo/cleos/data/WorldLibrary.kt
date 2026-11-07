package com.cleo.cleos.data

import androidx.room.withTransaction
import com.cleo.cleos.ai.*
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One reading opportunity, shared by manual and opted-in background requests. No sharing tools. */
class WorldLibrary(
    private val db: AppDatabase, private val secrets: SecretStore, private val reader: WorldReader,
    private val scope: CoroutineScope, private val occupied: suspend (Long) -> Boolean,
    private val diagnostic: (String) -> Unit = {},
    private val beforeArticleDelete: suspend (Long, Long) -> List<String> = { _, _ -> emptyList() },
    private val discardFile: suspend (String) -> Unit = {},
    private val picturePixels: suspend (WorldPictureEntity) -> String? = { null },
    private val albumPixels: suspend (WorldAlbumPhotoEntity) -> String? = { null },
    private val beforeOutsideDelete: suspend (Long, Set<Long>) -> Unit = { _, _ -> },
    private val readingMemories: suspend (Long) -> List<MemoryEntity> = { emptyList() },
) {
    private data class TopicLookup(val recent: List<String>, val fetch: suspend (String) -> List<Long>)
    private data class PictureLook(val createdAt: Long, val picture: WorldPictureEntity? = null,
        val fetch: (suspend (Long, Long, WorldArticleEntity, suspend () -> Boolean) -> WorldPictureEntity?)? = null)
    private data class AlbumLook(val createdAt: Long, val photo: WorldAlbumPhotoEntity? = null)
    private data class Active(val job: Job, val background: Boolean, val space: String, val articleId: Long? = null)
    private val jobs = ConcurrentHashMap<Long, Active>()
    @Volatile private var paused = false
    @Volatile private var generation = java.util.UUID.randomUUID().mostSignificantBits
    private val outsideChanges = Mutex()
    private val removing = MutableStateFlow<Set<Long>>(emptySet())
    val removingOutside = removing.asStateFlow()
    fun revision(): Long = generation
    suspend fun saveJourneyTopic(owner: Long, createdAt: Long, revision: Long, expected: WorldJourneyTopicEntity?, expectedItems: List<WorldJourneyItemEntity>,
        title: String, kind: String, ids: List<Long>): Long = db.withTransaction {
        check(!paused && generation == revision && db.companions().get(owner)?.createdAt == createdAt) { "资料已变化，请重新打开专题后整理；本次未保存" }
        val heading = WorldJourneyTopics.title(title)
        require(kind in WorldJourneyTopics.kinds) { "请选择主题或地方" }
        WorldJourneyTopics.selection(owner, ids, db.world().articles(owner))
        if (expected != null) check(expected.companionId == owner && db.world().journeyTopic(expected.id) == expected && db.world().journeyItems(expected.id) == expectedItems) { "专题或关联见闻已变化，请返回后重新整理；本次未保存" }
        else check(db.world().allJourneyTopics().size < WorldJourneyTopics.MAX_TOPICS) { "专题数量已满，请先整理已有专题" }
        val now = System.currentTimeMillis()
        val id = if (expected == null) db.world().add(WorldJourneyTopicEntity(companionId = owner, title = heading, kind = kind, createdAt = now, updatedAt = now))
            else expected.id.also { db.world().update(expected.copy(title = heading, kind = kind, updatedAt = maxOf(now, expected.updatedAt + 1))) }
        db.world().clearJourneyItems(id, owner)
        db.world().restoreJourneyItems(ids.mapIndexed { index, article -> WorldJourneyItemEntity(id, article, owner, index) })
        id
    }
    suspend fun deleteJourneyTopic(owner: Long, createdAt: Long, revision: Long, expected: WorldJourneyTopicEntity) = db.withTransaction {
        check(!paused && generation == revision && expected.companionId == owner && db.companions().get(owner)?.createdAt == createdAt && db.world().journeyTopic(expected.id) == expected) { "专题资料已变化，请重新打开后再移除" }
        db.world().deleteJourneyTopic(expected.id, owner)
    }
    private val working = MutableStateFlow<Set<Long>>(emptySet())
    val busy = working.asStateFlow()
    private val initialization = Mutex()
    private var initialized = false
    private fun checkReading(allowed: Boolean, code: String, message: String) {
        if (!allowed) throw WorldReplyException(code, message)
    }
    suspend fun initialize() = initialization.withLock {
        if (!initialized) { db.world().recover(); initialized = true }
    }

    suspend fun add(taId: Long, expectedCreatedAt: Long, title: String, source: String, raw: String): Long {
        val body = WorldReading.normalize(raw)
        require(title.isNotBlank() && title.trim().length <= 120 && source.trim().length <= 500 && body.isNotBlank() && body.length <= WorldReading.MAX_TEXT) { "请填写书名和可读内容，每份读物最多 50 万个字符" }
        val fingerprint = WorldReading.fingerprint(body)
        return db.withTransaction {
            check(!paused && db.companions().get(taId)?.createdAt == expectedCreatedAt) { "资料正在恢复或这位 TA 已变化，请重新打开书房" }
            val all = db.world().articles(taId)
            check(all.none { it.space != "study" && it.fingerprint == fingerprint }) { "相同文字已保存在窗外，请从窗外查看" }
            val shelf = all.filter { it.space == "study" }
            shelf.firstOrNull { it.fingerprint == fingerprint }?.id ?: run {
                check(shelf.size < WorldReading.MAX_ARTICLES) { "书架最多放 50 份读物，请先整理一些" }
                db.world().add(WorldArticleEntity(companionId = taId, title = title.trim(), source = source.trim(), content = body,
                    fingerprint = fingerprint, createdAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun addPasted(taId: Long, createdAt: Long, revision: Long, title: String, source: String, raw: String): Long {
        val text = WorldPastedText.prepare(title, source, raw)
        return db.withTransaction {
            check(!paused && generation == revision) { "资料已恢复或发生变化，请重新打开后确认正文" }
            val existing = db.world().articles(taId).any { it.fingerprint == WorldReading.fingerprint(text.body) }
            val id = add(taId, createdAt, text.title, text.source, text.body)
            if (!existing) db.world().put(WorldShelfEntity(id, taId, "article", "", ""))
            id
        }
    }
    fun startArticle(taId: Long, createdAt: Long, article: WorldArticleEntity) {
        require(article.companionId == taId && article.space == "study" && article.id > 0)
        val revision = generation
        launchReading(taId, false, "study", articleIds = setOf(article.id)) {
            generation == revision && db.companions().get(taId)?.createdAt == createdAt && db.world().article(article.id) == article
        }
    }
    fun start(taId: Long) { launchReading(taId, false, "study") { true } }
    suspend fun stopWritingBackground(taId: Long) { jobs[taId]?.takeIf { it.background && it.space == "writing" }?.job?.cancelAndJoin() }
    fun startWriting(expected: CompanionEntity) {
        val revision = generation
        launchReading(expected.id, false, "writing") {
            generation == revision && WorldReading.sameContext(expected, db.companions().get(expected.id))
        }
    }
    suspend fun writeInBackground(taId: Long, permission: suspend () -> Boolean) {
        currentCoroutineContext().ensureActive()
        val job = launchReading(taId, true, "writing", permission = permission) ?: return
        try { job.join() } catch (e: CancellationException) { withContext(NonCancellable) { job.cancelAndJoin() }; throw e }
    }
    suspend fun deleteWriting(taId: Long, createdAt: Long, expected: WorldWritingEntity) = db.withTransaction {
        check(!paused && db.companions().get(taId)?.createdAt == createdAt && expected.companionId == taId && db.world().writing(expected.id) == expected) { "随笔或角色已经变化，请重新打开" }
        db.world().deleteWriting(expected.id, taId)
    }
    fun startOutside(taId: Long) { launchReading(taId, false, "outside") { true } }
    fun startPicture(taId: Long, createdAt: Long, picture: WorldPictureEntity) {
        require(picture.companionId == taId)
        launchReading(taId, false, "picture", picture = PictureLook(createdAt, picture)) { true }
    }
    fun lookingAt(taId: Long, articleId: Long): Boolean = jobs[taId]?.let { it.space == "picture" && it.articleId == articleId } == true
    fun startAlbum(taId: Long, createdAt: Long, photo: WorldAlbumPhotoEntity) {
        require(photo.companionId == taId)
        launchReading(taId, false, "album", album = AlbumLook(createdAt, photo)) { true }
    }
    suspend fun browseAlbumInBackground(taId: Long, createdAt: Long, permission: suspend () -> Boolean) {
        currentCoroutineContext().ensureActive()
        val job = launchReading(taId, true, "album", album = AlbumLook(createdAt), permission = permission) ?: return
        try { job.join() } catch (e: CancellationException) {
            withContext(NonCancellable) { job.cancelAndJoin() }; throw e
        }
    }
    suspend fun stopAlbumBackground(taId: Long) { jobs[taId]?.takeIf { it.background && it.space == "album" }?.job?.cancelAndJoin() }
    fun lookingAtAlbum(taId: Long, id: Long): Boolean = jobs[taId]?.let { it.space == "album" && it.articleId == id } == true
    fun browsingAlbum(taId: Long): Boolean = jobs[taId]?.let { it.space == "album" && it.background } == true
    suspend fun stopAlbum(taId: Long, id: Long) { jobs[taId]?.takeIf { it.space == "album" && (it.articleId == id || it.background) }?.job?.cancelAndJoin() }
    suspend fun stopPicture(taId: Long, articleId: Long) { jobs[taId]?.takeIf { it.space == "picture" && (it.articleId == articleId || it.background) }?.job?.cancelAndJoin() }
    suspend fun browsePicturesInBackground(taId: Long, createdAt: Long,
        fetch: suspend (Long, Long, WorldArticleEntity, suspend () -> Boolean) -> WorldPictureEntity?, permission: suspend () -> Boolean) {
        currentCoroutineContext().ensureActive()
        val job = launchReading(taId, true, "picture", picture = PictureLook(createdAt, fetch = fetch), permission = permission) ?: return
        try { job.join() } catch (e: CancellationException) { withContext(NonCancellable) { job.cancelAndJoin() }; throw e }
    }
    suspend fun stopPictureBackground(taId: Long) { jobs[taId]?.takeIf { it.background && it.space == "picture" }?.job?.cancelAndJoin() }
    fun browsingPictures(taId: Long): Boolean = jobs[taId]?.let { it.background && it.space == "picture" } == true
    suspend fun deletePictureLook(taId: Long, createdAt: Long, expected: WorldPictureLookEntity) = db.withTransaction {
        check(!paused && db.companions().get(taId)?.createdAt == createdAt && expected.companionId == taId && db.world().pictureLook(expected.id) == expected) { "看图记录已经变化，请重新打开" }
        db.world().deletePictureLook(expected.id, taId)
    }
    fun startOutsideResults(taId: Long, articleIds: Set<Long>, query: String) {
        require(articleIds.isNotEmpty() && articleIds.size <= 3 && articleIds.all { it > 0 })
        launchReading(taId, false, "outside", articleIds = articleIds.toSet(), focus = WorldTopicSearch.query(query)) { true }
    }
    private fun launchReading(taId: Long, background: Boolean, space: String, sourceId: String? = null,
        articleIds: Set<Long>? = null, focus: String = "", topic: TopicLookup? = null, picture: PictureLook? = null, album: AlbumLook? = null, permission: suspend () -> Boolean): Job? = synchronized(jobs) {
        if (paused || taId in removing.value || jobs.containsKey(taId)) return@synchronized null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            run(taId, if (topic == null) space else "outside", sourceId, articleIds, focus, topic, picture, album, permission)
        }
        jobs[taId] = Active(job, background, space, picture?.picture?.articleId ?: album?.photo?.id)
        working.update { it + taId }
        // A lazy job may be cancelled before entering its body; completion still releases its busy owner.
        job.invokeOnCompletion { synchronized(jobs) {
            if (jobs[taId]?.job === job) { jobs.remove(taId); working.update { it - taId } }
        } }
        job.start()
        job
    }
    /** A stopped Worker must also stop the app-scope request it owns, without cancelling manual work. */
    suspend fun readInBackground(taId: Long, space: String = "study", sourceId: String? = null, permission: suspend () -> Boolean) {
        require(space in setOf("study", "outside")); currentCoroutineContext().ensureActive()
        val job = launchReading(taId, true, space, sourceId, permission = permission) ?: return
        try { job.join() } catch (e: CancellationException) {
            withContext(NonCancellable) { job.cancelAndJoin() }
            throw e
        }
    }
    /** Topic selection, one search and reading share one lease and one cancellation owner. */
    suspend fun exploreInBackground(taId: Long, recent: List<String>, fetch: suspend (String) -> List<Long>, permission: suspend () -> Boolean) {
        currentCoroutineContext().ensureActive()
        val job = launchReading(taId, true, "topic", topic = TopicLookup(recent.toList(), fetch), permission = permission) ?: return
        try { job.join() } catch (e: CancellationException) {
            withContext(NonCancellable) { job.cancelAndJoin() }; throw e
        }
    }
    fun cancel(taId: Long) { jobs[taId]?.job?.cancel() }
    fun cancelBackground(taId: Long, space: String = "study") { jobs[taId]?.takeIf { it.background && it.space == space }?.job?.cancel() }
    suspend fun stopBackground(space: String? = null) {
        val running = jobs.values.filter { it.background && (space == null || it.space == space) }.map { it.job }
        running.forEach { it.cancel() }; running.forEach { it.join() }
    }
    suspend fun stopAll() {
        val running = synchronized(jobs) { paused = true; generation++; jobs.values.map { it.job } }
        running.forEach { it.cancel() }; running.forEach { it.join() }
        outsideChanges.withLock { }
    }
    fun resume() { paused = false }
    /** Avoid background network use when no reading can start. Fetching itself never consumes quota. */
    suspend fun canBrowseOutside(taId: Long, createdAt: Long): Boolean {
        if (paused || taId in removing.value || jobs.containsKey(taId) || occupied(taId)) return false
        val ta = db.companions().get(taId) ?: return false
        if (ta.createdAt != createdAt || ta.apiBaseUrl.isBlank() || ta.apiModel.isBlank() || secrets.key(ta.apiBaseUrl).isNullOrBlank()) return false
        val state = db.world().state(taId)
        val used = state?.takeIf { it.day == LocalDate.now().toEpochDay() }?.let { it.attempts - if (it.token != null) 1 else 0 } ?: 0
        return used < WorldReading.DAILY && db.world().recordCount(taId) < WorldReading.MAX_NOTES
    }
    suspend fun deleteArticle(taId: Long, articleId: Long) {
        jobs[taId]?.job?.cancelAndJoin()
        val files = beforeArticleDelete(taId, articleId)
        db.world().deleteArticle(articleId, taId)
        files.forEach { discardFile(it) }
    }
    suspend fun deleteNote(taId: Long, id: Long) { db.world().deleteNote(id, taId) }
    private suspend fun outsideRemoval(ta: Long, createdAt: Long, revision: Long, ids: List<Long>): WorldOutsideSelection.Removal {
        check(!paused && generation == revision && db.companions().get(ta)?.createdAt == createdAt) { "世界或角色已变化，请重新打开窗外" }
        return WorldOutsideSelection.removal(ta, createdAt, revision, ids, db.world().articles(ta), db.world().notes(ta), db.world().pictureLooks(ta), db.world().pictures(ta))
    }
    suspend fun prepareOutsideRemoval(ta: Long, createdAt: Long, revision: Long, ids: List<Long>) = db.withTransaction {
        outsideRemoval(ta, createdAt, revision, ids.toList())
    }
    /** One confirmed snapshot, one transaction. Restores drain this operation before replacing files. */
    suspend fun removeOutside(expected: WorldOutsideSelection.Removal): Int = outsideChanges.withLock {
        val ta = expected.owner
        db.withTransaction {
            check(expected.matches(outsideRemoval(ta, expected.createdAt, expected.revision, expected.ids))) { "见闻或关联笔记已变化，尚未删除，请重新确认" }
        }
        synchronized(jobs) {
            check(!paused && generation == expected.revision && ta !in removing.value) { "世界已变化，请重新确认" }
            check(jobs[ta]?.space !in setOf("study", "album")) { "TA 正在另一个空间，请稍后整理" }
            removing.update { it + ta }
        }
        try {
            jobs[ta]?.job?.cancelAndJoin()
            beforeOutsideDelete(ta, expected.ids.toSet())
            val files = db.withTransaction {
                val current = outsideRemoval(ta, expected.createdAt, expected.revision, expected.ids)
                check(expected.matches(current)) { "见闻或关联笔记已变化，尚未删除，请重新确认" }
                check(db.world().deleteOutside(expected.ids, ta) == expected.ids.size)
                current.pictures.map { it.file }
            }
            withContext(NonCancellable) { files.forEach { file ->
                runCatching { discardFile(file) }.onFailure { diagnostic("event=world_outside_removal route=outside stage=cleanup reason=file_cleanup_failed") }
            } }
            expected.ids.size
        } finally { synchronized(jobs) { removing.update { it - ta } } }
    }
    suspend fun bookmark(taId: Long, createdAt: Long, articleId: Long, fingerprint: String, offset: Int, label: String): Long = db.withTransaction {
        val a = requireNotNull(db.world().article(articleId)) { "读物已不在书架上" }
        check(!paused && db.companions().get(taId)?.createdAt == createdAt && a.companionId == taId && a.space == "study" && a.fingerprint == fingerprint) { "读物已经变化，请重新打开" }
        require(offset < a.content.length && WorldReading.boundary(a.content, offset) && WorldShelfData.label(label.trim(), 80)) { "书签位置或名称无效" }
        val marks = db.world().bookmarks(taId)
        marks.firstOrNull { it.articleId == articleId && it.offset == offset }?.let { old ->
            db.world().update(old.copy(label = label.trim())); old.id
        } ?: run {
            check(marks.size < WorldShelfData.MAX_BOOKMARKS) { "书签已满，请先整理一些" }
            db.world().add(WorldBookmarkEntity(companionId = taId, articleId = articleId, fingerprint = fingerprint, offset = offset, label = label.trim(), createdAt = System.currentTimeMillis()))
        }
    }
    suspend fun deleteBookmark(taId: Long, createdAt: Long, expected: WorldBookmarkEntity) = db.withTransaction {
        check(!paused && db.companions().get(taId)?.createdAt == createdAt && expected.companionId == taId && db.world().bookmark(expected.id) == expected) { "书签已经变化，请重新打开" }
        db.world().deleteBookmark(expected.id, taId)
    }
    suspend fun organize(taId: Long, createdAt: Long, articleId: Long, fingerprint: String, expected: WorldShelfEntity?, kind: String, series: String, issue: String) = db.withTransaction {
        val a = requireNotNull(db.world().article(articleId)) { "读物已不在书架上" }
        check(!paused && db.companions().get(taId)?.createdAt == createdAt && a.companionId == taId && a.space == "study" && a.fingerprint == fingerprint && db.world().shelf(articleId) == expected) { "读物或整理信息已经变化，请重新打开" }
        require(WorldShelfData.valid(kind, series.trim(), issue.trim())) { "请填写杂志名称，期号最多 80 字" }
        db.world().put(WorldShelfEntity(articleId, taId, kind, series.trim(), issue.trim()))
    }

    /** UI actions carry the observed row and TA incarnation; stale screens cannot overwrite a restored library. */
    suspend fun handleWish(taId: Long, expectedCreatedAt: Long, expected: WorldWishEntity, status: String,
        articleId: Long? = null, catalog: WorldBookCatalog.Book? = null,
    ) = db.withTransaction {
        check(db.companions().get(taId)?.createdAt == expectedCreatedAt && expected.companionId == taId &&
            db.world().wish(expected.id) == expected) { "想读清单已经变化，请重新打开" }
        require(status in WorldWishes.statuses)
        if (status == "pending" && expected.status !in setOf("pending", "later"))
            check(db.world().wishes(taId).count { it.status == "pending" || it.status == "later" } < WorldWishes.MAX_PENDING) { "待添加清单已满" }
        if (articleId != null) check(status == "added" && db.world().article(articleId)?.let { it.companionId == taId && it.space == "study" } == true) { "请关联这位 TA 书架上的读物" }
        if (catalog != null) check(db.world().wishes(taId).none { it.id != expected.id && it.catalogKey == catalog.key }) { "这本书目已经在想读清单中" }
        val at = System.currentTimeMillis().coerceAtLeast(expected.createdAt)
        db.world().update(expected.copy(status = status, handledAt = if (status == "pending") null else at,
            articleId = if (status == "added") articleId ?: expected.articleId else null,
            catalogKey = catalog?.key ?: expected.catalogKey, catalogTitle = catalog?.title ?: expected.catalogTitle,
            catalogAuthor = catalog?.author ?: expected.catalogAuthor, verifiedAt = if (catalog != null) at else expected.verifiedAt))
    }

    private suspend fun run(taId: Long, space: String, sourceId: String?, articleIds: Set<Long>?, initialFocus: String, topic: TopicLookup?, picture: PictureLook?, album: AlbumLook?, permission: suspend () -> Boolean) {
        initialize()
        val token = UUID.randomUUID().toString()
        var claimed = false
        var phase = "prepare"
        var albumPhoto = album?.photo
        var pictureImage = picture?.picture
        var pictureArticle: WorldArticleEntity? = null
        suspend fun status(text: String, failureCode: String = "", refund: Boolean = false) = db.withTransaction {
            val state = db.world().state(taId)
            if (state != null && (state.token == token || !claimed && state.token == null)) db.world().put(state.copy(
                attempts = if (refund && claimed && state.token == token) (state.attempts - 1).coerceAtLeast(0) else state.attempts,
                token = null, status = text, failureCode = failureCode, space = space, pictureArticleId = pictureImage?.articleId, albumPhotoId = albumPhoto?.id))
        }
        try {
            val ta = db.companions().get(taId) ?: return
            if (album != null) {
                checkReading(ta.createdAt == album.createdAt && (albumPhoto == null || albumPhoto?.let { it.companionId == taId && db.worldAlbum().photo(it.id) == it } == true),
                    "picture_changed", "相册照片或角色已变化，请重新打开")
                checkReading(db.world().recordCount(taId) < WorldReading.MAX_NOTES, "notebook_full", "世界记录已满，请先整理一些")
            }
            if (picture != null) {
                checkReading(ta.createdAt == picture.createdAt, "picture_changed", "角色已经变化，请重新打开")
                pictureImage?.let { p ->
                    val a = db.world().article(p.articleId)
                    checkReading(p.companionId == taId && db.world().picture(p.articleId) == p && a != null && a.companionId == taId && a.space == "outside" && a.fingerprint == p.fingerprint,
                        "picture_changed", "配图或条目已经变化，请重新打开")
                    pictureArticle = a
                }
                checkReading(db.world().recordCount(taId) < WorldReading.MAX_NOTES, "notebook_full", "随手本已满，请先整理一些记录")
            }
            checkReading(!paused && permission(), "permission_changed", "自主阅读已关闭或安排已变化，本次没有开始")
            checkReading(!occupied(taId), "busy", "TA 正在聊天或通话，稍后再读")
            val selectedSource = sourceId?.let { id -> WorldOutside.sources.single { it.id == id }.name }
            val shelf = if (space == "writing") emptyList() else db.world().articles(taId).filter { it.space == space && (selectedSource == null || it.source == selectedSource) && (articleIds == null || it.id in articleIds) }
            var articles = (if (space == "study") WorldBookVolumes.candidates(shelf)
                else shelf.filter { it.readOffset < it.content.length }).take(20)
            var focus = initialFocus
            val wishes = db.world().wishes(taId)
            val mayWish = space == "study" && articleIds == null && WorldWishes.mayAsk(wishes, System.currentTimeMillis())
            check(space == "writing" || album != null || picture != null || topic != null || articles.isNotEmpty() || mayWish) { if (space == "outside") "暂时没有未读摘要，可以先刷新见闻" else "书架暂时没有未读内容，想读清单也需要歇一歇；可以先添加读物" }
            val key = secrets.key(ta.apiBaseUrl)?.takeIf(String::isNotBlank) ?: error("请先配置这位 TA 的模型密钥")
            check(ta.apiBaseUrl.isNotBlank() && ta.apiModel.isNotBlank()) { "请先配置这位 TA 的模型" }
            val writingKeyRevision = if (space == "writing") secrets.revision(ta.apiBaseUrl) else ""
            db.withTransaction {
                checkReading(!paused && permission(), "permission_changed", "阅读安排已变化，本次没有开始")
                val today = LocalDate.now().toEpochDay()
                val state = db.world().state(taId)
                val used = state?.takeIf { it.day == today }?.attempts ?: 0
                check(used < WorldReading.DAILY) { "今天的阅读时间用完了，明天再继续" }
                db.world().put(WorldReadingStateEntity(taId, today, used + 1, token, if (album != null) "正在准备这张照片" else if (picture != null) "正在准备这张配图" else if (topic != null) "正在想这次查阅什么" else if (space == "outside") "正在挑选想看的见闻" else if (space == "writing") "正在随手写写" else "正在挑选想读的文章", space = space, pictureArticleId = pictureImage?.articleId, albumPhotoId = albumPhoto?.id))
                claimed = true
            }
            suspend fun allowed() {
                currentCoroutineContext().ensureActive()
                checkReading(!paused && permission(), "permission_changed", "自主阅读已关闭或安排已变化，本次阅读已停止")
                val current = db.companions().get(taId)
                checkReading(WorldReading.sameContext(ta, current) && secrets.key(ta.apiBaseUrl) == key && (space != "writing" || secrets.revision(ta.apiBaseUrl) == writingKeyRevision),
                    "context_changed", "角色或模型设置已变化，本次阅读已停止")
                checkReading(db.world().state(taId)?.token == token, "permission_changed", "本次阅读已失效，未保存旧结果")
                checkReading(!occupied(taId), "busy", "TA 正在聊天或通话，本次阅读先停下，稍后可再试")
                if (pictureArticle != null) checkReading(db.world().article(pictureArticle!!.id) == pictureArticle, "picture_changed", "配图条目已经移除或变化，本次结果未保存")
                if (pictureImage != null) checkReading(pictureImage?.let { db.world().picture(it.articleId) == it } == true,
                    "picture_changed", "配图已经移除或变化，本次结果未保存")
                if (albumPhoto != null) checkReading(albumPhoto?.let { db.worldAlbum().photo(it.id) == it } == true,
                    "picture_changed", "相册照片已经移除或变化，本次结果未保存")
            }
            val endpoint = ApiEndpoint(ta.apiBaseUrl, key, ta.apiModel)
            if (space == "writing") {
                allowed(); phase = "writing"
                checkReading(db.world().recordCount(taId) < WorldReading.MAX_NOTES, "notebook_full", "随手本已满，请先整理一些记录")
                val recent = db.world().writings(taId).take(2)
                val result = reader.write(endpoint, ta, recent)
                allowed()
                db.withTransaction {
                    allowed()
                    checkReading(recent.all { db.world().writing(it.id) == it }, "context_changed", "近期随笔已经变化，本次结果没有保存")
                    checkReading(db.world().recordCount(taId) < WorldReading.MAX_NOTES, "notebook_full", "随手本已满，本次没有保存")
                    if (result == null) status("TA 这次没有想写的，没有新增随笔")
                    else {
                        phase = "save_writing"
                        db.world().add(WorldWritingEntity(companionId = taId, runId = token, kind = result.kind, title = result.title, content = result.content, createdAt = System.currentTimeMillis()))
                        status("TA 留下了一段${WorldWritingData.kinds.getValue(result.kind)}，已收进随手本")
                    }
                }
                return
            }
            if (album != null) {
                if (albumPhoto == null) {
                    allowed(); phase = "choose_album"
                    val candidates = WorldAlbumConsent.candidates(db.worldAlbum().photos(taId), db.worldAlbum().looks(taId))
                    checkReading(candidates.isNotEmpty(), "album_empty", "相册暂时没有可看的照片")
                    val chosen = reader.chooseAlbum(endpoint, ta, candidates)
                    allowed()
                    checkReading(candidates.all { db.worldAlbum().photo(it.id) == it }, "picture_changed", "相册候选已变化，本次结果未保存")
                    if (chosen == null) { db.withTransaction { allowed(); status("TA 这次暂时不翻相册，没有发送照片") }; return }
                    albumPhoto = candidates.single { it.id == chosen }
                    val thisJob = currentCoroutineContext()[Job]
                    synchronized(jobs) { jobs[taId]?.takeIf { it.job == thisJob }?.let { jobs[taId] = it.copy(articleId = chosen) } }
                    db.withTransaction { allowed(); db.world().state(taId)?.let { db.world().put(it.copy(albumPhotoId = chosen)) } }
                }
                val selectedPhoto = requireNotNull(albumPhoto)
                allowed(); phase = "album_pixels"
                val pixels = albumPixels(selectedPhoto)
                checkReading(pixels != null, "picture_missing", "本地照片缺失或损坏，没有发送请求")
                allowed(); phase = "look_album"
                val result = reader.lookAlbum(endpoint, ta, selectedPhoto, requireNotNull(pixels))
                allowed()
                if (result == null) { db.withTransaction { allowed(); status("TA 这次想先放一放，没有确认看过照片") }; return }
                phase = "save_album"
                db.withTransaction {
                    allowed()
                    checkReading(db.world().recordCount(taId) < WorldReading.MAX_NOTES, "notebook_full", "世界记录已满，本次感想未保存")
                    db.worldAlbum().add(WorldAlbumLookEntity(companionId = taId, photoId = selectedPhoto.id, photoSha256 = selectedPhoto.sha256,
                        runId = token, content = result.content, question = result.question, createdAt = System.currentTimeMillis()))
                    status(if (result.content.isNotBlank() || result.question.isNotBlank()) "TA 看过这张照片，感想留在相册里" else "TA 看过这张照片，没有留下感想")
                }
                return
            }
            if (picture != null) {
                if (pictureImage == null) {
                    allowed(); phase = "choose_picture"
                    val candidates = WorldPictureExplore.candidates(taId, db.world().articles(taId), db.world().pictureLooks(taId))
                    checkReading(candidates.isNotEmpty(), "picture_empty", "窗外暂时没有可获取配图的百科条目")
                    val chosen = reader.choosePicture(endpoint, ta, candidates)
                    allowed()
                    checkReading(candidates.all { db.world().article(it.id) == it }, "picture_changed", "候选条目已变化，本次未获取配图")
                    if (chosen == null) { db.withTransaction { allowed(); status("TA 这次想先放一放，没有获取或观看配图") }; return }
                    pictureArticle = candidates.single { it.id == chosen }
                    jobs.computeIfPresent(taId) { _, active -> active.copy(articleId = chosen) }
                    db.withTransaction { allowed(); db.world().state(taId)?.let { db.world().put(it.copy(pictureArticleId = chosen, status = "正在核对公开配图与署名")) } }
                    phase = "fetch_picture"
                    pictureImage = requireNotNull(picture.fetch)(taId, picture.createdAt, requireNotNull(pictureArticle)) { allowed(); true }
                    allowed()
                    checkReading(pictureImage != null, "picture_missing", "这份条目没有可确认署名与许可的配图，没有发送看图请求")
                }
                val selectedPicture = requireNotNull(pictureImage)
                allowed(); phase = "picture_pixels"
                val pixels = picturePixels(selectedPicture)
                checkReading(pixels != null, "picture_missing", "本地配图缺失或损坏，没有发送看图请求")
                allowed(); phase = "look_picture"
                val result = reader.look(endpoint, ta, selectedPicture, requireNotNull(pixels))
                allowed()
                if (result == null) { db.withTransaction { allowed(); status("TA 这次想先放一放，没有确认看过图片") }; return }
                phase = "save_picture"
                db.withTransaction {
                    allowed()
                    checkReading(db.world().recordCount(taId) < WorldReading.MAX_NOTES, "notebook_full", "随手本已满，本次看图记录没有保存")
                    db.world().add(WorldPictureLookEntity(companionId = taId, articleId = selectedPicture.articleId, pictureSha256 = selectedPicture.sha256,
                        runId = token, content = result.content, question = result.question, createdAt = System.currentTimeMillis()))
                    status(if (result.content.isNotBlank() || result.question.isNotBlank()) "TA 看过这张配图，留下了几句感想" else "TA 看过这张配图，没有留下笔记")
                }
                return
            }
            if (topic != null) {
                allowed()
                phase = "topic"
                val query = reader.topic(endpoint, ta, db.world().recentNotes(taId), topic.recent, db.world().pictureLooks(taId).take(2))
                allowed()
                if (query == null || WorldTopicChoice.repeated(query, topic.recent)) {
                    db.withTransaction { allowed(); status(if (query == null) "TA 这次想先放一放，没有查阅或发送消息" else "这个主题近期已查过，本次没有重复搜索") }
                    return
                }
                phase = "lookup"
                val ids = try { topic.fetch(query).toSet() } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { throw WorldReplyException("lookup_failed", "百科查阅没有完成，请检查网络或稍后再试") }
                allowed()
                checkReading(ids.size <= 3, "invalid_result", "查阅结果超出本次范围")
                focus = query
                articles = db.world().articles(taId).filter { it.space == "outside" && it.id in ids && it.readOffset < it.content.length }
                if (articles.isEmpty()) {
                    db.withTransaction { allowed(); status(if (ids.isEmpty()) "本次没有找到可读摘要，次数已退回，问题仍保留" else "找到的摘要已经看过，没有重复阅读，次数已退回", refund = true) }
                    return
                }
            }
            allowed()
            phase = "choose"
            val memory = if (space == "study") readingMemories(taId) else emptyList()
            val decision = reader.decide(endpoint, ta, articles, db.world().recentNotes(taId), wishes, shelf, mayWish, outside = space == "outside", focus = focus, looks = db.world().pictureLooks(taId).take(2),
                studyContext = if (space == "study") WorldStudyMemory.reading(taId, null, shelf, wishes, emptyList(), memory) else null)
            allowed()
            decision.wish?.let { wish ->
                phase = "save_wish"
                db.withTransaction {
                    allowed()
                    val current = db.world().wishes(taId)
                    val currentShelf = db.world().articles(taId)
                    if (WorldWishes.duplicate(wish.title, current, currentShelf)) status("这本书已在书架或想读清单中，没有重复添加")
                    else {
                        checkReading(WorldWishes.mayAsk(current, System.currentTimeMillis()), "wish_changed", "想读清单已变化，本次请求未保存")
                        db.world().add(WorldWishEntity(companionId = taId, title = wish.title, author = wish.author, reason = wish.reason,
                            identity = WorldWishes.identity(wish.title), runId = token, createdAt = System.currentTimeMillis()))
                        status("TA 记下了想读的《${wish.title}》，可在「想读的」查看；尚未阅读正文")
                    }
                }
                return
            }
            val chosen = decision.articleId
            if (chosen == null) { db.withTransaction { allowed(); status("TA 这次想先放一放，没有发送消息") }; return }
            checkReading(db.world().recordCount(taId) < WorldReading.MAX_NOTES, "notebook_full", "随手本已满，请先整理一些笔记")
            val article = articles.single { it.id == chosen }
            checkReading(db.world().article(chosen) == article, "article_changed", "文章已经变化，本次阅读已停止")
            val end = WorldReading.end(article.content, article.readOffset)
            phase = "read"
            val bookParts = if (space == "study") WorldBookVolumes.books(shelf).first { b -> b.parts.any { it.id == chosen } }.parts.map { it.id } else emptyList()
            val context = if (space == "study") WorldStudyMemory.reading(taId, article, shelf, wishes, db.world().studyNotes(taId, bookParts), readingMemories(taId)) else null
            val result = reader.read(endpoint, ta, article, end, focus, studyContext = context)
            allowed()
            if (result == null) { db.withTransaction { allowed(); status("TA 暂时停下了，阅读位置没有改变") }; return }
            phase = "save_reading"
            db.withTransaction {
                allowed()
                val now = db.world().article(chosen)
                checkReading(now == article, "article_changed", "文章或阅读位置已经变化，本次结果未保存")
                if (context != null) {
                    val live = WorldStudyMemory.reading(taId, now, db.world().articles(taId), db.world().wishes(taId),
                        db.world().studyNotes(taId, bookParts), readingMemories(taId))
                    checkReading(live == context, "context_changed", "阅读记忆或笔记已经变化，本次结果未保存，可重新阅读")
                }
                val at = System.currentTimeMillis()
                db.world().update(article.copy(readOffset = end, readAt = at))
                if (result.content.isNotEmpty() || result.question.isNotEmpty()) db.world().add(WorldNoteEntity(
                    companionId = taId, articleId = chosen, runId = token, content = result.content, question = result.question,
                    quote = result.quote, quoteStart = result.quoteOffset?.plus(article.readOffset), fromOffset = article.readOffset, toOffset = end, createdAt = at))
                val state = requireNotNull(db.world().state(taId))
                db.world().put(state.copy(token = null, failureCode = "", status = if (space == "outside") "已看过《${article.title}》的${if (article.searchQuery.isEmpty()) "订阅" else "百科导言"}摘要，未读取网页全文" else if (end == article.content.length) "已读完《${article.title}》的书架文字" else "读到了《${article.title}》第 $end 个字符"))
            }
        } catch (e: TimeoutCancellationException) {
            val failure = WorldFailure.of(e)
            runCatching { diagnostic("event=world_reading_failure route=world stage=$phase reason=${failure.code} type=${e.javaClass.simpleName}") }
            withContext(NonCancellable) { status(failure.message + if (space == "writing") "。次数已退回，没有保存随笔" else "。次数已退回，进度未更新", failure.code, refund = true) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { status(if (space == "writing") "写作已停止，次数已退回，未完成内容没有保存" else "阅读已停止，次数已退回，未完成片段没有计入进度", "cancelled", refund = true) }
        } catch (e: Exception) {
            // Provider bodies may contain credentials or ordinary model output; neither belongs in a notebook.
            val failure = WorldFailure.of(e).let { original ->
                if (original.code == "invalid_result" && phase.startsWith("save_"))
                    WorldFailure("save_failed", "阅读结果没有保存成功，可查看工具诊断后重试") else original
            }
            runCatching { diagnostic("event=world_reading_failure route=world stage=$phase reason=${failure.code} type=${e.javaClass.simpleName}") }
            val message = if (!claimed) e.message?.take(160) ?: "暂时无法开始阅读" else failure.message + if (space == "writing") "。次数已退回，没有保存随笔" else "。次数已退回，进度未更新"
            if (!claimed && db.companions().get(taId) != null && db.world().state(taId) == null) {
                db.world().put(WorldReadingStateEntity(taId, LocalDate.now().toEpochDay(), 0, status = message, failureCode = "prepare", space = space, pictureArticleId = pictureImage?.articleId, albumPhotoId = albumPhoto?.id))
            } else status(message, if (claimed) failure.code else "prepare", refund = claimed)
        }
    }
}
