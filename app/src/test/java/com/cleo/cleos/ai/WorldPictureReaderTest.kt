package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import com.cleo.cleos.ui.world.WorldJournalMotion
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class WorldPictureReaderTest {
    private val pixels = "data:image/jpeg;base64,/9j/2Q=="
    private val picture = WorldPictureEntity(1, 10, "f", "world-fixture.jpg", 400, 300, "Synthetic sea", "Synthetic artist", "Synthetic credit", "Synthetic caption", "CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0/", "https://commons.wikimedia.org/wiki/File:Sea.jpg", "https://upload.wikimedia.org/wikipedia/commons/a/Sea.jpg", 1, "0".repeat(64))
    private val ta = CompanionEntity(id = 10, name = "Synthetic reader", persona = "SYNTHETIC_PERSONA", apiBaseUrl = "http://127.0.0.1", apiModel = "synthetic", createdAt = 1)
    private val good = """{"action":"seen","note":"A synthetic view.","question":"How does wind shape waves?"}"""
    private fun response(text: String) = "data: " + buildJsonObject {
        putJsonArray("choices") { addJsonObject { putJsonObject("delta") { put("content", text); put("reasoning_content", "PRIVATE_REASONING_CANARY") }; put("finish_reason", "stop") } }
    } + "\n\ndata: [DONE]\n\n"
    private fun server(reply: (Int) -> Pair<Int, String>, test: suspend (WorldReader, ApiEndpoint, List<JsonObject>) -> Unit) = runBlocking {
        val requests = mutableListOf<JsonObject>(); val http = OkHttpClient()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            requests += Json.parseToJsonElement(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject
            val (code, text) = reply(requests.size); val bytes = text.toByteArray()
            exchange.responseHeaders.add("Content-Type", if (code == 200) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { test(WorldReader(ChatClient(http, diagnostic = {})), ApiEndpoint("http://127.0.0.1:${server.address.port}/v1", "synthetic", "synthetic"), requests) }
        finally { server.stop(0); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }
    private fun assertPixels(request: JsonObject) {
        val messages = request["messages"]!!.jsonArray
        val images = messages.flatMap { m -> (m.jsonObject["content"] as? JsonArray).orEmpty() }.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }
        assertEquals(pixels, images.single().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertTrue(request.toString().contains("SYNTHETIC_PERSONA")); assertTrue(request.toString().contains("Synthetic caption"))
        assertFalse(request.toString().contains("PRIVATE_REASONING_CANARY"))
    }
    @Test fun realOutboundImagePartAndOnlyPublicAcknowledgement() = server({ 200 to response(good) }) { reader, endpoint, requests ->
        val result = reader.look(endpoint, ta, picture, pixels)!!
        assertEquals("A synthetic view.", result.content); assertEquals(1, requests.size); assertPixels(requests.single())
        assertEquals("world_finish_picture", requests.single()["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }
    @Test fun structuredRepairStillCarriesPixelsAndNeverFailedProseOrReasoning() = server({ n -> 200 to response(if (n == 1) "FAILED_PROSE_CANARY" else good) }) { reader, endpoint, requests ->
        assertNotNull(reader.look(endpoint, ta, picture, pixels)); assertEquals(2, requests.size)
        requests.forEach(::assertPixels); assertNull(requests[1]["tools"]); assertFalse(requests[1].toString().contains("FAILED_PROSE_CANARY"))
    }
    @Test fun rejectedToolsRetryKeepsImage() = server({ n -> if (n == 1) 400 to """{"error":{"message":"tools schema not supported"}}""" else 200 to response(good) }) { reader, endpoint, requests ->
        assertNotNull(reader.look(endpoint, ta, picture, pixels)); assertEquals(2, requests.size); requests.forEach(::assertPixels)
    }
    @Test fun rejectedImagesNeverFallBackToTextOnly() = server({ 400 to """{"error":{"message":"image input is not supported by this model"}}""" }) { reader, endpoint, requests ->
        try { reader.look(endpoint, ta, picture, pixels); fail() } catch (_: ChatException) { }
        assertEquals(1, requests.size); assertPixels(requests.single())
    }
    @Test fun proseSkipTruncationAndExtraFieldsNeverBecomeSeen() {
        for (bad in listOf("SKIP", "I saw the sea", good.dropLast(1), good.dropLast(1) + ",\"reasoning\":\"internal\"}", """{"action":"seen","note":null,"question":""}"""))
            server({ 200 to response(bad) }) { reader, endpoint, requests ->
                try { reader.look(endpoint, ta, picture, pixels); fail(bad) } catch (_: IllegalArgumentException) { }
                assertEquals(2, requests.size); requests.forEach(::assertPixels)
            }
    }
    @Test fun pauseAndSilentSeenHaveDifferentMeaning() {
        assertNull(WorldReader.pictureResult("""{"action":"pause","note":"","question":""}"""))
        val seen = WorldReader.pictureResult("""{"action":"seen","note":"","question":""}""")!!
        assertEquals("", seen.content); assertEquals("", seen.question)
        try { WorldReader.pictureResult("""{"action":"pause","note":"inconsistent","question":""}"""); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun missingPixelsWrongOwnerAndNonImagePayloadNeverReachNetwork() = server({ error("No request expected") }) { reader, endpoint, requests ->
        for (value in listOf("world-local.jpg", "https://example.invalid/photo.jpg", "data:image/jpeg;base64,bm90anBlZw==", "data:image/jpeg;base64,@@@")) {
            try { reader.look(endpoint, ta, picture, value); fail() } catch (_: IllegalArgumentException) { }
        }
        try { reader.look(endpoint, ta.copy(id = 20), picture, pixels); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(requests.isEmpty())
    }
    @Test fun backupBindsLookToOwnerAndActualPixelVersionWithoutPretendingSourceWasRead() {
        val look = WorldPictureLookEntity(1, 10, 1, picture.sha256, "run", "", "", 2)
        WorldPictureData.validateLooks(listOf(picture), listOf(look), emptyList())
        for (bad in listOf(look.copy(id = 0), look.copy(companionId = 20), look.copy(articleId = 2), look.copy(pictureSha256 = "bad"), look.copy(createdAt = 0), look.copy(content = "a".repeat(4001)))) {
            try { WorldPictureData.validateLooks(listOf(picture), listOf(bad), emptyList()); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(look, Json.decodeFromString<WorldPictureLookEntity>(Json.encodeToString(look)))
        assertTrue(BackupFile.VERSION >= 24)
    }
    @Test fun combinedNotebookCapAndCrossTypeDuplicateRunAreRejected() {
        val look = WorldPictureLookEntity(1, 10, 1, picture.sha256, "run", "note", "", 2)
        val note = WorldNoteEntity(1, 10, 1, "run", "text", "", "", null, 0, 1, 1)
        try { WorldPictureData.validateLooks(listOf(picture), listOf(look), listOf(note)); fail() } catch (_: IllegalArgumentException) { }
        val full = (1..500).map { look.copy(id = it.toLong(), runId = "r$it") }
        WorldPictureData.validateLooks(listOf(picture), full, emptyList())
        try { WorldPictureData.validateLooks(listOf(picture), full, listOf(note)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun journalMotionIsSmallFiniteAndExactlyOffWhenDisabled() {
        assertEquals(.015f, WorldJournalMotion.at(2f, true), .00001f)
        assertEquals(-.015f, WorldJournalMotion.at(-3f, true), .00001f)
        for (offset in listOf(-4f, 0f, 4f, Float.NaN, Float.POSITIVE_INFINITY)) assertEquals(0f, WorldJournalMotion.at(offset, false), 0f)
        assertEquals(0f, WorldJournalMotion.at(Float.NaN, true), 0f)
    }
}
