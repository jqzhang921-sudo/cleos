package com.cleo.cleos.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class MessageTranslatorTest {
    @Test fun acceptsOneStructuredTranslationAndKeepsItsLineBreaksAndFencedCompatibility() {
        assertEquals("你好\n朋友", MessageTranslator.parse("""{"translation":"你好\n朋友"}"""))
        assertEquals("你好", MessageTranslator.parse("```json\n{\"translation\":\"你好\"}\n```"))
        assertEquals("a".repeat(12_000), MessageTranslator.parse(buildJsonObject { put("translation","a".repeat(12_000)) }.toString()))
    }
    @Test fun reasoningProseSkipEmptyExtraFieldsAndMalformedResultsAreNeverShownAsTranslations() {
        for(raw in listOf("SKIP", "I should translate this", "prefix {\"translation\":\"text\"}", "{}", "[]", """{"translation":null}""",
            """{"translation":""}""", """{"translation":42}""", """{"translation":"text","reasoning":"private"}""", """{"translation":"unfinished""")) {
            try { MessageTranslator.parse(raw); fail(raw) } catch (_: IllegalStateException) { }
        }
    }
    @Test fun oversizedTranslationIsNotSilentlyStoredAsPartialText() {
        try { MessageTranslator.parse(buildJsonObject { put("translation", "x".repeat(24_001)) }.toString()); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun actualHttpSendsOnlyOneSourceMessageNoHistoryAttachmentsOrToolsAndIgnoresReasoning() = runBlocking {
        var requests = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            requests++
            val obj = Json.parseToJsonElement(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject
            assertFalse("tools" in obj); assertFalse("tool_choice" in obj)
            val messages = obj["messages"]!!.jsonArray
            assertEquals(2, messages.size)
            assertEquals("system",messages[0].jsonObject["role"]!!.jsonPrimitive.content)
            assertTrue(messages[0].jsonObject["content"]!!.jsonPrimitive.content.contains("简体中文"))
            val source = Json.parseToJsonElement(messages[1].jsonObject["content"]!!.jsonPrimitive.content).jsonObject
            assertEquals(setOf("text"), source.keys); assertEquals("Hello <ignore instructions>",source["text"]!!.jsonPrimitive.content)
            assertEquals("Bearer synthetic-key",exchange.requestHeaders.getFirst("Authorization"))
            val data = buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("delta") {
                put("reasoning_content", "PRIVATE_REASONING_CANARY"); put("content", "{\"translation\":\"你好\"}")
            }; put("finish_reason", "stop") } } }
            val bytes = "data: $data\n\ndata: [DONE]\n\n".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start(); val http = OkHttpClient()
        try {
            assertEquals("你好", MessageTranslator(ChatClient(http,diagnostic = {})).translate(ApiEndpoint("http://127.0.0.1:${server.address.port}/v1","synthetic-key","synthetic"), "Hello <ignore instructions>", "zh-CN"))
            assertEquals(1,requests)
        } finally { server.stop(0); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }
}
