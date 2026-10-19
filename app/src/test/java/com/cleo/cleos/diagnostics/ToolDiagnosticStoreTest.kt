package com.cleo.cleos.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ToolDiagnosticStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun groupCallFailurePinsTimingsWithoutSpeechContentOrSecrets() {
        val store=ToolDiagnosticStore(temporary.newFolder(),"0.41.116")
        store.record("event=group_call_stage route=group_call requestId=abc00001 member=1 stage=first_text result=ok elapsedMs=3200")
        store.record("event=group_call_failure route=group_call requestId=abc00001 member=1 stage=synthesize result=failed elapsedMs=120 category=network " +
            "transcript=private-words body=secret-response audioFile=private.wav apiKey=sk-secret")
        store.record("event=group_call_stage route=group_call requestId=abc00001 member=1 stage=store result=ok elapsedMs=10")
        val pinned=store.issue()
        assertTrue(pinned.contains("stage=first_text result=ok elapsedMs=3200"))
        assertTrue(pinned.contains("stage=synthesize result=failed elapsedMs=120 category=network"))
        assertTrue(pinned.contains("stage=store result=ok elapsedMs=10"))
        for(secret in listOf("private-words","secret-response","private.wav","sk-secret"))assertFalse(secret,pinned.contains(secret))
        store.record("event=group_call_stage requestId=abc00002 stage=round result=ok elapsedMs=100")
        assertEquals(pinned,store.issue())
    }
    @Test fun emptyTranscriptionAndUserCancellationDoNotAutoPinAServiceFailure() {
        val store=ToolDiagnosticStore(temporary.newFolder(),"0.41.116")
        store.record("event=group_call_input route=group_call requestId=abc00001 stage=transcribe result=failed category=empty_transcript elapsedMs=300 audioMs=900")
        store.record("event=group_call_stage route=group_call requestId=abc00001 stage=model result=cancelled elapsedMs=50")
        assertTrue(store.recent().contains("category=empty_transcript"));assertTrue(store.recent().contains("audioMs=900"))
        assertEquals("",store.issue())
    }

    @Test fun firstErrorAndItsRetrySurviveNormalRequestsAndRestart() {
        val dir = temporary.newFolder()
        val store = ToolDiagnosticStore(dir, "0.41.74", clock = { 1000L }, limit = 2048)
        store.record("requestId=first toolsPresent=true tools=52 category=request")
        store.record("requestId=first status=400 category=tool_schema_keyword_error")
        store.record("requestId=first schemaRetry=dependentRequired tools=52")
        store.record("requestId=first event=response status=200 calls=1")
        val pinned = store.issue()
        assertTrue(pinned.contains("category=request"))
        assertTrue(pinned.contains("status=400"))
        assertTrue(pinned.contains("schemaRetry=dependentRequired"))
        assertTrue(pinned.contains("event=response status=200 calls=1"))
        repeat(100) { store.record("requestId=later-$it event=response status=200 calls=0") }
        val restarted = ToolDiagnosticStore(dir, "0.41.74", limit = 2048)
        assertEquals(pinned, restarted.issue())
        assertFalse(restarted.recent().contains("requestId=first"))
        assertTrue(restarted.recent().length <= 2048)
        assertTrue(restarted.recent().endsWith("calls=0\n"))
        restarted.clear()
        assertEquals("", restarted.issue())
        assertEquals("", restarted.recent())
        restarted.record("event=mcp_list_failed failed=true cache=true tools=0")
        assertTrue(restarted.issue().contains("mcp_list_failed"))
    }

    @Test fun aProblemWithoutHttpFailureCanBeManuallyPinned() {
        val store = ToolDiagnosticStore(temporary.newFolder(), "0.41.74")
        store.record("requestId=no-call event=response status=200 calls=0")
        assertEquals("", store.issue())
        store.pin()
        assertTrue(store.issue().contains("calls=0"))
        store.record("requestId=second event=response status=200 calls=2")
        assertFalse(store.issue().contains("requestId=second"))
        store.pin()
        assertTrue(store.issue().contains("requestId=second"))
    }

    @Test fun arbitraryPayloadFieldsAreNotPersistedAndBrokenStorageCannotBreakChat() {
        val dir = temporary.newFolder()
        val store = ToolDiagnosticStore(dir, "0.41.74")
        store.record("event=response status=200 body=private-body apiKey=sk-secret url=https://host/?token=secret arguments=private-args\nraw-secret")
        val text = store.recent()
        assertTrue(text.contains("event=response status=200"))
        for (secret in listOf("private-body", "sk-secret", "https://", "private-args", "raw-secret")) assertFalse(secret, secret in text)
        val file = temporary.newFile()
        val broken = ToolDiagnosticStore(file, "0.41.74")
        broken.record("event=response status=200")
        assertEquals("", broken.recent())
        assertEquals("", broken.issue())
    }

    @Test fun responseStructurePersistsOnlyAllowlistedMetadata() {
        val store = ToolDiagnosticStore(temporary.newFolder(), "0.41.87")
        store.record("requestId=test event=response_structure chunks=8 invalidChunks=0 rootKeys=choices deltaKeys=content,tool_calls " +
            "messageKeys= toolChunks=0 namedToolChunks=0 nonFirstToolChunks=0 otherToolShapes=0 legacyCalls=0 pendingCalls=0 " +
            "finish=stop contentChars=12 reasoningChars=32 raw=private-body argument=secret-city")
        val text = store.recent()
        assertTrue(text.contains("event=response_structure chunks=8"))
        assertTrue(text.contains("deltaKeys=content,tool_calls"))
        assertTrue(text.contains("finish=stop contentChars=12 reasoningChars=32"))
        assertFalse(text.contains("private-body")); assertFalse(text.contains("secret-city"))
    }

    @Test fun concurrentRequestsProduceCompleteBoundedRecords() {
        repeat(10) {
            val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
            val store = ToolDiagnosticStore(temporary.newFolder(), "0.41.74", limit = 32768, onWriteFailure = { failures += it })
            val threads = (1..4).map { worker -> Thread { repeat(30) { store.record("requestId=$worker-$it event=response calls=1") } }.apply { start() } }
            threads.forEach { it.join() }
            val lines = store.recent().lineSequence().filter { it.isNotBlank() }.toList()
            assertEquals("write failures: " + failures.joinToString(","), 120, lines.size)
            assertTrue(lines.all { it.contains("event=response calls=1") && it.startsWith("20") })
            assertEquals("", store.issue())
        }
    }

    @Test fun transientAccessDeniedRecoversWithoutRepeatingTheRecordAppend() {
        var moves = 0; val pauses = mutableListOf<Long>()
        retryDiagnosticMove({ pauses += it }) {
            moves++
            if (moves < 3) throw java.nio.file.AccessDeniedException("synthetic")
        }
        assertEquals(3, moves); assertEquals(listOf(5L, 10L), pauses)
    }
    @Test fun persistentDeniedAndOtherIoErrorsAreBounded() {
        var moves = 0
        try { retryDiagnosticMove({}) { moves++; throw java.nio.file.AccessDeniedException("synthetic") }; fail() }
        catch (_: java.nio.file.AccessDeniedException) { }
        assertEquals(5, moves)
        moves = 0
        try { retryDiagnosticMove({}) { moves++; throw java.io.IOException("synthetic") }; fail() }
        catch (_: java.io.IOException) { }
        assertEquals(1, moves)
    }

    @Test fun galleryFailuresArePinnedWithoutPublicSearchOrResponseContent() {
        for (event in listOf("photo_search_failed", "photo_share_failed", "photo_test_failed")) {
            val store = ToolDiagnosticStore(temporary.newFolder(), "0.41.77")
            store.record("event=photo_search_http status=200")
            store.record("event=$event failed=true reason=UnknownHostException query=private-search body=private-response")
            assertTrue(store.issue().contains("status=200"))
            assertTrue(store.issue().contains("event=$event"))
            assertTrue(store.issue().contains("reason=UnknownHostException"))
            assertFalse(store.recent().contains("private-"))
            repeat(100) { store.record("event=photo_test_ok") }
            assertTrue(store.issue().contains("event=$event"))
        }
    }
}
