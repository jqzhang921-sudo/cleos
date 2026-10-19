package com.cleo.cleos.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketException

/** Reproduces a successful SSE response whose connection aborts while the model is thinking. */
class ReasoningDisconnectTest {
    private suspend fun disconnected(payload: String): Pair<List<ChatEvent>, Throwable?> {
        val source = object : Source {
            val remaining = Buffer().writeUtf8(payload)
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (remaining.size > 0) return remaining.read(sink, byteCount)
                throw SocketException("Software caused connection abort")
            }
            override fun timeout() = Timeout.NONE
            override fun close() {}
        }.buffer()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("synthetic fixture").body(object : ResponseBody() {
                    override fun contentType() = "text/event-stream".toMediaType()
                    override fun contentLength() = -1L
                    override fun source(): BufferedSource = source
                }).build()
        }.build()
        val events = mutableListOf<ChatEvent>()
        return try {
            val error = runCatching {
                ChatClient(http, diagnostic = {}).stream(ApiEndpoint("https://synthetic.invalid/v1", "fixture-key", "fixture"),
                    listOf(ApiMessage("user", "synthetic fixture"))).collect { events += it }
            }.exceptionOrNull()
            events to error
        } finally {
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }

    @Test fun reasoningFieldStaysReasoningWhenTheConnectionAborts() = runBlocking {
        val (events, failure) = disconnected("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private fixture thought\"}}]}\n\n")
        assertEquals(listOf(ChatEvent.Reasoning("private fixture thought")), events)
        assertTrue(failure is ChatException && failure.retryableNetwork)
        assertFalse(events.any { it is ChatEvent.Delta })
    }

    @Test fun unclosedExplicitThinkBlockDoesNotFallBackIntoBodyOnAbort() = runBlocking {
        val (events, failure) = disconnected("data: {\"choices\":[{\"delta\":{\"content\":\"<think>private fixture thought\"}}]}\n\n")
        assertEquals(listOf(ChatEvent.Reasoning("private fixture thought", sendBack = false)), events)
        assertTrue(failure is ChatException)
        assertFalse(events.any { it is ChatEvent.Delta })
    }

    @Test fun partialAnswerAndReasoningKeepTheirOwnChannelsOnAbort() = runBlocking {
        val (events, failure) = disconnected("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private fixture thought\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"public fixture answer\"}}]}\n\n")
        assertEquals(listOf(ChatEvent.Reasoning("private fixture thought"), ChatEvent.Delta("public fixture answer")), events)
        assertTrue(failure is ChatException)
    }
}
