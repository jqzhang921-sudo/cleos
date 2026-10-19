package com.cleo.cleos.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class GroupCallTranscriptionTimeoutException : Exception("这次转写等得太久，请再说一次")
internal class GroupCallPersistenceException(cause: Exception) : Exception("通话记录没能保存，先结束通话，请稍后再试", cause)

/** The complete per-turn lifecycle, shared by production and offline session tests. */
internal class GroupCallRounds(participants: List<Long>, private val transcriptionTimeoutMs: Long = GroupCallRules.TRANSCRIBE_TIMEOUT_MS) {
    private val ids = participants.toList().also { GroupCallRules.selected(it, it.toSet()) }
    private val gate = Mutex()
    private var index = 0

    suspend fun <T> run(transcribe: suspend () -> String,
        begin: suspend (String, List<Long>) -> List<T>, respond: suspend (T) -> Unit,
        finish: suspend (List<T>) -> Unit, onHeard: (String) -> Unit = {}) = gate.withLock {
        currentCoroutineContext().ensureActive()
        val raw = withTimeoutOrNull(transcriptionTimeoutMs) { transcribe() }
            ?: throw GroupCallTranscriptionTimeoutException()
        val words = GroupCallRules.userWords(raw).substringAfter('\n')
        currentCoroutineContext().ensureActive()
        onHeard(words)
        var slots: List<T>? = null
        try {
            durable {
                // Assign within the non-cancellable transaction boundary, so even immediate
                // cancellation on returning from an I/O dispatcher still has slots to finish.
                slots = begin(words, GroupCallRules.order(ids, index))
                index = if (index == Int.MAX_VALUE) 0 else index + 1
            }
            for (slot in requireNotNull(slots)) {
                currentCoroutineContext().ensureActive()
                respond(slot)
            }
        } finally { slots?.let { saved -> durable { finish(saved) } } }
    }

    private suspend fun <T> durable(block: suspend () -> T): T = withContext(NonCancellable) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: GroupCallPersistenceException) { throw e }
        catch (e: Exception) { throw GroupCallPersistenceException(e) }
    }
}

/** Completion also runs when a lazy job is cancelled before its body ever starts. */
internal fun ownedCallJob(scope: CoroutineScope, slot: CallSlot, owner: Any,
    finished: () -> Unit, block: suspend CoroutineScope.() -> Unit): Job {
    val job = scope.launch(start = CoroutineStart.LAZY, block = block)
    job.invokeOnCompletion {
        try { finished() } finally { slot.release(owner) }
    }
    return job
}
