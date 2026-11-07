package com.cleo.cleos.ai

import com.cleo.cleos.data.db.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class WorldTopicReaderHttpTest {
    @Test fun topicSelectionHasOneBoundedRepairAndNeverEchoesFailedReasoning() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            requests += Json.parseToJsonElement(ex.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject
            val payload = buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("delta") {
                put("reasoning_content", "PRIVATE_CANARY")
                put("content", if (requests.size == 1) "Ordinary prose is not a topic" else """{"query":"潮汐"}""")
            }; put("finish_reason", "stop") } } }
            val bytes = "data: $payload\n\ndata: [DONE]\n\n".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream"); ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start(); val http = OkHttpClient()
        try {
            val endpoint = ApiEndpoint("http://127.0.0.1:${server.address.port}/v1", "synthetic", "synthetic")
            val reader = WorldReader(ChatClient(http, diagnostic = {}))
            val ta = CompanionEntity(id = 1, name = "Fixture", persona = "喜爱自然", apiBaseUrl = endpoint.baseUrl, apiModel = endpoint.model, createdAt = 0)
            val note = WorldNoteEntity(id = 1, companionId = 1, articleId = 1, runId = "fixture", content = "公开笔记", question = "海边为什么涨落？", quote = "", quoteStart = null, fromOffset = 0, toOffset = 1, createdAt = 0)
            assertEquals("潮汐", reader.topic(endpoint, ta, listOf(note), listOf("京都")))
            assertEquals(2, requests.size)
            assertEquals("world_choose_topic", requests.first()["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            for (r in requests) {
                assertTrue(r.toString().contains("海边为什么涨落")); assertTrue(r.toString().contains("京都"))
                assertFalse(r.toString().contains("PRIVATE_CANARY")); assertFalse(r.toString().contains("Ordinary prose"))
                assertFalse(r.toString().contains("world_send"))
            }
            assertTrue(requests.last()["tools"] == null || requests.last()["tools"]!!.jsonArray.isEmpty())
        } finally { server.stop(0); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }
}
