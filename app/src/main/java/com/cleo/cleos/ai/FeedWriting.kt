package com.cleo.cleos.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** Contention is a local scheduling result, not a model or network failure. */
internal class FeedWritingBusy : IllegalStateException(MESSAGE) {
    companion object { const val MESSAGE = "TA 正在写，请稍等" }
}

/** Shared by manual drafts, invited interactions and automatic visits. Never queues a stale draft. */
internal class FeedWriting {
    private val lock = Mutex()
    val busy: Boolean get() = lock.isLocked

    suspend fun <T> once(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!lock.tryLock()) throw FeedWritingBusy()
        return try {
            currentCoroutineContext().ensureActive()
            action()
        } finally { lock.unlock() }
    }
}
