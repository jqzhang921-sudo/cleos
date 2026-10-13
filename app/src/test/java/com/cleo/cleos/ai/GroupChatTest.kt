package com.cleo.cleos.ai

import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class GroupChatTest {
    private val group = ChatGroupEntity(1, "伙伴们", 0, 0)
    private val ta = CompanionEntity(id = 10, name = "同名", persona = "喜欢猫", apiBaseUrl = "https://example.com", apiModel = "test", createdAt = 0)
    private val members = listOf(GroupMemberEntity(1, 10, 0, "同名"), GroupMemberEntity(1, 20, 1, "同名"))
    private fun row(id: Long, role: String, speaker: Long? = null, text: String = "消息$id", status: String = "sent") =
        GroupMessageEntity(id, 1, role, text, 0, speaker, "同名", turnId = if (speaker != null) 1 else null, status = status)

    @Test fun ordinaryTurnsRotateAndNeverExceedTwoMembers() {
        val first = GroupChatRules.select(listOf(10, 20, 30, 40), emptyList(), 0)
        assertEquals(listOf(10L, 20L), first.targets)
        val next = GroupChatRules.select(listOf(10, 20, 30, 40), emptyList(), first.next)
        assertEquals(listOf(30L, 40L), next.targets)
        assertEquals(0, next.next)
        assertEquals(listOf(20L), GroupChatRules.select(listOf(20), emptyList(), 100).targets)
    }
    @Test fun mentionUsesStableIdsAndPreservesExplicitOrder() {
        assertEquals(listOf(20L, 10L), GroupChatRules.select(listOf(10, 20), listOf(20, 10), 1).targets)
        assertEquals(1, GroupChatRules.select(listOf(10, 20), listOf(10), 1).next)
        for (bad in listOf(listOf(99L), listOf(10L, 10L))) {
            try { GroupChatRules.select(listOf(10, 20), bad, 0); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun sameNameMembersKeepSeparateAuthorRolesAndOtherTaIsNotTheAssistant() {
        val sent = GroupChatRules.request(group, members, ta, listOf(row(1, "user"), row(2, "assistant", 20), row(3, "assistant", 10), row(4, "user")),
            "用户", listOf(10), ZonedDateTime.parse("2026-10-11T12:00:00+08:00"))
        assertEquals(listOf("system", "user", "assistant", "user"), sent.map { it.role })
        assertTrue(sent[1].content.contains("成员 20"))
        assertEquals("消息3", sent[2].content)
        assertTrue(sent.first().content.contains("成员 10"))
        assertTrue(sent.all { it.toolCalls.isEmpty() && it.images.isEmpty() })
    }
    @Test fun failedPartialRepliesNeverEnterAnotherMembersHistory() {
        val sent = GroupChatRules.request(group, members, ta, listOf(row(1, "user"), row(2, "assistant", 20, "半句话", "failed")),
            "用户", emptyList(), ZonedDateTime.now())
        assertFalse(sent.any { it.content.contains("半句话") })
        assertTrue(sent.first().content.contains("按顺序轮到你"))
    }
    @Test fun successfulMemberIsNeverRequestedAgainOnRetry() = runBlocking {
        val calls = mutableListOf<Long>()
        GroupReplyEngine.round(listOf(row(2, "assistant", 10), row(3, "assistant", 20, status = "failed")), 3,
            { true }, { calls += it.speakerId!! })
        assertEquals(listOf(20L), calls)
    }
    @Test fun stoppedRoundCannotStartTheNextMember() = runBlocking {
        val calls = mutableListOf<Long>()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            GroupReplyEngine.round(listOf(row(2, "assistant", 10, status = "pending"), row(3, "assistant", 20, status = "pending")), null,
                { true }, { calls += it.speakerId!!; started.complete(Unit); awaitCancellation() })
        }
        started.await(); job.cancelAndJoin()
        assertEquals(listOf(10L), calls)
    }
    @Test fun streamedReplyKeepsTextAndHidesReasoning() = runBlocking {
        var shown = ""
        val reply = GroupReplyEngine.reply(flowOf(ChatEvent.Reasoning("隐藏思考"), ChatEvent.Delta("你"), ChatEvent.Delta("在呀。"))) { shown = it }
        assertEquals("你在呀。", reply)
        assertEquals(reply, shown)
    }
    @Test fun failingStreamKeepsPartialOutputWithoutReportingSuccess() = runBlocking {
        var shown = ""
        try {
            GroupReplyEngine.reply(flow { emit(ChatEvent.Delta("已经说的半句")); throw IllegalStateException("network") }) { shown = it }
            fail()
        } catch (_: IllegalStateException) { }
        assertEquals("已经说的半句", shown)
    }
    @Test fun replyBoundRejectsOverlongOrEmptyContent() = runBlocking {
        for (events in listOf(flowOf(ChatEvent.Delta(" ")), flowOf(ChatEvent.Delta("x".repeat(GroupChatRules.MAX_REPLY + 1))))) {
            try { GroupReplyEngine.reply(events) { }; fail() } catch (_: IllegalStateException) { }
        }
    }
    @Test fun unrequestedToolCallsAreNotReportedAsExecuted() = runBlocking {
        var shown = ""
        try {
            GroupReplyEngine.reply(flowOf(ChatEvent.Delta("准备试一下"), ChatEvent.ToolCalls(listOf(ToolCall("call1", "message", "{}"))))) { shown = it }
            fail()
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("没有执行")) }
        assertEquals("准备试一下", shown)
    }
    @Test fun retryCannotDuplicateSuccessfulOrPendingSlot() {
        assertFalse(GroupChatRules.retryable(row(2, "assistant", 10)))
        assertFalse(GroupChatRules.retryable(row(2, "assistant", 10, status = "pending")))
        assertFalse(GroupChatRules.retryable(row(1, "user", status = "failed")))
        assertTrue(GroupChatRules.retryable(row(2, "assistant", 10, status = "interrupted")))
    }
}
