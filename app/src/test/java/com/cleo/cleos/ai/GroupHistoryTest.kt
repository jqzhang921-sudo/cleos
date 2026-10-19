package com.cleo.cleos.ai

import com.cleo.cleos.data.db.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

/** Fixtures are verified against the actual Room query by tools/check_group_history.py. */
class GroupHistoryTest {
    private val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/group-history.json")).readText())
        .jsonObject.getValue("cases").jsonObject
    private fun rows(name: String) = Json.decodeFromJsonElement<List<GroupMessageEntity>>(cases.getValue(name).jsonObject.getValue("history"))
    private val group = ChatGroupEntity(1, "synthetic group", 0, 0)
    private val members = listOf(10L, 20L, 30L, 40L).mapIndexed { index, id -> GroupMemberEntity(1, id, index, "same-name") }
    private fun actor(id: Long) = CompanionEntity(id = id, name = "same-name", apiBaseUrl = "https://fixture-$id.invalid/v1", apiModel = "fixture-model-$id", createdAt = 0)
    private fun context(name: String, id: Long, call: Boolean = false): List<ApiMessage> {
        val now = ZonedDateTime.parse("2026-10-17T12:00:00+08:00")
        return if (call) GroupCallRules.request(group, members, listOf(10, 20), actor(id), rows(name), "fixture-user", "", now)
            else GroupChatRules.request(group, members, actor(id), rows(name), "fixture-user", emptyList(), now)
    }
    private suspend fun wire(name: String, id: Long, call: Boolean = false): JsonObject {
        var captured: JsonObject? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("fixture-$id.invalid", request.url.host)
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            captured = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        try {
            val ta = actor(id)
            assertEquals(listOf(ChatEvent.Delta("OK")), ChatClient(http, diagnostic = {})
                .stream(ApiEndpoint(ta.apiBaseUrl, "fixture-key", ta.apiModel), context(name, id, call)).toList())
            return requireNotNull(captured).also { assertEquals("fixture-model-$id", it.getValue("model").jsonPrimitive.content) }
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }
    private fun words(body: JsonObject) = body.getValue("messages").jsonArray.drop(1)
        .joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content.orEmpty() }
    private fun peerWords(body: JsonObject) = body.getValue("messages").jsonArray
        .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        .joinToString("\n") { it.jsonObject.getValue("content").jsonPrimitive.content }

    @Test fun earlierRetryReadsAlreadyCompletedLaterPeerButNeverItsOwnStaleReply() = runBlocking {
        assertEquals(listOf(1L, 3L), rows("retry_first").map { it.id })
        val body = wire("retry_first", 10)
        assertTrue(peerWords(body).contains("CTX_B")); assertTrue(peerWords(body).contains("成员 20"))
        assertTrue(words(body).contains("CTX_ANCHOR")); assertFalse(words(body).contains("OLD_OWN_OUTPUT"))
        val middle = wire("retry_middle", 20)
        assertTrue(peerWords(middle).contains("CTX_A")); assertTrue(peerWords(middle).contains("CTX_C"))
    }
    @Test fun normalOrderAndBothModelsNextRoundStillReceiveDistinctAuthors() = runBlocking {
        assertFalse(words(wire("normal_first", 10)).contains("CTX_B"))
        assertTrue(peerWords(wire("normal_second", 20)).contains("CTX_A"))
        for (id in listOf(10L, 20L)) {
            val body = wire("next_turn", id)
            for (token in listOf("CTX_ANCHOR", "CTX_A", "CTX_B", "CTX_NEXT")) assertTrue(token, words(body).contains(token))
            assertTrue(peerWords(body).contains(if (id == 10L) "CTX_B" else "CTX_A"))
        }
    }
    @Test fun retryingFirstSlotDoesNotRequestCompletedPeerAgainOrInviteMoreMembers() = runBlocking {
        val completed = rows("retry_first").last()
        val failed = completed.copy(id = 2, speakerId = 10, status = "failed", content = "OLD_OWN_OUTPUT")
        val requested = mutableListOf<Long?>()
        GroupInterjectionRound.run(listOf(failed, completed), 2, { true }, { slot, mayInvite ->
            requested += slot.speakerId
            assertFalse(mayInvite)
            assertTrue(peerWords(wire("retry_first", requireNotNull(slot.speakerId))).contains("CTX_B"))
        }, { error("a retry cannot append more members") })
        assertEquals(listOf(10L), requested)
    }
    @Test fun completedVoiceSurvivesLaterFailureCancellationAndProcessRecovery() = runBlocking {
        for (name in listOf("failed_saved_voice", "cancelled_saved_voice", "interrupted_saved_voice", "retry_later_voice")) {
            assertEquals(2, rows(name).size)
            val body = wire(name, if (name == "retry_later_voice") 10 else 20)
            assertTrue(name, peerWords(body).contains("CTX_VOICE"))
            assertTrue(peerWords(body).contains(if (name == "retry_later_voice") "成员 20" else "成员 10"))
            assertTrue(words(body).contains("没有提供原始声音"))
            assertFalse(words(body).contains("UNFINISHED_SECRET"))
            assertFalse(words(body).contains("voice_group_reply_"))
        }
    }
    @Test fun forgedAudioNeverPublishesItsBodyOrToolDetailsAsHistory() = runBlocking {
        for (name in listOf("invalid_audio", "foreign_audio", "mismatched_voice_words")) {
            val body = wire(name, 20)
            assertTrue(words(body).contains("CTX_ANCHOR"))
            for (token in listOf("CTX_VOICE", "UNFINISHED_SECRET", "UNCOMMITTED_BODY", "voice_group_reply_"))
                assertFalse("$name: $token", words(body).contains(token))
        }
    }
    @Test fun futureTurnsOtherGroupsAndUnpublishedRowsStayOut() = runBlocking {
        for (name in listOf("future_turn_excluded", "other_group_excluded", "failed_partial", "pending_partial", "running_media")) {
            val body = wire(name, 10)
            assertTrue(words(body).contains("CTX_ANCHOR"))
            for (token in listOf("FUTURE_USER", "FUTURE_TA", "OTHER_GROUP", "CTX_VOICE", "UNFINISHED_SECRET"))
                assertFalse("$name: $token", words(body).contains(token))
        }
    }
    @Test fun promptAlsoRejectsRunningMediaWhenPassedOutsideTheDao() {
        val voice = rows("failed_saved_voice").last()
        for (status in listOf("pending", "running", "unknown")) {
            val direct = GroupChatRules.request(group, members, actor(20),
                listOf(rows("failed_saved_voice").first(), voice.copy(status = status)),
                "fixture-user", emptyList(), ZonedDateTime.now()).drop(1).joinToString { it.content }
            assertTrue(direct.contains("CTX_ANCHOR"))
            assertFalse(direct.contains("CTX_VOICE")); assertFalse(direct.contains("UNFINISHED_SECRET"))
        }
    }
    @Test fun minimumWindowKeepsAnchorAndCompletedPeersWithoutUnboundedHistory() = runBlocking {
        val all = words(wire("minimum_window", 40))
        for (token in listOf("CTX_ANCHOR", "CTX_A", "CTX_B", "CTX_C")) assertTrue(token, all.contains(token))
        assertEquals(listOf(17L, 18L, 19L, 20L, 22L), rows("bounded_retry").map { it.id })
        val bounded = words(wire("bounded_retry", 10))
        assertTrue(bounded.contains("CTX_ANCHOR")); assertTrue(bounded.contains("CTX_B")); assertFalse(bounded.contains("OLD_16"))
    }
    @Test fun completedTextFallbackRemainsVisibleButUncommittedFailedTextIsNotGuessed() = runBlocking {
        assertTrue(words(wire("text_fallback_sent", 20)).contains("CTX_TEXT_FALLBACK"))
        assertFalse(words(wire("text_fallback_then_failed", 20)).contains("CTX_TEXT_FALLBACK"))
    }
    @Test fun groupCallUsesTheSamePublishedPeerContextWithoutToolsOrRawAudio() = runBlocking {
        for (name in listOf("normal_second", "failed_saved_voice")) {
            val body = wire(name, 20, call = true)
            assertTrue(peerWords(body).contains(if (name == "normal_second") "CTX_A" else "CTX_VOICE"))
            assertFalse(words(body).contains("UNFINISHED_SECRET"))
            assertTrue(body["tools"] == null || body["tools"]?.jsonArray?.isEmpty() == true)
        }
    }
}
