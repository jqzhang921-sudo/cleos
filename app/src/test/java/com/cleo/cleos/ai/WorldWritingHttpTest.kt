package com.cleo.cleos.ai

import com.cleo.cleos.data.db.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class WorldWritingHttpTest {
    private fun withServer(reply: String?, run: suspend (WorldReader, ApiEndpoint, MutableList<JsonObject>) -> Unit) = runBlocking {
        val requests = mutableListOf<JsonObject>(); val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            requests += Json.parseToJsonElement(ex.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject
            val delta = if (reply == null) buildJsonObject { put("reasoning_content", "REASONING_ONLY"); put("content", "ORDINARY_PROSE_ONLY") }
            else buildJsonObject { put("reasoning_content", "REASONING_ONLY"); put("content", "ORDINARY_PROSE_ONLY"); putJsonArray("tool_calls") { addJsonObject {
                put("index", 0); put("id", "write"); put("type", "function"); putJsonObject("function") { put("name", "world_write_text"); put("arguments", reply) }
            } } }
            val payload = buildJsonObject { putJsonArray("choices") { addJsonObject { put("delta", delta); put("finish_reason", "tool_calls") } } }
            val bytes = "data: $payload\n\ndata: [DONE]\n\n".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream"); ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start(); val http = OkHttpClient()
        try { run(WorldReader(ChatClient(http, diagnostic = {})), ApiEndpoint("http://127.0.0.1:${server.address.port}/v1", "synthetic", "synthetic"), requests) }
        finally { server.stop(0); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }
    private val ta = CompanionEntity(id = 7, name = "Writer", persona = "ROLE_ONLY", createdAt = 1, apiBaseUrl = "https://synthetic.invalid/v1", apiModel = "synthetic")
    @Test fun onlyTwoOwnedBoundedCreativeTextsReachTheModelAndReasoningNeverBecomesTheResult() = withServer("""{"action":"write","kind":"story","title":"Boat","content":"A small boat."}""") { reader, endpoint, requests ->
        val first = WorldWritingEntity(1, 7, "r1", "note", "First", "FIRST_CONTEXT" + "x".repeat(700), 10)
        val result = reader.write(endpoint, ta, listOf(first.copy(companionId = 8, content = "OTHER_OWNER"), first, first.copy(id = 2, content = "SECOND_CONTEXT"), first.copy(id = 3, content = "OLD_CONTEXT")))!!
        assertEquals("A small boat.", result.content); assertEquals("story", result.kind); assertEquals(1, requests.size)
        val sent = requests.single().toString()
        assertTrue(sent.contains("FIRST_CONTEXT")); assertTrue(sent.contains("SECOND_CONTEXT")); assertFalse(sent.contains("OTHER_OWNER")); assertFalse(sent.contains("OLD_CONTEXT")); assertFalse(sent.contains("x".repeat(600)))
        assertFalse(sent.contains("REASONING_ONLY")); assertFalse(sent.contains("ORDINARY_PROSE_ONLY"))
        assertEquals("world_write_text", requests.single()["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }
    @Test fun canPauseWithoutProducingAFakeEntry() = withServer("""{"action":"pause","kind":"","title":"","content":""}""") { reader, endpoint, _ -> assertNull(reader.write(endpoint, ta, emptyList())) }
    @Test fun ordinaryProseAndThoughtsWithoutAcknowledgementFailAfterOneBoundedRepair() = withServer(null) { reader, endpoint, requests ->
        try { reader.write(endpoint, ta, emptyList()); fail("Unconfirmed prose was saved") } catch (_: IllegalArgumentException) { }
        assertEquals(2, requests.size)
    }
}
