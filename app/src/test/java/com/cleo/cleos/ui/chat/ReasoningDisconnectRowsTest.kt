package com.cleo.cleos.ui.chat

import com.cleo.cleos.ai.StreamingReply
import com.cleo.cleos.data.MessageThought
import com.cleo.cleos.data.MessageThoughts
import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.*
import org.junit.Test

class ReasoningDisconnectRowsTest {
    private val failed = MessageEntity(id = 23, conversationId = 1, role = "assistant", content = "", createdAt = 100,
        error = "网络出错：Software caused connection abort", thought = MessageThoughts.encode(MessageThought("private fixture thought", 10)))

    @Test fun storedNetworkErrorDoesNotTurnItsThoughtIntoTheMessageBody() {
        val rows = buildRows(listOf(failed))
        assertEquals("", rows.filterIsInstance<ChatRow.Message>().single().message.content)
        assertEquals("private fixture thought", rows.filterIsInstance<ChatRow.Activity>().single().events
            .filterIsInstance<ChatActivity.Thought>().single().text)
        assertTrue(rows.filterIsInstance<ChatRow.Activity>().single().events
            .filterIsInstance<ChatActivity.Thought>().single().interrupted)
    }

    @Test fun hidingThoughtsDoesNotMoveThemIntoTheErrorBody() {
        val rows = buildRows(listOf(failed), showThought = false)
        assertEquals("", rows.filterIsInstance<ChatRow.Message>().single().message.content)
        assertTrue(rows.none { it is ChatRow.Activity })
    }

    @Test fun liveToStoredFailureHandoverDoesNotDuplicateTheThoughtAsLiveBody() {
        val live = StreamingReply(1, "", thinking = false, savedId = 23, finished = true, thought = "private fixture thought")
        val rows = buildRows(listOf(failed), live = live)
        assertTrue(rows.none { it is ChatRow.Live })
        assertEquals(1, rows.filterIsInstance<ChatRow.Activity>().sumOf { it.events.filterIsInstance<ChatActivity.Thought>().size })
        assertEquals("", rows.filterIsInstance<ChatRow.Message>().single().message.content)
    }

    @Test fun finishedErrorHandoverStopsTheThinkingStateBeforeTheDatabaseRowArrives() {
        val live = StreamingReply(1, "", thinking = false, savedId = 23, finished = true,
            thought = "private fixture thought", error = "fixture disconnected", activity = "在等模型")
        val rows = buildRows(emptyList(), live = live)
        val activity = rows.filterIsInstance<ChatRow.Activity>().single()
        assertFalse(activity.live)
        assertTrue(activity.events.filterIsInstance<ChatActivity.Thought>().single().interrupted)
        assertTrue(activity.events.none { it is ChatActivity.Running })
        assertTrue(rows.none { it is ChatRow.Live })
    }

    @Test fun finishedHiddenThoughtDoesNotLeaveWaitingDotsOrReviveTheEarlierToolPanel() {
        val earlier = failed.copy(id = 22, role = "tool", content = "fixture result", note = "查了天气", error = null)
        val live = StreamingReply(1, "", thinking = false, savedId = 23, finished = true,
            thought = "private fixture thought", error = "fixture disconnected")
        val rows = buildRows(listOf(earlier), live = live, showThought = false)
        assertTrue(rows.none { it is ChatRow.Live })
        assertFalse(rows.filterIsInstance<ChatRow.Activity>().single().live)
    }

    @Test fun normallyCompletedThoughtDoesNotGetAnInterruptionLabel() {
        val rows = buildRows(listOf(failed.copy(content = "public fixture answer", error = null)))
        assertFalse(rows.filterIsInstance<ChatRow.Activity>().single().events
            .filterIsInstance<ChatActivity.Thought>().single().interrupted)
    }
}
