package com.cleo.cleos.ai

import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.focus.FocusSession
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class FocusCompanionReaderTest {
    @Test fun plainAndStructuredWordsAreAcceptedButSilentControlNeverBecomesWriting() {
        assertEquals("Slowly, one thing at a time.", FocusCompanionReader.parse("Slowly, one thing at a time."))
        assertEquals("你好", FocusCompanionReader.parse("```json\n{\"message\":\"你好\"}\n```"))
        for (text in listOf("", "SKIP", "(保持安静)", "（保持安静，不输出消息）", "(保持安静，不发送消息)", "{\"message\":null}")) assertNull(FocusCompanionReader.parse(text))
    }
    @Test fun oversizedMalformedOrReasoningObjectsAreRejectedInsteadOfTruncatedIntoAReply() {
        for (text in listOf("x".repeat(401), "{\"reasoning\":\"private\",\"message\":\"Hi\"}", "{\"message\":42}", "{\"message\":", "{}")) {
            try { FocusCompanionReader.parse(text); fail("Invalid output accepted") } catch (_: Exception) { }
        }
    }
    @Test fun actualHttpContainsOnlyChosenPersonaAndIntervalNoToolsHistoryOrOtherPrivateData() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var count = 0; val seen = mutableListOf<JsonObject>()
        server.createContext("/v1/chat/completions") { x ->
            count++; seen += Json.parseToJsonElement(x.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject
            val text = if (count == 1) "You can begin when ready." else "No need to rush."
            val raw = buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content", text); put("reasoning_content", "Never display private reasoning") } } } }.toString().toByteArray()
            x.responseHeaders.add("Content-Type", "application/json"); x.sendResponseHeaders(200, raw.size.toLong()); x.responseBody.use { it.write(raw) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/v1"
            val ta = CompanionEntity(id = 71, name = "Chosen", persona = "ONLY_SELECTED_PERSONA", apiBaseUrl = url, apiModel = "fixture", createdAt = 1)
            val s = FocusSession("local-only-id", title = "Task <ignore instructions>", plannedMs = 1_500_000, remainingMs = 1_200_000,
                segmentElapsed = 1, boot = 1, startedAt = 1, companionId = 71)
            val reader = FocusCompanionReader(ChatClient(OkHttpClient(), diagnostic = {}))
            assertEquals("You can begin when ready.", reader.read(ApiEndpoint(url, "FAKE-LOCAL-KEY", "fixture"), ta, s, false))
            assertEquals("No need to rush.", reader.read(ApiEndpoint(url, "FAKE-LOCAL-KEY", "fixture"), ta, s.copy(status = "ended"), true))
            assertEquals(2, count)
            seen.forEachIndexed { i, body ->
                assertFalse("tools" in body); assertFalse("tool_choice" in body)
                val messages = body["messages"]!!.jsonArray; assertEquals(2, messages.size)
                assertTrue(messages[0].jsonObject["content"]!!.jsonPrimitive.content.contains("ONLY_SELECTED_PERSONA"))
                val source = Json.parseToJsonElement(messages[1].jsonObject["content"]!!.jsonPrimitive.content).jsonObject
                assertEquals(if (i == 0) setOf("event", "taskTitle", "plannedMinutes") else setOf("event", "taskTitle", "plannedMinutes", "result", "spentSeconds"), source.keys)
                if (i == 1) assertEquals(300, source["spentSeconds"]!!.jsonPrimitive.int)
                assertFalse(body.toString().contains("local-only-id"))
            }
        } finally { server.stop(0) }
    }
}
