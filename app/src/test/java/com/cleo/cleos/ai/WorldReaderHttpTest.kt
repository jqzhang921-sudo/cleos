package com.cleo.cleos.ai

import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.db.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class WorldReaderHttpTest {
    private val source = "This is a synthetic article about wind. It is not a private chat."
    private val article = WorldArticleEntity(7, 10, "Wind", "synthetic source", source, WorldReading.fingerprint(source), 0)
    @Test fun twoRealHttpRoundsOnlyExposeCatalogueThenActualSourceAndIgnoreReasoning() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject
            requests += request
            val choosing = requests.size == 1
            val args = if (choosing) """{"article_id":7}""" else """{"action":"read","note":"A small thought about wind.","question":"Where does it go?","quote":"wind"}"""
            val payload = buildJsonObject { putJsonArray("choices") { addJsonObject {
                putJsonObject("delta") {
                    put("reasoning_content", "PRIVATE_REASONING_CANARY")
                    put("content", "ORDINARY_OUTPUT_CANARY")
                    putJsonArray("tool_calls") { addJsonObject {
                        put("index", 0); put("id", "world-${requests.size}"); put("type", "function")
                        putJsonObject("function") { put("name", if (choosing) "world_choose_article" else "world_finish_reading"); put("arguments", args) }
                    } }
                }
                put("finish_reason", "tool_calls")
            } } }
            val bytes = "data: $payload\n\ndata: [DONE]\n\n".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val http = OkHttpClient()
        try {
            val reader = WorldReader(ChatClient(http, diagnostic = {}))
            val endpoint = ApiEndpoint("http://127.0.0.1:${server.address.port}/v1", "synthetic-key", "synthetic-model")
            val ta = CompanionEntity(id = 10, name = "Synthetic reader", apiBaseUrl = endpoint.baseUrl, apiModel = endpoint.model, createdAt = 0)
            val context = buildJsonObject { put("prior_note", "PRIOR_NOTE_CANARY"); put("wanted_reason", "WANTED_REASON_CANARY"); put("reading_preference", "PREFERENCE_CANARY") }
            assertEquals(7L, reader.decide(endpoint, ta, listOf(article), emptyList(), emptyList(), listOf(article), false, studyContext = context).articleId)
            val note = reader.read(endpoint, ta, article, source.length, studyContext = context)!!
            assertEquals("A small thought about wind.", note.content)
            assertEquals(source.indexOf("wind"), note.quoteOffset)
            assertEquals(2, requests.size)
            assertFalse(requests[0].toString().contains(source))
            assertTrue(requests[1].toString().contains(source))
            for (request in requests) {
                for (material in listOf("PRIOR_NOTE_CANARY", "WANTED_REASON_CANARY", "PREFERENCE_CANARY")) assertTrue(request.toString().contains(material))
                val tool = request["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
                assertTrue(tool.startsWith("world_"))
                assertFalse(request.toString().contains("PRIVATE_REASONING_CANARY"))
                assertFalse(request.toString().contains("ORDINARY_OUTPUT_CANARY"))
            }
        } finally { server.stop(0); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }
    @Test fun ordinaryTextAndReasoningWithoutAcknowledgementCannotAdvanceReading() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"PRIVATE_THOUGHT\",\"content\":\"I read it\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start(); val http = OkHttpClient()
        try {
            val reader = WorldReader(ChatClient(http, diagnostic = {}))
            try {
                reader.read(ApiEndpoint("http://127.0.0.1:${server.address.port}/v1", "synthetic", "synthetic"), CompanionEntity(id = 10, apiBaseUrl = "http://127.0.0.1", apiModel = "synthetic", createdAt = 0), article, source.length)
                fail("Ordinary prose became a reading result")
            } catch (_: IllegalArgumentException) { }
        } finally { server.stop(0); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }
}
