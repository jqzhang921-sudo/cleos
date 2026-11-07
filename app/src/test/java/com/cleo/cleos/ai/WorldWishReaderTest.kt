package com.cleo.cleos.ai

import com.cleo.cleos.data.db.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class WorldWishReaderTest {
    @Test fun keepsLegacyChoicesAndSupportsOptionalEmptyFields() {
        assertEquals(7L, WorldReader.decision("""{"article_id":"7"}""", setOf(7), true).articleId)
        assertEquals(7L, WorldReader.decision("""{"id":7}""", setOf(7), true).articleId)
        assertNull(WorldReader.decision("""{"article_id":null}""", emptySet(), true).wish)
        assertEquals(7L, WorldReader.decision("""{"article_id":7,"wanted_title":"","wanted_author":"","wanted_reason":""}""", setOf(7), true).articleId)
    }
    @Test fun acceptsOnlyCompleteUnambiguousWishAndNoToolOrMessageExtras() {
        val valid = """{"article_id":null,"wanted_title":"風之書","wanted_author":"","wanted_reason":"想讀風的故事"}"""
        assertEquals("風之書", WorldReader.decision(valid, emptySet(), true).wish!!.title)
        val bad = listOf(valid.replace("null", "7"), valid.replace("風之書", ""), valid.replace("想讀風的故事", ""),
            valid.replace("\"wanted_author\":\"\",", ""), valid.replace("\"wanted_author\":\"\"", "\"wanted_author\":3"),
            valid.replace("風之書", "x".repeat(121)), valid.replace("}", ",\"send_message\":\"Hi\"}"), "thought $valid")
        for (raw in bad) try { WorldReader.decision(raw, setOf(7), true); fail("Malformed accepted: $raw") } catch (_: IllegalArgumentException) { }
        try { WorldReader.decision(valid, emptySet(), false); fail("Wish accepted during cooldown") } catch (_: IllegalArgumentException) { }
    }
    @Test fun realStreamOnEmptyShelfReturnsOnePublicWishAndNeverEchoesReasoningOrOrdinaryOutput() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()
        server.createContext("/v1/chat/completions") { e ->
            requests += e.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val args = """{"article_id":null,"wanted_title":"Wind","wanted_author":"Synthetic","wanted_reason":"I want to read about wind."}"""
            val payload = buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("delta") {
                put("reasoning_content", "PRIVATE_THOUGHT_CANARY"); put("content", "ORDINARY_CANARY")
                putJsonArray("tool_calls") { addJsonObject { put("index", 0); put("id", "wish-1"); put("type", "function")
                    putJsonObject("function") { put("name", "world_choose_article"); put("arguments", args) }
                } }
            }; put("finish_reason", "tool_calls") } } }
            val bytes = "data: $payload\n\ndata: [DONE]\n\n".toByteArray()
            e.responseHeaders.add("Content-Type", "text/event-stream"); e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start(); val http = OkHttpClient()
        try {
            val endpoint = ApiEndpoint("http://127.0.0.1:${server.address.port}/v1", "synthetic-key", "synthetic")
            val reader = WorldReader(ChatClient(http, diagnostic = {}))
            val ta = CompanionEntity(id = 10, name = "Synthetic", createdAt = 1, apiBaseUrl = endpoint.baseUrl, apiModel = endpoint.model)
            val choice = reader.decide(endpoint, ta, emptyList(), emptyList(), emptyList(), emptyList(), true)
            assertEquals("Wind", choice.wish!!.title); assertNull(choice.articleId); assertEquals(1, requests.size)
            val request = requests.single()
            assertFalse(request.contains("PRIVATE_THOUGHT_CANARY")); assertFalse(request.contains("ORDINARY_CANARY"))
            assertEquals("world_choose_article", Json.parseToJsonElement(request).jsonObject["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        } finally { server.stop(0); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }
}
