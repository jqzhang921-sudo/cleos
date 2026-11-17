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
    @Test fun manualRevisitOnlyCarriesBoundedSameOwnerSamePixelHistory() = server({ 200 to good }) { reader, endpoint, requests ->
        val previous = (1..4).map { WorldAlbumLookEntity(it.toLong(), ta.id, photo.id, photo.sha256, "run-$it",
            "SAME_PHOTO_$it " + "n".repeat(1000), "q".repeat(800), it.toLong()) }
        val excluded = listOf(previous.last().copy(companionId = 99, content = "OTHER_OWNER_CANARY"),
            previous.last().copy(photoSha256 = "9".repeat(64), content = "OTHER_PIXELS_CANARY"))
        assertNotNull(reader.lookAlbum(endpoint, ta, photo, pixels, previous + excluded))
        val request = requests.single(); checkPhoto(request)
        val text = request["messages"]!!.jsonArray.flatMap { (it.jsonObject["content"] as? JsonArray).orEmpty() }
            .single { it.jsonObject["type"]!!.jsonPrimitive.content == "text" && it.jsonObject["text"]!!.jsonPrimitive.content.contains("previous_views") }
            .jsonObject["text"]!!.jsonPrimitive.content
        val data = Json.parseToJsonElement(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)).jsonObject
        val history = data["previous_views"]!!.jsonArray
        assertEquals(3, history.size)
        assertTrue(history.first().jsonObject["note"]!!.jsonPrimitive.content.startsWith("SAME_PHOTO_4"))
        assertTrue(history.all { it.jsonObject["note"]!!.jsonPrimitive.content.length <= 600 && it.jsonObject["question"]!!.jsonPrimitive.content.length <= 200 })
        assertFalse(text.contains("SAME_PHOTO_1")); assertFalse(text.contains("OTHER_OWNER_CANARY")); assertFalse(text.contains("OTHER_PIXELS_CANARY"))
        assertFalse(text.contains(photo.file)); assertFalse(text.contains(photo.sha256))
    }
    @Test fun revisitRepairRetainsHistoryAndExactlyOneImage() = server({ n -> 200 to if(n==1) "BAD_PROSE_CANARY" else good }) { reader, endpoint, requests ->
        val look = WorldAlbumLookEntity(1, ta.id, photo.id, photo.sha256, "old", "PREVIOUS_VIEW_CANARY", "", 1)
        assertNotNull(reader.lookAlbum(endpoint, ta, photo, pixels, listOf(look)))
        assertEquals(2, requests.size)
        requests.forEach { checkPhoto(it); assertTrue(it.toString().contains("PREVIOUS_VIEW_CANARY")) }
    }
}
