package com.cleo.cleos.data

import androidx.room.withTransaction
import com.cleo.cleos.ai.*
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/** One active translation per message; stale, edited, deleted, restored or reconfigured sources cannot save. */
class MessageTranslations(private val db: AppDatabase, private val keyFor: suspend (String) -> String?,
    private val keyRevision: suspend (String) -> String, private val translator: MessageTranslator,
    private val scope: CoroutineScope, private val privateBusy: (Long) -> Boolean = { false }) {
    data class Task(val token: String, val language: String, val sourceStamp: String, val busy: Boolean = true, val error: String = "")
    private data class Source(val key: TranslationKey, val stamp: String, val text: String, val ta: CompanionEntity)
    private val lock = Any(); private var paused = false; private var epoch = 0L
    private val jobs = mutableMapOf<TranslationKey, Job>()
    private val tasks = MutableStateFlow<Map<TranslationKey, Task>>(emptyMap())
    val states = tasks.asStateFlow()
    private val slots = Semaphore(2)
    fun observe(key: TranslationKey): Flow<List<MessageTranslationEntity>> = if (key.group) db.translations().observeGroup(key.id) else db.translations().observePrivate(key.id)
    private suspend fun source(key: TranslationKey): Source? {
        if (key.id <= 0) return null
        val text: String; val stamp: String; val taId: Long
        if (key.group) {
            val m = db.groups().message(key.id) ?: return null
            text = MessageTranslationRules.source(m) ?: return null; stamp = MessageTranslationRules.stamp(m) ?: return null
            taId = m.speakerId ?: return null
        } else {
            val m = db.messages().get(key.id) ?: return null
            if (privateBusy(m.conversationId)) return null
            text = MessageTranslationRules.source(m) ?: return null; stamp = MessageTranslationRules.stamp(m) ?: return null
            taId = db.conversations().get(m.conversationId)?.companionId ?: return null
        }
        return db.companions().get(taId)?.let { Source(key, stamp, text, it) }
    }
    fun request(key: TranslationKey, expectedStamp: String, language: String) {
        if (language !in MessageTranslationRules.languages) return
        synchronized(lock) {
            if (paused) return
            if (key in jobs) {
                if (tasks.value[key]?.sourceStamp == expectedStamp) return
                jobs[key]?.cancel()
            }
            val generation = epoch; val token = UUID.randomUUID().toString()
            tasks.update { it + (key to Task(token, language, expectedStamp)) }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var problem = ""
                try {
                    slots.withPermit {
                        val original = db.withTransaction { source(key) }
                        check(original != null && original.stamp == expectedStamp) { "消息或 TA 已变化，或本轮还未结束，请重新打开翻译" }
                        val cached = if (key.group) db.translations().groupResult(key.id, language) else db.translations().privateResult(key.id, language)
                        if (cached?.sourceStamp == original.stamp) return@withPermit
                        val ta = original.ta
                        check(ta.apiBaseUrl.isNotBlank() && ta.apiModel.isNotBlank()) { "请先配置这位 TA 的模型" }
                        val revision = keyRevision(ta.apiBaseUrl)
                        val secret = keyFor(ta.apiBaseUrl)?.takeIf { it.isNotBlank() } ?: error("请先配置这位 TA 的 API Key")
                        check(keyRevision(ta.apiBaseUrl) == revision) { "模型密钥已变化，请重试" }
                        val translated = translator.translate(ApiEndpoint(ta.apiBaseUrl, secret, ta.apiModel), original.text, language)
                        currentCoroutineContext().ensureActive()
                        check(keyRevision(ta.apiBaseUrl) == revision) { "模型密钥已变化，未保存旧结果" }
                        db.withTransaction {
                            currentCoroutineContext().ensureActive()
                            check(synchronized(lock) { !paused && generation == epoch }) { "资料已变化，未保存旧结果" }
                            val current = source(key)
                            check(current != null && current.stamp == original.stamp && WorldReading.sameContext(ta, current.ta)) { "消息或 TA 已变化，未保存旧结果" }
                            check(keyRevision(ta.apiBaseUrl) == revision) { "模型密钥已变化，未保存旧结果" }
                            check(synchronized(lock) { !paused && generation == epoch && tasks.value[key]?.token == token }) { "翻译已停止，未保存旧结果" }
                            db.translations().put(MessageTranslationEntity(privateMessageId = key.id.takeUnless { key.group },
                                groupMessageId = key.id.takeIf { key.group }, language = language, sourceStamp = original.stamp,
                                text = translated, createdAt = System.currentTimeMillis()))
                            db.translations().prune()
                        }
                    }
                } catch (e: TimeoutCancellationException) { problem = "翻译等待超时，可以重试"
                } catch (e: CancellationException) { throw e
                } catch (e: ChatException) { problem = e.message ?: "模型请求失败，请稍后重试"
                } catch (e: Exception) { problem = e.message ?: "翻译没有完成，请重试"
                } finally {
                    synchronized(lock) {
                        if (tasks.value[key]?.token == token) {
                            jobs.remove(key)
                            tasks.update {
                                val next = if (generation == epoch && problem.isNotEmpty()) it + (key to Task(token, language, expectedStamp, false, problem)) else it - key
                                val keep = next.filterValues { !it.busy }.keys.toList().takeLast(80).toSet()
                                next.filter { (k, v) -> v.busy || k in keep }
                            }
                        }
                    }
                }
            }
            jobs[key] = job; job.start()
        }
    }
    fun cancel(key: TranslationKey) { synchronized(lock) { jobs[key]?.cancel() } }
    suspend fun pause() {
        val old = synchronized(lock) { paused = true; epoch++; jobs.values.toList() }
        old.forEach { it.cancel() }; old.joinAll()
        synchronized(lock) { jobs.clear(); tasks.value = emptyMap() }
    }
    fun resume() { synchronized(lock) { paused = false } }
}
