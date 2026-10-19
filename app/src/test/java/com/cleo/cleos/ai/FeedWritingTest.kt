package com.cleo.cleos.ai

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FeedWritingTest {
    @Test fun contentionAfterAnIdleSnapshotNeverStartsALosingModelRequest() = runBlocking {
        val writing = FeedWriting()
        assertFalse(writing.busy) // The scheduler's earlier check can legitimately see idle.
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val winner = launch { writing.once { requests++; started.complete(Unit); release.await() } }
        try {
            withTimeout(5_000) { started.await() }
            val failure = runCatching { writing.once { requests++; error("must not request") } }.exceptionOrNull()
            assertTrue(failure is FeedWritingBusy)
            assertEquals(FeedWritingBusy.MESSAGE, failure?.message)
            assertEquals(1, requests)
            assertTrue(writing.busy)
        } finally { release.complete(Unit); winner.join() }
        assertFalse(writing.busy)
    }

    @Test fun failedContenderCannotUnlockTheActualOwnerOrQueueALateWrite() = runBlocking {
        val writing = FeedWriting()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var losingWrites = 0
        val owner = launch { writing.once { started.complete(Unit); release.await() } }
        try {
            withTimeout(5_000) { started.await() }
            repeat(3) {
                val failure = runCatching { writing.once { losingWrites++ } }.exceptionOrNull()
                assertTrue(failure is FeedWritingBusy)
                assertTrue(writing.busy)
            }
        } finally { release.complete(Unit); owner.join() }
        yield()
        assertEquals(0, losingWrites)
        assertEquals("new opportunity", writing.once { "new opportunity" })
    }

    @Test fun cancellationDuringWritingReleasesTheLockForTheNextOpportunity() = runBlocking {
        val writing = FeedWriting()
        val started = CompletableDeferred<Unit>()
        val owner = launch { writing.once { started.complete(Unit); awaitCancellation() } }
        withTimeout(5_000) { started.await(); owner.cancelAndJoin() }
        assertFalse(writing.busy)
        assertEquals("ready", writing.once { "ready" })
    }

    @Test fun realFailuresArePreservedRatherThanConvertedIntoContention() = runBlocking {
        val writing = FeedWriting()
        for (failure in listOf(ChatException("synthetic network", retryableNetwork = true),
            IllegalStateException("synthetic storage failure"), IllegalStateException(FeedWritingBusy.MESSAGE))) {
            val caught = runCatching { writing.once<Unit> { throw failure } }.exceptionOrNull()
            assertSame(failure, caught)
            assertFalse(caught is FeedWritingBusy)
            assertFalse(writing.busy)
        }
    }

    @Test fun anAlreadyCancelledCallerCannotAcquireOrStartAWrite() = runBlocking {
        val writing = FeedWriting()
        val result = CompletableDeferred<Throwable?>()
        var writes = 0
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            result.complete(runCatching { writing.once { writes++ } }.exceptionOrNull())
        }
        caller.join()
        assertTrue(result.await() is CancellationException)
        assertEquals(0, writes)
        assertFalse(writing.busy)
    }

    @Test fun successfulManualAndBackgroundWritersUseTheSameReleasedGate() = runBlocking {
        val writing = FeedWriting()
        val first = writing.once { assertTrue(writing.busy); "manual draft" }
        assertFalse(writing.busy)
        val next = writing.once { assertTrue(writing.busy); "background visit" }
        assertEquals("manual draft", first)
        assertEquals("background visit", next)
        assertFalse(writing.busy)
    }
}
