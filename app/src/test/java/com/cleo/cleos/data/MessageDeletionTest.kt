package com.cleo.cleos.data

import com.cleo.cleos.ai.ToolCall
import com.cleo.cleos.ai.ToolCallCodec
import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.*
import org.junit.Test

class MessageDeletionTest {
    private fun row(id: Long, role: String, content: String = "text") = MessageEntity(id = id, conversationId = 1, role = role, content = content, createdAt = id)
    private val owner = row(2, "assistant", "").copy(toolCalls = ToolCallCodec.encode(listOf(ToolCall("a", "search", "{}"), ToolCall("b", "search", "{}"))))
    private val a = row(3, "tool").copy(toolCallId = "a")
    private val b = row(4, "tool").copy(toolCallId = "b")
    private val history get() = listOf(row(1, "user"), owner, a, b, row(5, "assistant"), row(6, "user"), row(7, "assistant"))
    @Test fun deletingResultCleansCompleteBatchButKeepsLaterConversation() {
        assertEquals(setOf(2L, 3L, 4L), MessageDeletion.plan(history, setOf(3)).deleted)
    }
    @Test fun deletingAnswerIncludesItsPrecedingTools() {
        assertEquals(setOf(2L, 3L, 4L, 5L), MessageDeletion.plan(history, setOf(5)).deleted)
    }
    @Test fun laterAnswerDoesNotDeleteEarlierTurnTools() {
        assertEquals(setOf(7L), MessageDeletion.plan(history, setOf(7)).deleted)
    }
    @Test fun toolDeletionKeepsOwnersOrdinaryTextWithoutToolRequests() {
        val plan = MessageDeletion.plan(history.map { if (it.id == 2L) it.copy(content = "查一下") else it }, setOf(3))
        assertEquals(setOf(3L, 4L), plan.deleted)
        assertEquals(setOf(2L), plan.clearTools)
    }
    @Test fun callDeletionIncludesHiddenCallRows() {
        val plan = MessageDeletion.plan(listOf(row(1, "call"), row(2, "user").copy(call = 1), row(3, "assistant")), setOf(1))
        assertEquals(setOf(1L, 2L), plan.deleted)
    }
    @Test fun toolIdsCannotCrossConversations() {
        val other = row(8, "tool").copy(conversationId = 2, toolCallId = "a")
        assertEquals(setOf(2L, 3L, 4L), MessageDeletion.plan(history + other, setOf(3)).deleted)
        assertEquals(setOf(8L), MessageDeletion.plan(history + other, setOf(8)).deleted)
    }
}
