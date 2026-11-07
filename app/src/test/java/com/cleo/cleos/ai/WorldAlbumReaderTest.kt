package com.cleo.cleos.ai

import com.cleo.cleos.data.db.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class WorldAlbumReaderTest {
    private val pixels = "data:image/jpeg;base64,/9j/2Q=="
    private val photo = WorldAlbumPhotoEntity(1, 10, "album-00000000-0000-0000-0000-000000000001.jpg", 400, 300, "0".repeat(64), "PRIVATE_CAPTION_CANARY", 1)
    private val ta = CompanionEntity(id = 10, name = "Album reader", persona = "PERSONA_CANARY", apiBaseUrl = "", apiModel = "", createdAt = 1)
    private val good = """{"action":"seen","note":"A small view.","question":""}"""
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
    private fun checkPhoto(r: JsonObject) {
        val parts = r["messages"]!!.jsonArray.flatMap { (it.jsonObject["content"] as? JsonArray).orEmpty() }
        assertEquals(pixels, parts.single { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertTrue(r.toString().contains("PRIVATE_CAPTION_CANARY")); assertTrue(r.toString().contains("PERSONA_CANARY"))
        assertFalse(r.toString().contains("REASONING_CANARY")); assertFalse(r.toString().contains("BAD_PROSE_CANARY"))
    }
    @Test fun sendsExactlyOnePrivatePhotoUsingDedicatedAcknowledgement() = server({ 200 to good }) { reader, endpoint, requests ->
        assertEquals("A small view.", reader.lookAlbum(endpoint, ta, photo, pixels)!!.content)
        checkPhoto(requests.single()); assertEquals("world_finish_album", requests.single()["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }
    @Test fun repairContainsSamePhotoAndDoesNotEchoReasoningOrFailedText() = server({ n -> 200 to if (n == 1) "BAD_PROSE_CANARY" else good }) { reader, endpoint, requests ->
        assertNotNull(reader.lookAlbum(endpoint, ta, photo, pixels)); assertEquals(2, requests.size); requests.forEach(::checkPhoto)
    }
    @Test fun imageRejectionNeverRetriesWithoutPhoto() = server({ 400 to """{"error":{"message":"image input is not supported"}}""" }) { reader, endpoint, requests ->
        try { reader.lookAlbum(endpoint, ta, photo, pixels); fail() } catch (_: ChatException) { }
        assertEquals(1, requests.size); checkPhoto(requests.single())
    }
    @Test fun skipDoesNotProduceAFalseSeenRecord() = server({ 200 to "SKIP" }) { reader, endpoint, requests ->
        try { reader.lookAlbum(endpoint, ta, photo, pixels); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(2, requests.size)
    }
    @Test fun staleOwnerOrMissingPixelsNeverGoToNetwork() = server({ error("No network expected") }) { reader, endpoint, requests ->
        try { reader.lookAlbum(endpoint, ta.copy(id = 20), photo, pixels); fail() } catch (_: IllegalArgumentException) { }
        try { reader.lookAlbum(endpoint, ta, photo, "https://example.invalid/image.jpg"); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(requests.isEmpty())
    }
    @Test fun choosingOnlySendsCandidateCaptionsAndCannotMarkAnyPhotoSeen() = server({ 200 to """{"photo_id":1}""" }) { reader, endpoint, requests ->
        assertEquals(1L, reader.chooseAlbum(endpoint, ta, listOf(photo)))
        val r = requests.single();assertFalse(r.toString().contains("image_url"));assertFalse(r.toString().contains(photo.file))
        assertTrue(r.toString().contains("PRIVATE_CAPTION_CANARY"))
        assertEquals("world_choose_album",r["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }
    @Test fun unavailablePhotoSelectionGetsOneBoundedRepairWithoutEchoingReasoning() = server({ n -> 200 to if(n==1) """{"photo_id":99}""" else """{"photo_id":1}""" }) { reader, endpoint, requests ->
        assertEquals(1L, reader.chooseAlbum(endpoint, ta, listOf(photo)));assertEquals(2,requests.size)
        assertFalse(requests.last().toString().contains("REASONING_CANARY"));assertNull(requests.last()["tools"])
    }
}
