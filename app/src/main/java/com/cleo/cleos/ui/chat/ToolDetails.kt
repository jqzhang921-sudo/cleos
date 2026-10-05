package com.cleo.cleos.ui.chat

import com.cleo.cleos.ai.ToolCallCodec
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One tool call as the chat shows it when tapped: what was asked of the tool, and what it answered. */
internal data class ToolDetail(val name: String, val arguments: String, val result: String)

internal object ToolDetails {
    private val pretty = Json { prettyPrint = true }

    /**
     * The call a "tool" row answers: its name and arguments are on the assistant turn that made it,
     * the nearest one before the row (some providers number their calls per turn, so an id alone
     * can match an older turn). The result is the row's own content, as the model saw it. A call
     * that can't be found (its turn was deleted) still shows the result.
     */
    fun find(messages: List<MessageEntity>, row: MessageEntity): ToolDetail {
        val at = messages.indexOfFirst { it.id == row.id }
        val id = row.toolCallId
        val call = if (id == null || at < 0) null else (at - 1 downTo 0).firstNotNullOfOrNull { i ->
            val m = messages[i]
            if (m.role != "assistant" || m.toolCalls.isNullOrBlank()) null else ToolCallCodec.decode(m.toolCalls).firstOrNull { it.id == id }
        }
        return ToolDetail(call?.name ?: "工具", call?.let { prettyJson(it.arguments) }.orEmpty(), row.content)
    }

    /** Arguments are raw JSON text and may not parse: then as they came. */
    fun prettyJson(raw: String): String =
        runCatching { pretty.encodeToString(JsonElement.serializer(), pretty.parseToJsonElement(raw)) }.getOrDefault(raw)
}

/** The lines tool calls leave in the chat. */
internal object ToolRuns {
    /**
     * A call that didn't go through says so in its line, one of two ways: the tool turned it
     * down ("记待办没成：参数写错了") or it threw on the way through ("翻日记出错了"). The line is
     * already its own report, so reading its ending is enough — and the ending, not a word
     * anywhere in it: what the person is called and what they put in a todo are their own
     * words, and one of them saying "没成" doesn't make a call that worked a failure.
     * A tool the person wouldn't let the TA use ("没让TA用XX") is neither: that was their
     * call, not something to draw a warning about.
     */
    fun failed(note: String): Boolean = note.contains("没成：") || note.endsWith("出错了")
}
