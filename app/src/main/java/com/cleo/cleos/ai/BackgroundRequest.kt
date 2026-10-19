package com.cleo.cleos.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** A small retry budget for ONE background opportunity, shared by all its model rounds. */
internal class BackgroundRequest(private val pause: suspend (Long) -> Unit = { delay(it) }) {
    private var retries = 0

    fun stream(
        open: () -> Flow<ChatEvent>,
        allowed: suspend () -> Boolean = { true },
        onRequest: suspend () -> Unit = {},
        onRetry: suspend (Long) -> Unit = {},
    ): Flow<ChatEvent> = flow {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!allowed()) throw CancellationException("后台执行条件改变")
            onRequest()
            var received = false
            try {
                open().collect { event ->
                    // Even reasoning counts: never replay a partially received model response.
                    received = true
                    emit(event)
                }
                return@flow
            } catch (e: ChatException) {
                currentCoroutineContext().ensureActive()
                if (!e.retryableNetwork || received || retries >= DELAYS.size) throw e
                val wait = DELAYS[retries++]
                onRetry(wait)
                pause(wait)
            }
        }
    }

    companion object {
        private val DELAYS = longArrayOf(2_000L, 8_000L)
    }
}

/** A WorkManager/timer owner stopping must also stop its still-undecided app-scope wake. */
internal suspend fun <T> awaitBackgroundResult(result: CompletableDeferred<T>, request: Job, onStopped: () -> Unit = {}): T = try {
    result.await()
} catch (e: CancellationException) {
    // Once the wake has returned, this job may already be answering a real user message.
    if (!result.isCompleted) withContext(NonCancellable) {
        request.cancelAndJoin()
        onStopped()
    }
    throw e
}
