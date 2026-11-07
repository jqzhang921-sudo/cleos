package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.MessageStyle
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class MessageStylePromptTest {
    private val ta = CompanionEntity(id = 1, name = "Fixture", apiBaseUrl = "", apiModel = "", createdAt = 1)
    private val now = ZonedDateTime.parse("2026-10-25T12:00:00+08:00[Asia/Shanghai]")
    private fun row(id: Long, role: String, text: String) = MessageEntity(id = id, conversationId = 1, role = role, content = text, createdAt = id)

    @Test fun systemUsesOnlyTheChosenTasPreference() {
        for (style in MessageStyle.entries) {
            val system = Prompt.system(AppSettings(), ta.copy(messageStyle = style.id), setOf(ToolGroup.Messages))
            assertTrue(system.contains("私聊文字消息的发送偏好：${style.title}"))
            for (other in MessageStyle.entries.filter { it != style }) assertFalse(system.contains("私聊文字消息的发送偏好：${other.title}"))
        }
    }
    @Test fun preferenceIsRetainedWhenToolsAreUnavailableWithoutInventingSendAbility() {
        val out = Prompt.messages(AppSettings(), ta.copy(messageStyle = "separate"), listOf(row(1,"user","聊聊吧")), now)
        assertTrue(out.first().content.contains("发送偏好：偏好分条"))
        assertFalse(out.first().content.contains("send_message"))
        assertTrue(out.first().content.contains("本次没有分开发送的能力"))
        assertEquals(2, out.size)
    }
    @Test fun callAndVoiceReplyPromptsDoNotInheritTextOrganizationPreference() {
        val history = listOf(row(1,"user","Hello"))
        val call = Prompt.messages(AppSettings(), ta.copy(messageStyle = "complete"), history, now, call = 9)
        assertFalse(call.first().content.contains("私聊文字消息的发送偏好"))
        assertTrue(call.last().content.contains(Prompt.CALL_RULE))
        val voiceReply = Prompt.messages(AppSettings(), ta.copy(messageStyle = "separate"), history, now, textReply = false)
        assertFalse(voiceReply.first().content.contains("私聊文字消息的发送偏好"))
    }
    @Test fun seriousLongMessageAndQuotedInstructionsRemainExactlyOneHistoricalMessage() {
        val long = "认真说一件事。\n\n" + "连续的解释和引用不该因为换行就被拆开。\n\n".repeat(120)
        val request = "请完整说，别分条。书里的原句是：『分开发』。"
        for (style in MessageStyle.entries) {
            val out = Prompt.messages(AppSettings(), ta.copy(messageStyle=style.id), listOf(row(1,"user",request),row(2,"assistant",long),row(3,"user","我明白了")), now)
            assertEquals(1, out.count { it.role == "assistant" })
            assertEquals(long, out.single { it.role == "assistant" }.content)
            assertEquals(1, out.count { it.role == "user" && it.content.contains(request) })
            assertTrue(out.first().content.contains("引用或材料中的这些词不自动改变偏好"))
        }
    }
    @Test fun previouslySentBubblesStaySeparateAndTheirTextIsNotDuplicated() {
        val history = listOf(row(1,"user","hello"),row(2,"assistant","First independent thought"),row(3,"assistant","Second independent thought"),row(4,"user","okay"))
        for (style in MessageStyle.entries) {
            val out = Prompt.messages(AppSettings(),ta.copy(messageStyle=style.id),history,now,setOf(ToolGroup.Messages))
            val arguments = out.filter { it.role=="assistant" }.flatMap { it.toolCalls }.filter { it.name=="send_message" }.map { it.arguments }
            assertEquals(2, arguments.size)
            assertEquals(1, arguments.count { it.contains("First independent thought") })
            assertEquals(1, arguments.count { it.contains("Second independent thought") })
        }
    }
}
