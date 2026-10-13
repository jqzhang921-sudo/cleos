package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class McpImageHistoryTest {
    private val call = ToolCall("photo", "mcp_abcd_picture", "{}")
    private val photos = MessageImages.encode(listOf(MessageImage("saved.png", 10, 10, "图片服务")))
    private val history = listOf(
        MessageEntity(id = 1, conversationId = 1, role = "user", content = "想看图片", createdAt = 1),
        MessageEntity(id = 2, conversationId = 1, role = "assistant", content = "", toolCalls = ToolCallCodec.encode(listOf(call)), createdAt = 2),
        MessageEntity(id = 3, conversationId = 1, role = "tool", content = "Cleos 已在聊天中显示 1 张服务返回的图片。", toolCallId = "photo", note = "用了图片服务", images = photos, createdAt = 3),
        MessageEntity(id = 4, conversationId = 1, role = "user", content = "收到了", createdAt = 4),
    )
    private val ta = CompanionEntity(id = 1, name = "沐", apiBaseUrl = "", apiModel = "", createdAt = 0)

    @Test fun imageOwnershipDoesntInterruptTheApiToolChainOrSendBase64() {
        val tool = McpTool("abcd", "图片服务", "picture", call.name, "图片", "找图片", JsonObject(emptyMap()), true)
        val messages = Prompt.messages(AppSettings(), ta, history, ZonedDateTime.now(), outside = listOf(tool), images = true)
        val index = messages.indexOfFirst { it.toolCalls.isNotEmpty() }
        assertTrue(index >= 0)
        assertEquals("photo", messages[index].toolCalls.single().id)
        assertEquals("tool", messages[index + 1].role)
        assertEquals("photo", messages[index + 1].toolCallId)
        assertTrue(messages[index + 1].content.contains("已在聊天中显示 1 张"))
        assertTrue(messages.all { it.images.isEmpty() })
    }

    @Test fun withoutToolsTheShownImageFactSurvivesButItsContentsArentInvented() {
        val messages = Prompt.messages(AppSettings(), ta, history, ZonedDateTime.now())
        assertFalse(messages.any { it.role == "tool" || it.toolCalls.isNotEmpty() })
        assertTrue(messages.any { it.content.contains("展示了 1 张来自外部服务的图片") && it.content.contains("不要推断画面内容") })
        assertTrue(messages.all { it.images.isEmpty() })
    }
}
