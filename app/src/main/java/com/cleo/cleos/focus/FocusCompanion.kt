package com.cleo.cleos.focus

import androidx.room.withTransaction
import com.cleo.cleos.ai.ApiEndpoint
import com.cleo.cleos.ai.FocusCompanionReader
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import kotlinx.coroutines.*

/** Requests live outside the screen. Conditional partial writes never overwrite timer state. */
class FocusCompanion(
    private val db: AppDatabase,
    private val keyFor: suspend (String) -> String?,
    private val keyRevision: suspend (String) -> String,
    private val reader: FocusCompanionReader,
    private val scope: CoroutineScope,
) {
    private val jobs = mutableMapOf<String, Job>()
    private val recovery = scope.async {
        db.withTransaction { db.focus().recoverStartReplies(); db.focus().recoverEndReplies() }
    }
    private suspend fun write(id: String, end: Boolean, expected: String, state: String, text: String? = null) =
        if (end) db.focus().endReply(id, expected, state, text) else db.focus().startReply(id, expected, state, text)

    fun request(id: String, end: Boolean, retry: Boolean = false) {
        val key = "$id:$end"
        synchronized(jobs) {
            if (key in jobs) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    recovery.await()
                    val original = db.withTransaction {
                        val s = db.focus().get(id) ?: return@withTransaction null
                        if (!FocusCompanionRules.canRequest(s, end, retry)) return@withTransaction null
                        val ta = s.companionId?.let { db.companions().get(it) }
                        if (ta == null) { write(id, end, FocusCompanionRules.state(s, end), "failed"); return@withTransaction null }
                        if (write(id, end, FocusCompanionRules.state(s, end), "running") != 1) return@withTransaction null
                        s to ta
                    } ?: return@launch
                    val (session, ta) = original
                    check(ta.apiBaseUrl.isNotBlank() && ta.apiModel.isNotBlank())
                    val revision = keyRevision(ta.apiBaseUrl)
                    val secret = keyFor(ta.apiBaseUrl)?.takeIf { it.isNotBlank() } ?: error("Unconfigured model")
                    check(keyRevision(ta.apiBaseUrl) == revision)
                    val text = reader.read(ApiEndpoint(ta.apiBaseUrl, secret, ta.apiModel), ta, session, end)
                    currentCoroutineContext().ensureActive()
                    check(keyRevision(ta.apiBaseUrl) == revision)
                    db.withTransaction {
                        currentCoroutineContext().ensureActive()
                        val current = db.focus().get(id)
                        val currentTa = db.companions().get(ta.id)
                        if (current == null || !FocusCompanionRules.boundary(current, end) || current.companionId != ta.id ||
                            !same(ta, currentTa) || keyRevision(ta.apiBaseUrl) != revision) {
                            write(id, end, "running", "skipped"); return@withTransaction
                        }
                        write(id, end, "running", if (text == null) "silent" else "ready", text)
                    }
                } catch (_: CancellationException) {
                    withContext(NonCancellable) { write(id, end, "running", "failed") }
                } catch (_: Exception) {
                    // Keep error bodies, keys and provider messages out of visible writing and records.
                    write(id, end, "running", "failed")
                } finally { synchronized(jobs) { jobs.remove(key) } }
            }
            jobs[key] = job; job.start()
        }
    }

    suspend fun stop(id: String) {
        recovery.await()
        // Change persisted acceptance first; late network output cannot revive the opportunity.
        db.withTransaction {
            for (end in listOf(false, true)) {
                val s = db.focus().get(id) ?: continue
                val state = FocusCompanionRules.state(s, end)
                if (state in setOf("pending", "running", "failed")) write(id, end, state, "skipped")
            }
        }
        synchronized(jobs) { jobs["$id:false"]?.cancel(); jobs["$id:true"]?.cancel() }
    }

    private fun same(a: CompanionEntity, b: CompanionEntity?) = b != null && a.id == b.id && a.createdAt == b.createdAt &&
        a.name == b.name && a.persona == b.persona && a.apiBaseUrl == b.apiBaseUrl && a.apiModel == b.apiModel
}
