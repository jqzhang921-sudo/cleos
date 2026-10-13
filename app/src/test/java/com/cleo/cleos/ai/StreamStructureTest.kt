package com.cleo.cleos.ai

import org.junit.Assert.*
import org.junit.Test

class StreamStructureTest {
    @Test fun textOnlyReplyDoesNotImplyAToolWasReturned() {
        val p = StreamParser()
        p.feed("""{"choices":[{"delta":{"content":"tool_calls {city: secret-city} </think>","reasoning_content":"private-thought"},"finish_reason":"stop"}]}""")
        assertTrue(p.toolCalls().isEmpty())
        val d = p.diagnostic()
        assertTrue(d.contains("toolChunks=0 namedToolChunks=0"))
        assertTrue(d.contains("finish=stop"))
        assertTrue(d.contains("deltaKeys=content,reasoning_content"))
        assertFalse(d.contains("secret-city")); assertFalse(d.contains("private-thought"))
    }

    @Test fun receivedToolFragmentsAndCompletedCallsAreCountedSeparately() {
        val p = StreamParser()
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"secret-id","function":{"name":"get_weather","arguments":"{"}}]}}]}""")
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]}""")
        assertEquals(1, p.toolCalls().size)
        val d = p.diagnostic()
        assertTrue(d.contains("toolChunks=2 namedToolChunks=1")); assertTrue(d.contains("pendingCalls=1"))
        assertTrue(d.contains("finish=tool_calls")); assertFalse(d.contains("secret-id"))
    }

    @Test fun aNamelessFragmentCanBeDistinguishedFromNoCall() {
        val p = StreamParser()
        p.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""")
        assertTrue(p.toolCalls().isEmpty())
        assertTrue(p.diagnostic().contains("toolChunks=1 namedToolChunks=0"))
        assertTrue(p.diagnostic().contains("pendingCalls=1"))
    }

    @Test fun nonFirstChoiceAndAlternateShapesRemainVisibleWithoutChangingExecution() {
        val p = StreamParser()
        p.feed("""{"choices":[{"delta":{"function_call":{"name":"secret-name"},"tool_calls":{}}},{"delta":{"tool_calls":[{"function":{"name":"get_weather"}}]}}]}""")
        assertTrue(p.toolCalls().isEmpty())
        val d = p.diagnostic()
        assertTrue(d.contains("nonFirstToolChunks=1 otherToolShapes=1 legacyCalls=1"))
        assertFalse(d.contains("secret-name"))
    }

    @Test fun wholeReplyMessageAndIgnoredMessageAlongsideDeltaAreDistinguished() {
        val p = StreamParser()
        p.wholeReply("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
        assertTrue(p.diagnostic().contains("deltaKeys= messageKeys=content"))
        val ignored = StreamParser()
        ignored.feed("""{"choices":[{"delta":{},"message":{"tool_calls":[{"function":{"name":"get_weather"}}]}}]}""")
        assertTrue(ignored.toolCalls().isEmpty())
        assertTrue(ignored.diagnostic().contains("messageKeys=tool_calls toolChunks=1"))
    }

    @Test fun unknownFieldNamesFinishValuesAndMalformedPayloadsCannotLeak() {
        val p = StreamParser()
        p.feed("not-json-private")
        p.feed("""{"sk-root-secret":"private","choices":[{"delta":{"sk-field-secret":"private","content":"private-body"},"finish_reason":"sk-finish-secret"}]}""")
        val d = p.diagnostic()
        assertTrue(d.contains("chunks=2 invalidChunks=1")); assertTrue(d.contains("finish=other"))
        assertFalse(d.contains("secret")); assertFalse(d.contains("private"))
    }
}
