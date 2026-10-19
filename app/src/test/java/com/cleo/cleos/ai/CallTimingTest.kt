package com.cleo.cleos.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CallTimingTest {
    @Test fun firstTextAndPlaybackAreIndependentOneTimeMilestones() {
        var now=100L
        val lines=mutableListOf<String>()
        val trace=CallTiming(lines::add,true,100,{now},"1234abcd")
        now=350;trace.first(CallTimingStage.FIRST_TEXT)
        now=400;trace.first(CallTimingStage.FIRST_TEXT)
        now=900;trace.first(CallTimingStage.PLAYBACK_START)
        assertEquals(2,lines.size)
        assertTrue(lines[0].contains("route=incoming_call") && lines[0].endsWith("elapsedMs=250"))
        assertTrue(lines[1].contains("stage=playback_start") && lines[1].endsWith("elapsedMs=800"))
    }
    @Test fun measuresEachServiceSeparatelyAndReturnsTheOriginalResult() = runBlocking {
        var now=0L
        val lines=mutableListOf<String>()
        val trace=CallTiming(lines::add,false,0,{now},"1234abcd")
        assertEquals("audio",trace.measure(CallTimingStage.SYNTHESIZE){now=650;"audio"})
        trace.measure(CallTimingStage.DECODE){now=700}
        assertTrue(lines[1].contains("stage=synthesize result=ok elapsedMs=650"))
        assertTrue(lines[3].contains("stage=decode result=ok elapsedMs=50"))
    }
    @Test fun errorMetadataDoesNotIncludeExceptionProse() = runBlocking {
        val lines=mutableListOf<String>()
        val trace=CallTiming(lines::add,true,0,{100},"1234abcd")
        val error=java.io.IOException("private words api-key https://private.invalid")
        assertSame(error,runCatching{trace.measure(CallTimingStage.SYNTHESIZE){throw error}}.exceptionOrNull())
        assertTrue(lines.last().contains("result=failed") && lines.last().contains("category=network"))
        assertFalse(lines.joinToString().contains("private"))
        assertFalse(lines.joinToString().contains("api-key"))
    }
    @Test fun cancellationIsPropagatedAndHasNoFailureText() = runBlocking {
        val lines=mutableListOf<String>()
        val trace=CallTiming(lines::add,true,0,{100},"1234abcd")
        val cancelled=CancellationException("private cancellation reason")
        assertSame(cancelled,runCatching{trace.measure(CallTimingStage.MODEL){throw cancelled}}.exceptionOrNull())
        assertTrue(lines.last().contains("result=cancelled"))
        assertFalse(lines.joinToString().contains("private"))
    }
    @Test fun diagnosticsNeverBreakPlaybackAndDurationsAreBounded() = runBlocking {
        val trace=CallTiming({error("synthetic write failure")},true,100,{0},"1234abcd")
        trace.first(CallTimingStage.FIRST_TEXT)
        assertEquals(42,trace.measure(CallTimingStage.DECODE){42})
        var now=0L
        val lines=mutableListOf<String>()
        val bounded=CallTiming(lines::add,true,100,{now},"1234abcd")
        bounded.first(CallTimingStage.FIRST_TEXT)
        now=Long.MAX_VALUE/2;bounded.first(CallTimingStage.AUDIO_READY)
        assertTrue(lines[0].endsWith("elapsedMs=0"))
        assertTrue(lines[1].endsWith("elapsedMs=86400000"))
    }
}
