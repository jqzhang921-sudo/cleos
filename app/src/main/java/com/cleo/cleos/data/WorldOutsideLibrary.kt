package com.cleo.cleos.data

import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** Manual and separately opted-in fetches. Restores and TA deletion drain both before replacing data. */
class WorldOutsideLibrary(private val db: AppDatabase, private val feeds: WorldOutside, private val scope: CoroutineScope,
    private val topics: WorldTopicSearch = WorldTopicSearch(),
) {
    data class State(val busy: Boolean = false, val message: String = "", val query: String = "", val articleIds: List<Long> = emptyList())
    private data class Active(val job: Deferred<State?>, val background: Boolean, val space: String)
    private val jobs = ConcurrentHashMap<Long, Active>()
    private val updates = MutableStateFlow<Map<Long, State>>(emptyMap())
    val states = updates.asStateFlow()
    fun busy(ta: Long): Boolean = jobs.containsKey(ta)
    @Volatile private var paused = false
    fun refresh(ta: Long, createdAt: Long, sourceId: String) { launchRefresh(ta, createdAt, sourceId, false) { true } }
    fun search(ta: Long, createdAt: Long, query: String) { launchRefresh(ta, createdAt, "wikipedia", false, WorldTopicSearch.query(query)) { true } }
    suspend fun refreshInBackground(ta: Long, createdAt: Long, sourceId: String, permission: suspend () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val job = launchRefresh(ta, createdAt, sourceId, true, permission = permission) ?: return false
        try { return job.await() != null } catch (e: CancellationException) {
            withContext(NonCancellable) { job.cancelAndJoin() }; throw e
        }
    }
    suspend fun searchInBackground(ta: Long, createdAt: Long, query: String, permission: suspend () -> Boolean): State {
        currentCoroutineContext().ensureActive()
        val job = launchRefresh(ta, createdAt, "wikipedia", true, WorldTopicSearch.query(query), permission)
            ?: error("另一次查阅正在进行")
        try { return job.await() ?: error("本次查阅没有完成") } catch (e: CancellationException) {
            withContext(NonCancellable) { job.cancelAndJoin() }; throw e
        }
    }
    private fun launchRefresh(ta: Long, createdAt: Long, sourceId: String, background: Boolean, query: String = "",
        permission: suspend () -> Boolean,
    ): Deferred<State?>? = synchronized(jobs) {
        if (paused || jobs.containsKey(ta)) return@synchronized null
        val source = if (query.isEmpty()) WorldOutside.sources.single { it.id == sourceId } else WorldTopicSearch.source
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                check(!paused && permission() && db.companions().get(ta)?.createdAt == createdAt)
                // A full outside shelf does not silently drop a search's results after a network request.
                check(query.isEmpty() || db.world().articles(ta).count { it.space == "outside" } < WorldOutside.MAX_ITEMS)
                val entries = if (query.isEmpty()) feeds.fetch(sourceId) else topics.search(query)
                currentCoroutineContext().ensureActive()
                val result = db.withTransaction {
                    check(!paused && permission() && db.companions().get(ta)?.createdAt == createdAt)
                    val existing = db.world().articles(ta)
                    val seenUrls = existing.filter { it.space == "outside" }.map { it.originUrl }.toMutableSet()
                    val seenText = existing.map { it.fingerprint }.toMutableSet()
                    var capacity = (WorldOutside.MAX_ITEMS - seenUrls.size).coerceAtLeast(0)
                    var added = 0; var full = false
                    val ids = mutableListOf<Long>()
                    for (entry in entries) {
                        currentCoroutineContext().ensureActive()
                        val old = existing.firstOrNull { it.space == "outside" && it.originUrl == entry.url }
                        if (old != null) { ids += old.id; continue }
                        val article = entry.article(ta, source, System.currentTimeMillis()).copy(searchQuery = query)
                        if (article.fingerprint in seenText) {
                            existing.firstOrNull { it.space == "outside" && it.fingerprint == article.fingerprint }?.let { ids += it.id }
                            continue
                        }
                        if (capacity == 0) { full = true; continue }
                        ids += db.world().add(article); seenUrls += entry.url; seenText += article.fingerprint; capacity--; added++
                    }
                    val message = if (full) "新增 $added 条摘要；窗外已满，可先移除一些旧见闻。"
                        else if (query.isNotEmpty()) {
                            if (entries.isEmpty()) "没有找到可读的百科导言摘要，可以换几个检索词；这不代表问题已有答案。"
                            else "找到 ${ids.distinct().size} 条摘要，新增 $added 条；已有内容与阅读位置保留，本次查找未发起模型阅读。"
                        } else if (entries.isEmpty()) "订阅源没有返回可读摘要，原有见闻保留。" else "新增 $added 条摘要，已有内容保留。"
                    State(message = message, query = query, articleIds = ids.distinct())
                }
                updates.update { it + (ta to result) }
                result
            } catch (e: CancellationException) {
                updates.update { it + (ta to State(message = if (query.isEmpty()) "刷新已停止，原有见闻保留" else "查阅已停止，原有见闻保留", query = query)) }
                throw e
            } catch (_: Exception) { updates.update { it + (ta to State(message = if (query.isEmpty()) "这次没有刷新成功，请检查网络或浏览设置后重试；原有见闻保留。" else "这次查阅没有完成，请检查网络，或先整理已满的窗外；原有见闻保留，没有扣阅读次数。", query = query)) }; null }
            finally { synchronized(jobs) { jobs.remove(ta) } }
        }
        jobs[ta] = Active(job, background, if (query.isEmpty()) "outside" else "topic"); updates.update { it + (ta to State(true,
            if (query.isEmpty()) "正在获取${source.name}的订阅摘要" else "正在查找百科导言摘要", query)) }; job.start(); job
    }
    fun cancel(ta: Long) { jobs[ta]?.job?.cancel() }
    fun cancelBackground(ta: Long, space: String = "outside") { jobs[ta]?.takeIf { it.background && it.space == space }?.job?.cancel() }
    suspend fun stopBackground(space: String? = null) {
        val active = jobs.values.filter { it.background && (space == null || it.space == space) }.map { it.job }
        active.forEach { it.cancel() }; active.forEach { it.join() }
    }
    suspend fun forget(ta: Long) { jobs[ta]?.job?.cancelAndJoin(); updates.update { it - ta } }
    suspend fun stopAll() {
        val active = synchronized(jobs) { paused = true; jobs.values.map { it.job } }
        active.forEach { it.cancel() }; active.forEach { it.join() }
        updates.value = emptyMap()
    }
    fun resume() { paused = false }
}
