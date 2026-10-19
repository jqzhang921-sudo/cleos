package com.cleo.cleos.ai

import org.junit.Assert.*
import org.junit.Test

class CallFocusTest {
    @Test fun deniedOrThrowingFocusDoesNotBecomeReadyFromALateCallback() {
        for (request in listOf<() -> Boolean>({ false }, { throw IllegalStateException("denied") })) {
            val seen = mutableListOf<CallFocusState>()
            val lease = CallFocusLease { seen += it }
            assertFalse(lease.take(request))
            lease.change(CallFocusState.Ready)
            assertTrue(seen.isEmpty())
        }
    }

    @Test fun transientPauseResumesButPermanentLossCannotResumeTheOldCall() {
        val seen = mutableListOf<CallFocusState>()
        val lease = CallFocusLease { seen += it }
        assertTrue(lease.take { true })
        assertFalse(lease.take { fail("duplicate request"); true })
        lease.change(CallFocusState.Paused)
        lease.change(CallFocusState.Ready)
        lease.change(CallFocusState.Lost)
        lease.change(CallFocusState.Ready)
        assertEquals(listOf(CallFocusState.Paused, CallFocusState.Ready, CallFocusState.Lost), seen)
    }

    @Test fun abandoningFirstDisarmsCallbacksEvenIfNativeAbandonFails() {
        val seen = mutableListOf<CallFocusState>()
        val lease = CallFocusLease { seen += it }
        assertTrue(lease.take { true })
        lease.give {
            lease.change(CallFocusState.Ready)
            error("synthetic abandon failure")
        }
        lease.change(CallFocusState.Paused)
        assertTrue(seen.isEmpty())
    }
}
