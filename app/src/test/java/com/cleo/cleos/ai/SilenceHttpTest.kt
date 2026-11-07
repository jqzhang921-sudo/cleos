package com.cleo.cleos.ai

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

/** Real SSE decoding with an in-process HTTP interceptor; no server, Key or external traffic. */
class SilenceHttpTest {
    private val endpoint = ApiEndpoint("https://synthetic.invalid/v1", "fixture-key", "fixture")
    private val messages = listOf(ApiMessage("user", "hello"))

    private suspend fun withClient(response: () -> String, action: suspend (ChatClient) -> Unit) {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(response().toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        try { action(ChatClient(http, diagnostic = {})) } finally {
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }

    @Test fun fragmentedSseControlDoesNotReachPreviewSpeechOrFinalBody() = runBlocking {
        withClient({
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private fixture thought\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"SK\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"IP\"},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n"
        }) { client ->
            val speech = WakeOutput.Speech(proactive = false)
            var body = ""
            var thought = ""
            client.stream(endpoint, messages).collect { event -> when (event) {
                is ChatEvent.Delta -> {
                    body += event.text
                    assertEquals("", WakeOutput.preview(body))
                    assertEquals("", speech.append(event.text))
                }
                is ChatEvent.Reasoning -> thought += event.text
                else -> fail("unexpected tool")
            } }
            assertEquals("SKIP", body)
            assertEquals("private fixture thought", thought)
            assertEquals("", speech.finish())
            assertNull(WakeOutput.message(body, proactive = false))
            assertNull(WakeOutput.inspect(body).text)
        }
    }

    @Test fun successfulEmptyCompletionIsAQuietBackgroundDecision() = runBlocking {
        withClient({ "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n" }) { client ->
            var body = ""
            client.stream(endpoint, messages).collect { if (it is ChatEvent.Delta) body += it.text }
            assertNull(WakeOutput.inspect(body).text)
            assertEquals("本次没有发送内容", WakeOutput.inspect(body).reason)
        }
    }

    @Test fun screenshotChineseSilenceAndLocatorHeadersSurviveRealSseDecodingOnlyAsProtocol() = runBlocking {
        for ((raw, expected) in listOf("（保持安静）" to "",
            "（消息编号 #31）\n好，你赢了。\n\n（消息编号 #32）\n打够了没？" to "好，你赢了。\n\n打够了没？")) {
            withClient({ raw.map { "data: {\"choices\":[{\"delta\":{\"content\":${Json.encodeToString(it.toString())}}}]}\n\n" }.joinToString("") +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n" }) { client ->
                val speech = WakeOutput.Speech(false)
                var body = ""
                var spoken = ""
                client.stream(endpoint, messages).collect { event -> if (event is ChatEvent.Delta) {
                    body += event.text
                    spoken += speech.append(event.text)
                    assertFalse(WakeOutput.preview(body).contains("消息编号"))
                } }
                spoken += speech.finish()
                assertEquals(expected, spoken)
                assertEquals(expected, WakeOutput.message(body, false).orEmpty())
            }
        }
    }

    @Test fun groupVoiceControlAssembledFromSseArgumentsNeverReachesTheExecutor() = runBlocking {
        var requests = 0
        var executed = 0
        withClient({
            if (++requests == 1)
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"voice\",\"function\":{\"name\":\"send_voice\",\"arguments\":\"{\\\"text\\\":\\\"SK\"}}]}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"IP\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
            else "data: {\"choices\":[{\"delta\":{\"content\":\"你好。\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        }) { client ->
            val text = GroupToolReply.reply(messages, listOf(ToolSpecs.sendVoice), false,
                { context, specs, thinking -> client.stream(endpoint, context, specs, thinking) },
                { executed++; error("must not synthesize") }) {}
            assertEquals("你好。", text)
            assertEquals(0, executed)
            assertEquals(2, requests)
        }
    }
}
