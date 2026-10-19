package com.cleo.cleos.ai

import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class GroupInterjectionsTest {
    private val group = ChatGroupEntity(1, "伙伴", 0, 0, allowInterjections = true)
    private val anchor = GroupMessageEntity(1, 1, "user", "聊聊猫", 0)
    private val turn = GroupTurnEntity(1, 1, 1, "[10,20]", 0)
    private val members = (1L..4L).mapIndexed { i, id -> GroupMemberEntity(1, id * 10, i, "同名") }
    private val replies = listOf(row(2, 10, "sent"), row(3, 20, "running"))
    private fun row(id: Long, ta: Long, status: String = "pending") = GroupMessageEntity(id, 1, "assistant", "", 0,
        speakerId = ta, speakerName = "同名", turnId = 1, status = status)
    private fun candidates(g: ChatGroupEntity = group, t: GroupTurnEntity = turn, a: GroupMessageEntity? = anchor,
        speaker: Long = 20, slots: List<GroupMessageEntity> = replies, roster: List<GroupMemberEntity> = members,
        available: Set<Long> = members.map { it.companionId }.toSet(), allowed: Boolean = true) =
        GroupInterjectionRules.candidates(g, t, a, speaker, slots, roster, available, allowed)

    @Test fun onlySecondOrdinarySpeakerCanInviteAnUnspokenActiveMember() {
        assertEquals(listOf(30L, 40L), candidates().map { it.companionId })
        assertTrue(candidates(speaker = 10).isEmpty())
        assertTrue(candidates(g = group.copy(allowInterjections = false)).isEmpty())
        assertTrue(candidates(allowed = false).isEmpty())
        assertTrue(candidates(roster = members.take(2)).isEmpty())
        assertEquals(listOf(40L), candidates(roster = members.map { if (it.companionId == 30L) it.copy(active = false) else it }).map { it.companionId })
        assertEquals(listOf(30L), candidates(available = setOf(10, 20, 30)).map { it.companionId })
    }

    @Test fun explicitMentionsAndIncompleteOrForeignTurnsNeverOfferInterjections() {
        for (a in listOf(anchor.copy(mentions = "[20]"), anchor.copy(mentions = "broken"), anchor.copy(groupId = 9),
            anchor.copy(role = "assistant"), anchor.copy(id = 99))) assertTrue(candidates(a = a).isEmpty())
        assertTrue(candidates(a = null).isEmpty())
        assertTrue(candidates(t = turn.copy(status = "cancelled")).isEmpty())
        assertTrue(candidates(t = turn.copy(groupId = 9)).isEmpty())
        assertTrue(candidates(t = turn.copy(targets = "[10,20,30]")).isEmpty())
        assertTrue(candidates(slots = replies.map { it.copy(groupId = 9) }).isEmpty())
        assertTrue(candidates(slots = replies.map { it.copy(turnId = 9) }).isEmpty())
        assertTrue(candidates(slots = replies.reversed()).isEmpty())
        for (state in listOf("failed", "cancelled", "interrupted", "pending")) {
            assertTrue(candidates(slots = listOf(replies[0].copy(status = state), replies[1])).isEmpty())
        }
    }

    @Test fun stableNumericIdsAndBoundedSchemaRejectNamesSelfDuplicatesAndExtraArguments() {
        val available = candidates()
        val spec = requireNotNull(GroupInterjectionRules.spec(available))
        assertEquals(listOf(30L, 40L), spec.parameters["properties"]!!.jsonObject["member_id"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals(40L, GroupInterjectionRules.target("""{"member_id":40}""", available))
        for (bad in listOf("""{"member_id":20}""", """{"member_id":99}""", """{"member_id":"30"}""",
            """{"member_id":30.0}""", """{"member_id":-30}""", """{"member_id":30,"text":"anything"}""", "{}", "[]")) {
            try { GroupInterjectionRules.target(bad, available); fail() } catch (_: IllegalArgumentException) { }
        }
        assertNull(GroupInterjectionRules.spec(emptyList()))
    }

    @Test fun oneFreshExtraRunsAfterBothPrimariesAndCannotInviteAnother() = runBlocking {
        val first = replies.map { it.copy(status = "pending") }
        val slots = first.toMutableList()
        val calls = mutableListOf<Pair<Long?, Boolean>>()
        GroupInterjectionRound.run(first, null, { true }, { slot, canInvite ->
            calls += slot.speakerId to canInvite
            if (slot.speakerId == 20L) slots += row(4, 30)
        }, { slots })
        assertEquals(listOf(10L to true, 20L to true, 30L to false), calls)
    }

    @Test fun noInvitationMakesNoExtraRequestAndRetryNeverConsultsTheExtraQueue() = runBlocking {
        val calls = mutableListOf<Long?>()
        GroupInterjectionRound.run(replies.map { it.copy(status = "pending") }, null, { true }, { slot, _ -> calls += slot.speakerId }, { replies })
        assertEquals(listOf(10L, 20L), calls)
        calls.clear()
        GroupInterjectionRound.run(replies.map { it.copy(status = "failed") }, 3, { true }, { slot, allowed ->
            assertFalse(allowed); calls += slot.speakerId
        }, { error("retry cannot append another member") })
        assertEquals(listOf(20L), calls)
    }

    @Test fun stopBeforeHandoffCannotRequestAdditionalMembers() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val calls = mutableListOf<Long?>()
        val first = replies.map { it.copy(status = "pending") }
        val job = launch {
            GroupInterjectionRound.run(first, null, { true }, { slot, _ ->
                calls += slot.speakerId
                if (slot.speakerId == 20L) { reached.complete(Unit); awaitCancellation() }
            }, { error("cancelled turn cannot inspect or start a handoff") })
        }
        reached.await(); job.cancelAndJoin()
        assertEquals(listOf(10L, 20L), calls)
    }

    @Test fun malformedExtraQueuesAreRejectedBeforeRequestingAnotherModel() = runBlocking {
        val first = replies.map { it.copy(status = "sent") }
        for (bad in listOf(listOf(row(4, 30), row(5, 40)), listOf(row(4, 20)), listOf(row(4, 30).copy(groupId = 9)),
            listOf(row(4, 30).copy(turnId = 9)), listOf(row(4, 30, "interrupted")))) {
            var extra = false
            try {
                GroupInterjectionRound.run(first, null, { true }, { _, _ -> extra = true }, { first + bad }); fail()
            } catch (_: IllegalStateException) { }
            assertFalse(extra)
        }
    }

    @Test fun handoffIsAnActualOfferedToolAndItsResultReturnsToTheCurrentSpeaker() = runBlocking {
        val offered = listOf(requireNotNull(GroupInterjectionRules.spec(candidates())))
        var requests = 0
        var chosen: Long? = null
        var trace = GroupToolTrace()
        val result = GroupToolReply.reply(listOf(ApiMessage("user", "fixture")), offered, false, { messages, _, _ ->
            if (++requests == 1) flowOf(ChatEvent.ToolCalls(listOf(ToolCall("invite1", GroupInterjectionRules.TOOL, """{"member_id":30}"""))))
            else {
                assertEquals("tool", messages.last().role); assertEquals("invite1", messages.last().toolCallId)
                assertTrue(messages.last().content.contains("pending")); flowOf(ChatEvent.Delta("一句自己的补充"))
            }
        }, { chosen = GroupInterjectionRules.target(it.arguments, candidates()); ToolOutcome("pending until this reply completes", "待完成") },
            onTrace = { trace = it }) {}
        assertEquals(30L, chosen); assertEquals("一句自己的补充", result)
        assertEquals("returned", trace.records.single().state)
        assertTrue(GroupToolRules.validate(GroupToolRules.encode(trace)))
    }

    @Test fun unOfferedInvitationIsNotExecutedEvenIfTheModelSaysItCalledOne() = runBlocking {
        var requests = 0
        var trace = GroupToolTrace()
        GroupToolReply.reply(listOf(ApiMessage("user", "fixture")), emptyList(), false, { _, _, _ ->
            if (++requests == 1) flowOf(ChatEvent.ToolCalls(listOf(ToolCall("invite1", GroupInterjectionRules.TOOL, """{"member_id":30}"""))))
            else flowOf(ChatEvent.Delta("文字回复"))
        }, { error("unoffered control tool must not execute") }, onTrace = { trace = it }) {}
        assertEquals("rejected", trace.records.single().state)
    }

    @Test fun providerRefusalFallsBackToTextWithoutArrangingAHandoff() = runBlocking {
        val names = mutableListOf<List<String>>()
        var requests = 0
        val result = GroupToolReply.reply(listOf(ApiMessage("user", "fixture")), listOf(requireNotNull(GroupInterjectionRules.spec(candidates()))),
            false, { _, tools, _ -> flow {
                names += tools.map { it.name }
                if (++requests == 1) throw ChatException("refused", 400, rejectedFeature = RequestFeature.Tools)
                emit(ChatEvent.Delta("仅文字"))
            } }, { error("no handoff after tools refusal") }) {}
        assertEquals("仅文字", result)
        assertEquals(listOf(listOf(GroupInterjectionRules.TOOL), emptyList<String>()), names)
    }

    @Test fun extraPromptKeepsAuthorsAndDoesNotInventOriginalAudioOrPrivateHistory() {
        val extra = row(4, 30)
        val t = turn.copy(targets = "[10,20,30]")
        assertTrue(GroupInterjectionRules.isExtra(t, anchor, extra))
        assertFalse(GroupInterjectionRules.isExtra(t, anchor.copy(mentions = "[10,20,30]"), extra))
        assertFalse(GroupInterjectionRules.isExtra(t, anchor, extra.copy(groupId = 9)))
        val ta = CompanionEntity(id = 30, name = "同名", apiBaseUrl = "https://example.invalid", apiModel = "fixture", createdAt = 0)
        val request = GroupChatRules.request(group, members, ta, listOf(anchor, replies[0].copy(content = "first real words"), replies[1].copy(status = "sent", content = "second real words")),
            "用户", emptyList(), ZonedDateTime.now(), interjection = true)
        assertTrue(request.first().content.contains("简短补充一两句"))
        assertTrue(request.first().content.contains("不能再邀请"))
        assertTrue(request.last().content.contains("成员 10")); assertTrue(request.last().content.contains("成员 20"))
        assertTrue(request.last().content.contains("first real words")); assertTrue(request.last().content.contains("second real words"))
        assertTrue(request.all { it.toolCalls.isEmpty() && it.images.isEmpty() })
    }
}
