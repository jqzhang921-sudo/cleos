package com.cleo.cleos.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

class BackgroundRequestTest {
    private val endpoint = ApiEndpoint("https://synthetic.invalid/v1", "fixture-key", "fixture-model")
    private val messages = listOf(ApiMessage("user", "synthetic background fixture"))
    private val completed = "data: {\"choices\":[{\"delta\":{\"content\":\"ready\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    private suspend fun <T> client(interceptor: Interceptor, block: suspend (ChatClient) -> T): T {
        val http = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return try { block(ChatClient(http, diagnostic = {})) } finally {
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }

    private fun response(chain: Interceptor.Chain, code: Int = 200) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body((if (code == 200) completed else "{\"error\":{\"message\":\"fixture refusal\"}}")
            .toResponseBody()).build()

    @Test fun dnsThenConnectionAbortRecoverWithoutDuplicatingEvents() = runBlocking {
        val calls = AtomicInteger(); val waits = mutableListOf<Long>(); var counted = 0
        client(Interceptor { chain ->
            when (calls.incrementAndGet()) {
                1 -> throw UnknownHostException("synthetic DNS failure")
                2 -> throw SocketException("Software caused connection abort")
                else -> response(chain)
            }
        }) { chat ->
            val output = BackgroundRequest { waits += it }.stream(
                { chat.stream(endpoint, messages) }, onRequest = { counted++ }).toList()
            assertEquals(listOf(ChatEvent.Delta("ready")), output)
            assertEquals(3, calls.get()); assertEquals(3, counted)
            assertEquals(listOf(2_000L, 8_000L), waits)
        }
    }

    @Test fun retryBudgetIsSharedAcrossAllRoundsOfOneOpportunity() = runBlocking {
        val calls = AtomicInteger(); val waits = mutableListOf<Long>()
        client(Interceptor { chain ->
            if (calls.incrementAndGet() == 2) response(chain) else throw SocketTimeoutException("fixture")
        }) { chat ->
            val background = BackgroundRequest { waits += it }
            background.stream({ chat.stream(endpoint, messages) }).toList()
            val error = runCatching { background.stream({ chat.stream(endpoint, messages) }).toList() }.exceptionOrNull()
            assertTrue(error is ChatException && error.retryableNetwork)
            assertEquals(4, calls.get()); assertEquals(listOf(2_000L, 8_000L), waits)
        }
    }

    @Test fun httpRefusalsAreNeverRetried() = runBlocking {
        for (code in listOf(400, 401, 403, 429, 500)) {
            val calls = AtomicInteger()
            client(Interceptor { chain -> calls.incrementAndGet(); response(chain, code) }) { chat ->
                val error = runCatching { BackgroundRequest { fail("HTTP must not retry") }
                    .stream({ chat.stream(endpoint, messages) }).toList() }.exceptionOrNull()
                assertTrue(error is ChatException && error.status == code)
                assertEquals(1, calls.get())
            }
        }
    }

    @Test fun tlsAndTransportPermissionFailuresAreNeverRetried() = runBlocking {
        for (failure in listOf(SSLHandshakeException("fixture certificate"),
            com.cleo.cleos.network.EndpointTransportException("fixture permit denied"))) {
            val calls = AtomicInteger()
            client(Interceptor { calls.incrementAndGet(); throw failure }) { chat ->
                val error = runCatching { BackgroundRequest { fail("Security errors must not retry") }
                    .stream({ chat.stream(endpoint, messages) }).toList() }.exceptionOrNull()
                assertTrue(error is ChatException && !error.retryableNetwork)
                assertEquals(1, calls.get())
            }
        }
    }

    @Test fun noReplayAfterTextReasoningOrToolOutput() = runBlocking {
        for (event in listOf(ChatEvent.Delta("partial"), ChatEvent.Reasoning("partial"), ChatEvent.ToolCalls(emptyList()))) {
            var calls = 0; val received = mutableListOf<ChatEvent>()
            val failure = ChatException("fixture lost connection", retryableNetwork = true)
            val error = runCatching {
                BackgroundRequest { fail("Partial output must not retry") }.stream({ flow {
                    calls++; emit(event); throw failure
                } }).collect { received += it }
            }.exceptionOrNull()
            assertSame(failure, error); assertEquals(1, calls); assertEquals(listOf(event), received)
        }
    }

    @Test fun typingOrSettingsChangeDuringBackoffPreventsAnotherRequest() = runBlocking {
        var allowed = true; var calls = 0
        val error = runCatching {
            BackgroundRequest { allowed = false }.stream({ flow<ChatEvent> {
                calls++; throw ChatException("fixture network", retryableNetwork = true)
            } }, allowed = { allowed }).toList()
        }.exceptionOrNull()
        assertTrue(error is CancellationException); assertEquals(1, calls)
    }

    @Test fun cancellingBackoffStopsFurtherRequests() = runBlocking {
        var calls = 0; val waiting = CompletableDeferred<Unit>()
        val job = launch {
            BackgroundRequest { waiting.complete(Unit); awaitCancellation() }.stream({ flow<ChatEvent> {
                calls++; throw ChatException("fixture network", retryableNetwork = true)
            } }).toList()
        }
        withTimeout(5_000) { waiting.await(); job.cancelAndJoin() }
        assertEquals(1, calls)
    }

    @Test fun stoppingWorkerWaitsForRequestCleanupAndResumesQueuedReply() = runBlocking {
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>(); val cleaned = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<String>(); var resumed = false
        val request = app.launch {
            try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { yield(); cleaned.complete(Unit) } }
        }
        try {
            val owner = launch { awaitBackgroundResult(outcome, request) { resumed = true } }
            withTimeout(5_000) { started.await(); yield(); owner.cancelAndJoin() }
            assertTrue(request.isCancelled); assertTrue(cleaned.isCompleted); assertTrue(resumed)
        } finally { app.cancel() }
    }

    @Test fun finishedWakeDoesNotCancelTheRealReplyThatFollows() = runBlocking {
        val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val request = app.launch { awaitCancellation() }
        val outcome = CompletableDeferred<String>()
        try {
            val owner = launch(start = CoroutineStart.UNDISPATCHED) { awaitBackgroundResult(outcome, request) }
            // Completion dispatches the owner, then WorkManager cancels it before it resumes.
            outcome.complete("wake finished")
            owner.cancelAndJoin()
            assertTrue(request.isActive)
            assertEquals("wake finished", awaitBackgroundResult(outcome, request))
        } finally { app.cancel() }
    }
}
