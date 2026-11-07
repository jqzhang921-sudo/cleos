package com.cleo.cleos.ai

import com.cleo.cleos.data.db.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class WorldPictureChoiceTest {
    private val ta = CompanionEntity(id = 1, name = "Public reader", persona = "ROLE", apiBaseUrl = "", apiModel = "", createdAt = 1)
    private val article = WorldArticleEntity(7, 1, "PUBLIC_TITLE_CANARY", "Public source", "PUBLIC_SUMMARY_CANARY", "hash", 1, space = "outside", originUrl = "https://zh.wikipedia.org/wiki/Test")
    private fun server(reply: (Int) -> Pair<Int, String>, test: suspend (WorldReader, ApiEndpoint, List<JsonObject>) -> Unit) = runBlocking {
        val requests = mutableListOf<JsonObject>(); val http = OkHttpClient()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { e ->
            requests += Json.parseToJsonElement(e.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject
            val (code, raw) = reply(requests.size)
            val payload = buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("delta") { put("content", raw); put("reasoning_content", "REASONING_CANARY") }; put("finish_reason", "stop") } } }
            val bytes = (if (code == 200) "data: $payload\n\ndata: [DONE]\n\n" else raw).toByteArray()
            e.responseHeaders.add("Content-Type", if (code == 200) "text/event-stream" else "application/json")
            e.sendResponseHeaders(code, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }; server.start()
        try { test(WorldReader(ChatClient(http, diagnostic = {})), ApiEndpoint("http://127.0.0.1:${server.address.port}/v1", "synthetic", "synthetic"), requests) }
        finally { server.stop(0); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }

    @Test fun selectionSendsOnlyPublicCandidatesWithoutPixelsAndUsesDedicatedTool() = server({ 200 to """{"article_id":7}""" }) { reader, endpoint, requests ->
        assertEquals(7L, reader.choosePicture(endpoint, ta, listOf(article)))
        val r=requests.single();assertTrue(r.toString().contains("PUBLIC_TITLE_CANARY"));assertTrue(r.toString().contains("PUBLIC_SUMMARY_CANARY"));assertFalse(r.toString().contains("image_url"))
        assertEquals("world_choose_picture",r["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }
    @Test fun unknownChoiceGetsOneRepairWithoutEchoingReasoning() = server({ n -> 200 to if(n==1) """{"article_id":99}""" else """{"article_id":7}""" }) { reader, endpoint, requests ->
        assertEquals(7L,reader.choosePicture(endpoint,ta,listOf(article)));assertEquals(2,requests.size);assertFalse(requests.last().toString().contains("REASONING_CANARY"));assertNull(requests.last()["tools"])
    }
    @Test fun foreignOwnerOrPrivateStudyIsRejectedBeforeAnyRequest() = server({ error("No request expected") }) { reader, endpoint, requests ->
        for(a in listOf(article.copy(companionId=2),article.copy(space="study"))) {
            try{reader.choosePicture(endpoint,ta,listOf(a));fail()}catch(_:IllegalArgumentException){}
        };assertTrue(requests.isEmpty())
    }
}
