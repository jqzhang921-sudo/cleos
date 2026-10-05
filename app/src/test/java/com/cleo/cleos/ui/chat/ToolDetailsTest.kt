package com.cleo.cleos.ui.chat

import com.cleo.cleos.ai.ToolCall
import com.cleo.cleos.ai.ToolCallCodec
import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDetailsTest {
    private fun assistant(id: Long, vararg calls: ToolCall) =
        MessageEntity(id = id, conversationId = 1, role = "assistant", content = "", createdAt = id, toolCalls = ToolCallCodec.encode(calls.toList()))

    private fun tool(id: Long, callId: String, result: String) =
        MessageEntity(id = id, conversationId = 1, role = "tool", content = result, createdAt = id, toolCallId = callId, note = "查了记忆")

    @Test
    fun aToolRowFindsItsCallOnTheNearestTurnBefore() {
        // Two turns that numbered their calls alike: each result belongs to the turn just before it.
        val messages = listOf(
            assistant(1, ToolCall("call_0", "latent_search", """{"query":"旧的"}""")),
            tool(2, "call_0", "旧结果"),
            assistant(3, ToolCall("call_0", "latent_search", """{"query":"新的"}""")),
            tool(4, "call_0", "新结果"),
        )
        val old = ToolDetails.find(messages, messages[1])
        assertEquals("latent_search", old.name)
        assertTrue(old.arguments.contains("旧的"))
        assertEquals("旧结果", old.result)
        val new = ToolDetails.find(messages, messages[3])
        assertTrue(new.arguments.contains("新的"))
        assertEquals("新结果", new.result)
    }

    @Test
    fun aCallThatIsGoneStillShowsItsResult() {
        val row = tool(2, "call_9", "结果")
        val d = ToolDetails.find(listOf(row), row)
        assertEquals("工具", d.name)
        assertEquals("", d.arguments)
        assertEquals("结果", d.result)
    }

    @Test
    fun argumentsArePrettyPrintedWhenTheyParseAndLeftAloneWhenNot() {
        assertTrue(ToolDetails.prettyJson("""{"a":1}""").contains("\n"))
        assertEquals("{坏掉的", ToolDetails.prettyJson("{坏掉的"))
    }

    @Test
    fun aToolRowKnowsTheNameTheModelCalledItBy() {
        val messages = listOf(
            assistant(1, ToolCall("a", "set_alarm", "{}")),
            tool(2, "a", "好了"),
            tool(3, "zzz", "没有这次调用"),
        )
        assertEquals("set_alarm", ToolDetails.nameOf(messages, messages[1]))
        assertEquals(null, ToolDetails.nameOf(messages, messages[2]))
    }
}
