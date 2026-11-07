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

class WorldReaderCompatibilityTest {
    private val diagnostics = mutableListOf<String>()
    private val text = "A real synthetic passage about wind."
    private val article = WorldArticleEntity(7, 10, "Wind", "synthetic", text, WorldReading.fingerprint(text), 0)
    private val good = """{"action":"read","note":"A public note.","question":"","quote":"wind"}"""
    private fun response(content: String, reasoning: String = "PRIVATE_CANARY") = "data: " + buildJsonObject {
        putJsonArray("choices") { addJsonObject { putJsonObject("delta") { put("content", content); put("reasoning_content", reasoning) }; put("finish_reason", "stop") } }
    } + "\n\ndata: [DONE]\n\n"
    private fun testServer(reply: (Int, JsonObject) -> Pair<Int, String>, test: suspend (WorldReader, ApiEndpoint, MutableList<JsonObject>) -> Unit) = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject
            requests += request
            val (status, body) = reply(requests.size, request); val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", if (status == 200) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start(); val http = OkHttpClient()
        try { test(WorldReader(ChatClient(http, diagnostic = {}), diagnostic = { diagnostics += it }), ApiEndpoint("http://127.0.0.1:${server.address.port}/v1", "synthetic", "synthetic"), requests) }
        finally { server.stop(0); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }
    private fun ta() = CompanionEntity(id = 10, name = "Synthetic reader", apiBaseUrl = "http://127.0.0.1", apiModel = "synthetic", createdAt = 0)
    @Test fun proseGetsOneStructuredRetryWithoutEchoingProseOrThoughts() = testServer(
        { n, _ -> 200 to response(if (n == 1) "FAILED_PROSE_CANARY" else good) },
    ) { reader, endpoint, requests ->
        assertEquals("A public note.", reader.read(endpoint, ta(), article, text.length)!!.content)
        assertEquals(2, requests.size); assertNotNull(requests[0]["tools"]); assertNull(requests[1]["tools"])
        assertTrue(requests[1].toString().contains(text)); assertFalse(requests[1].toString().contains("PRIVATE_CANARY")); assertFalse(requests[1].toString().contains("FAILED_PROSE_CANARY"))
    }
    @Test fun rejectedToolSchemaCanUseStructuredTextWithoutAnotherToolRequest() = testServer(
        { n, _ -> if (n == 1) 400 to """{"error":{"message":"tools schema not supported","type":"invalid_request_error"}}""" else 200 to response(good) },
    ) { reader, endpoint, requests ->
        assertNotNull(reader.read(endpoint, ta(), article, text.length)); assertEquals(2, requests.size); assertNull(requests[1]["tools"])
    }
    @Test fun completeJsonAlreadyReturnedByTheModelNeedsNoExtraRequest() = testServer(
        { _, _ -> 200 to response("```json\n$good\n```") },
    ) { reader, endpoint, requests ->
        assertEquals(text.indexOf("wind"), reader.read(endpoint, ta(), article, text.length)!!.quoteOffset); assertEquals(1, requests.size)
    }
    @Test fun authRateLimitAndServerFailureDoNotTriggerCompatibilityRetriesOrLeakProviderText() {
        for (code in listOf(401, 402, 403, 429, 503)) testServer({ _, _ -> code to """{"error":{"message":"does not support tool calling SECRET_PROVIDER_BODY"}}""" }) { reader, endpoint, requests ->
            try { reader.read(endpoint, ta(), article, text.length); fail() }
            catch (e: ChatException) { assertFalse(WorldFailure.of(e).message.contains("SECRET_PROVIDER_BODY")); assertEquals(code, e.status) }
            assertEquals(1, requests.size)
        }
    }
    @Test fun structuredFallbackStillRejectsUnseenQuotesAndNeverParsesProseFragments() = testServer(
        { _, _ -> 200 to response(good.replace("wind", "UNSEEN_QUOTE")) },
    ) { reader, endpoint, requests ->
        try { reader.read(endpoint, ta(), article, text.length); fail() }
        catch (e: WorldReplyException) { assertEquals("invalid_note", e.code) }
        assertEquals(1, requests.size)
        assertNull(WorldReader.jsonObjectText("I thought about this: $good")); assertNull(WorldReader.jsonObjectText("<think>$good</think>")); assertNull(WorldReader.jsonObjectText("[$good]"))
    }
    private fun toolResponse(arguments: String) = "data: " + buildJsonObject {
        putJsonArray("choices") { addJsonObject { putJsonObject("delta") {
            put("reasoning_content", "PRIVATE_CANARY")
            putJsonArray("tool_calls") { addJsonObject {
                put("index", 0); put("id", "choose"); put("type", "function")
                putJsonObject("function") { put("name", "world_choose_article"); put("arguments", arguments) }
            } }
        }; put("finish_reason", "tool_calls") } }
    } + "\n\ndata: [DONE]\n\n"
    @Test fun invalidSelectionFieldsGetOneRepairWithMatchingCatalogueAndNoOutputEcho() = testServer(
        { n, _ -> 200 to if (n == 1) toolResponse("""{"title":"Wind","reasoning":"FAILED_SELECTION_CANARY"}""") else response("""{"article_id":7}""") },
    ) { reader, endpoint, requests ->
        assertEquals(7L, reader.choose(endpoint, ta(), listOf(article), emptyList()))
        assertEquals(2, requests.size)
        val catalogue = Json.parseToJsonElement(requests[0]["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content.split('\n')[1]).jsonArray
        assertEquals(JsonPrimitive(7), catalogue.single().jsonObject["article_id"]); assertNull(catalogue.single().jsonObject["id"])
        val schema = requests[0]["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive(7), JsonNull)), schema["properties"]!!.jsonObject["article_id"]!!.jsonObject["enum"])
        assertNull(requests[1]["tools"])
        assertFalse(requests[1].toString().contains("FAILED_SELECTION_CANARY")); assertFalse(requests[1].toString().contains("PRIVATE_CANARY"))
        assertEquals(listOf("event=world_reading_retry route=world stage=choose reason=choice_fields"), diagnostics)
    }
    @Test fun canonicalStringAndLegacyCatalogueIdAreAcceptedWithoutExtraRequests() {
        for (key in listOf("article_id", "id")) testServer({ _, _ -> 200 to toolResponse("""{"$key":"7"}""") }) { reader, endpoint, requests ->
            assertEquals(7L, reader.choose(endpoint, ta(), listOf(article), emptyList())); assertEquals(1, requests.size)
        }
    }
    @Test fun invalidSelectionMayBeRepairedToSilenceWithoutSelectingTheOnlyBook() = testServer(
        { n, _ -> 200 to response(if (n == 1) """{"article_id":999}""" else """{"article_id":null}""") },
    ) { reader, endpoint, requests ->
        assertNull(reader.choose(endpoint, ta(), listOf(article), emptyList())); assertEquals(2, requests.size)
        assertTrue(diagnostics.single().endsWith("reason=choice_shelf"))
    }
    @Test fun secondInvalidSelectionStopsWithSpecificSafeReasonAndNeverSendsSource() {
        for ((raw, code) in listOf("{}" to "choice_fields", """{"article_id":"Wind"}""" to "choice_type", """{"article_id":999}""" to "choice_shelf",
            """{"article_id":7""" to "choice_json")) testServer({ _, _ -> 200 to toolResponse(raw) }) { reader, endpoint, requests ->
            try { reader.choose(endpoint, ta(), listOf(article), emptyList()); fail() }
            catch (e: WorldReplyException) { assertEquals(code, e.code); assertFalse(e.message!!.contains("PRIVATE_CANARY")) }
            assertEquals(2, requests.size); assertTrue(requests.none { it.toString().contains(text) })
        }
    }
    @Test fun schemaRejectionAndInvalidJsonStillHaveOnlyOneCompatibilityRetry() = testServer(
        { n, _ -> if (n == 1) 400 to """{"error":{"message":"unsupported tools"}}""" else 200 to response("{}") },
    ) { reader, endpoint, requests ->
        try { reader.choose(endpoint, ta(), listOf(article), emptyList()); fail() }
        catch (e: WorldReplyException) { assertEquals("choice_fields", e.code) }
        assertEquals(2, requests.size)
    }
    @Test fun onlyCompleteJsonFencesAreCompatibleIncludingWindowsLineEndings() {
        for (fence in listOf("```JSON\r\n$good\r\n```", "```\n$good\n```")) assertEquals(good, WorldReader.jsonObjectText(fence))
        for (raw in listOf("Before\n```json\n$good\n```", "```json\n$good\n```\nAfter", "<think>thought</think>\n$good")) assertNull(WorldReader.jsonObjectText(raw))
    }
}
