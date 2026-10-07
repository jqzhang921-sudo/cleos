package com.cleo.cleos.data

import com.cleo.cleos.ai.ToolCallCodec
import com.cleo.cleos.data.db.MessageEntity

/** Delete complete tool batches; keep an owner's ordinary text when only its tool is removed. */
object MessageDeletion {
    data class Plan(val deleted: Set<Long>, val clearTools: Set<Long>)
    fun plan(history: List<MessageEntity>, selected: Set<Long>): Plan {
        val rows = history.sortedWith(compareBy<MessageEntity> { it.createdAt }.thenBy { it.id })
        val deleted = rows.filter { it.id in selected }.map { it.id }.toMutableSet()
        val clear = mutableSetOf<Long>()
        rows.filter { it.id in selected && it.role == "call" }.forEach { call ->
            rows.filter { it.call == call.id }.forEach { deleted += it.id }
        }
        val owners = rows.filter { !it.toolCalls.isNullOrBlank() }
        owners.forEach { owner ->
            val calls = ToolCallCodec.decode(owner.toolCalls!!).map { it.id }.toSet()
            val results = rows.filter { it.conversationId == owner.conversationId && it.role == "tool" && it.toolCallId in calls }
            val conversationRows = rows.filter { it.conversationId == owner.conversationId }
            val ownerIndex = conversationRows.indexOf(owner)
            val answerSelected = conversationRows.drop(ownerIndex + 1)
                .takeWhile { !(it.role == "user" && it.note == null) }
                .any { it.role == "assistant" && it.id in selected }
            if (owner.id in deleted || results.any { it.id in deleted } || answerSelected) {
                deleted += results.map { it.id }
                if (owner.content.isBlank()) deleted += owner.id else clear += owner.id
            }
        }
        return Plan(deleted, clear - deleted)
    }
}
