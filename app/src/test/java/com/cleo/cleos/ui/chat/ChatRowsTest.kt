package com.cleo.cleos.ui.chat

import com.cleo.cleos.ai.StreamingReply
import com.cleo.cleos.ai.McpAsk
import com.cleo.cleos.ai.ToolCall
import com.cleo.cleos.ai.ToolCallCodec
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.MessageThought
import com.cleo.cleos.data.MessageThoughts
import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.*
import org.junit.Test

class ChatRowsTest {
    private fun user(id: Long) = MessageEntity(id, 1, "user", "我的消息", id)
    private fun thought(id: Long, content: String = "") = MessageEntity(id, 1, "assistant", content, id,
        thought = MessageThoughts.encode(MessageThought("思考$id", 1000)),
        toolCalls = ToolCallCodec.encode(listOf(ToolCall("c$id", "memory", "{}"))))
    private fun tool(id: Long, callId: Long, note: String = "翻了记忆") =
        MessageEntity(id, 1, "tool", "完整结果$id", id, toolCallId = "c$callId", note = note)
    private fun groups(rows: List<ChatRow>) = rows.filterIsInstance<ChatRow.Activity>()

    @Test fun alternatingThoughtsToolsAndFinalThoughtBecomeOneOrderedPanelWithoutChangingMessages() {
        val messages = listOf(user(1), thought(2), tool(3, 2), thought(4), tool(5, 4), thought(6, "最后的回复"))
        val before = messages.toList()
        val rows = buildRows(messages)
        val group = groups(rows).single()
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), group.events.map { it.messageId })
        assertEquals("最后的回复", rows.filterIsInstance<ChatRow.Message>().first().message.content)
        assertEquals(before, messages)
        assertEquals(setOf(2L, 3L, 4L, 5L, 6L), group.ids)
    }

    @Test fun intermediateWordsAndPicturesStayVisibleWhileActivityUsesOnePanel() {
        val photo = tool(5, 4).copy(images = MessageImages.encode(listOf(MessageImage("synthetic.jpg", 100, 100))))
        val rows = buildRows(listOf(user(1), thought(2, "先说一句"), tool(3, 2), thought(4), photo, thought(6, "再说一句")))
        assertEquals(1, groups(rows).size)
        val bodies = rows.filterIsInstance<ChatRow.Message>().filter { it.message.role != "user" }
        assertEquals(listOf(6L, 5L, 2L), bodies.map { it.message.id })
        assertEquals("synthetic.jpg", MessageImages.decode(bodies[1].message.images).single().file)
        assertTrue(bodies.all { it.activityFolded })
    }

    @Test fun anotherUserTurnCannotJoinThePreviousReply() {
        val rows = buildRows(listOf(user(1), thought(2), tool(3, 2), user(4), thought(5), tool(6, 5)))
        assertEquals(listOf(setOf(5L, 6L), setOf(2L, 3L)), groups(rows).map { it.ids })
    }

    @Test fun noticesRequestsRecapAndLongTimeGapsRemainVisibleBoundaries() {
        val first = listOf(user(1), thought(2), tool(3, 2))
        for (boundary in listOf(
            MessageEntity(4, 1, "note", "", 4, note = "不能用这个工具"),
            MessageEntity(4, 1, "request", "等待确认", 4),
        )) {
            val rows = buildRows(first + boundary + thought(5))
            assertEquals(2, groups(rows).size)
            assertTrue(rows.filterIsInstance<ChatRow.Message>().any { it.message == boundary })
        }
        assertEquals(2, groups(buildRows(first + thought(5), recapUntil = 3L to 3L)).size)
        assertEquals(2, groups(buildRows(first + thought(5).copy(createdAt = 700_000))).size)
    }

    @Test fun hidingThoughtsRetainsAllToolsIncludingFailureAndNormalReplies() {
        val rows = buildRows(listOf(user(1), thought(2), tool(3, 2, "想用的工具不存在：open"), thought(4, "正文")), showThought = false)
        val group = groups(rows).single()
        assertEquals(listOf(3L), group.events.map { it.messageId })
        assertTrue(ToolRuns.failed((group.events.single() as ChatActivity.Tool).message.note.orEmpty()))
        assertTrue(rows.filterIsInstance<ChatRow.Message>().any { it.message.content == "正文" })
    }

    @Test fun liveThinkingAndToolActivityUseTheExistingPanelAndKeepAStableLiveKey() {
        val messages = listOf(user(1), thought(2), tool(3, 2))
        val live = StreamingReply(1, "", true, thought = "继续思考")
        val group = groups(buildRows(messages, live = live)).single()
        assertTrue(group.live)
        assertEquals(listOf(2L, 3L, null), group.events.map { it.messageId })
        assertFalse(buildRows(messages, live = live).any { it is ChatRow.Live })
        val next = groups(buildRows(messages + thought(4) + tool(5, 4), live = live.copy(thought = "下一段"))).single()
        assertEquals(group.key, next.key)
        val waiting = groups(buildRows(messages, live = live.copy(thought = ""))).single()
        assertTrue(waiting.events.last() is ChatActivity.Running)
        assertEquals(group.key, waiting.key)
    }

    @Test fun storedHandoverDoesNotDuplicateThoughtOrReply() {
        val messages = listOf(user(1), thought(2, "正文"))
        val live = StreamingReply(1, "正文", false, savedId = 2, finished = true, thought = "思考2", thoughtMs = 1000)
        val rows = buildRows(messages, live = live)
        assertEquals(1, groups(rows).single().events.size)
        assertFalse(rows.any { it is ChatRow.Live })
        assertEquals(1, rows.filterIsInstance<ChatRow.Message>().count { it.message.content == "正文" })
    }

    @Test fun completedPanelKeepsItsIdentityAsLaterStepsAreAdded() {
        val old = groups(buildRows(listOf(user(1), thought(2), tool(3, 2)))).single()
        val newer = groups(buildRows(listOf(user(1), thought(2), tool(3, 2), thought(4, "最后正文")))).single()
        assertEquals(old.key, newer.key)
    }

    @Test fun pictureOnlyAssistantAndErrorNeverBecomeInvisibleThoughtRows() {
        val photo = thought(2).copy(images = MessageImages.encode(listOf(MessageImage("synthetic.jpg", 100, 100))))
        val error = thought(3).copy(error = "请求失败（400）")
        val rows = buildRows(listOf(user(1), photo, error), showThought = false)
        assertEquals(listOf(3L, 2L, 1L), rows.filterIsInstance<ChatRow.Message>().map { it.message.id })
    }

    @Test fun liveApprovalRemainsAnIndependentVisibleRow() {
        val messages = listOf(user(1), thought(2), tool(3, 2))
        val ask = McpAsk("合成服务", "更新资料", "{}")
        val rows = buildRows(messages, live = StreamingReply(1, "", false, asking = ask))
        assertEquals(ask, rows.filterIsInstance<ChatRow.Live>().single().reply.asking)
        assertFalse(groups(rows).single().live)
        assertEquals(setOf(2L, 3L), groups(rows).single().ids)
    }

    @Test fun proactiveReplyDoesNotMergeIntoTheOlderAnswer() {
        val rows = buildRows(listOf(user(1), thought(2), tool(3, 2),
            thought(4).copy(proactive = true), tool(5, 4).copy(proactive = true),
            thought(6, "主动回复").copy(proactive = true)))
        assertEquals(listOf(setOf(5L), setOf(2L, 3L)), groups(rows).map { it.ids })
        assertEquals(1, rows.filterIsInstance<ChatRow.Woke>().size)
    }

    @Test fun backgroundThoughtIsHiddenForOldRecordsWhileTheActualMessageAndOrdinaryThoughtStay() {
        val private = thought(4, "主动说的话").copy(proactive = true)
        val messages = listOf(user(1), thought(2, "普通回复"), user(3), private)
        val rows = buildRows(messages, showThought = true)
        assertEquals(listOf(2L), groups(rows).flatMap { it.events }.map { it.messageId })
        assertTrue(rows.filterIsInstance<ChatRow.Message>().any { it.message.content == "主动说的话" })
        assertEquals("思考4", MessageThoughts.decode(private.thought)?.text)
    }
}
