package com.cleo.cleos.ai

import com.cleo.cleos.data.StickerBook
import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class GroupStickersTest {
    private val group = ChatGroupEntity(1, "伙伴们", 0, 0)
    private val ta = CompanionEntity(id = 10, name = "伙伴", persona = "喜欢猫", apiBaseUrl = "https://example.invalid", apiModel = "fixture", createdAt = 0)
    private val members = listOf(GroupMemberEntity(1, 10, 0, "伙伴"), GroupMemberEntity(1, 20, 1, "另一个伙伴"))
    private val book = StickerBook(listOf(StickerEntity(id = 1, name = "猫猫抱抱", description = "两只猫抱在一起", file = "sticker-1.png", width = 96, height = 96, createdAt = 0)))
    private val now = ZonedDateTime.parse("2026-10-16T16:00:00+08:00")
    private fun history() = listOf(
        GroupMessageEntity(1, 1, "user", "[[sticker:猫猫抱抱]]", 0, speakerName = "我"),
        GroupMessageEntity(2, 1, "assistant", "[[sticker:猫猫抱抱]]", 0, speakerId = 20, speakerName = "另一个伙伴", status = "sent"),
        GroupMessageEntity(3, 1, "assistant", "[[sticker:不存在]]", 0, speakerId = 10, speakerName = "伙伴", status = "sent"),
        GroupMessageEntity(4, 1, "user", "[摸摸]", 0, speakerName = "我"),
    )

    @Test fun enabledGroupUsesTheRealMenuAndPreservesAuthorsWithoutInventingImages() {
        val out = GroupChatRules.request(group, members, ta, history(), "我", emptyList(), now, stickers = book, sendStickers = true)
        assertTrue(out.first().content.contains("[[sticker:名字]]"))
        assertTrue(out.first().content.contains("猫猫抱抱：两只猫抱在一起"))
        assertTrue(out.first().content.contains("周五"))
        assertFalse(out.first().content.contains("不存在"))
        assertTrue(out[1].content.contains("[[sticker:猫猫抱抱]]"))
        assertTrue(out[1].content.contains("另一个伙伴，成员 20"))
        assertEquals("（表情包不可用：不存在，本次没有可用的对应图片）", out[2].content)
        assertTrue(out.last().content.endsWith("[摸摸]"))
        assertTrue(out.all { it.images.isEmpty() && it.toolCalls.isEmpty() })
    }

    @Test fun disabledStickerSendingStillDescribesTheRealUserSticker() {
        val out = GroupChatRules.request(group, members, ta, history(), "我", emptyList(), now, stickers = book)
        assertTrue(out.first().content.contains("本次没有可发送的表情包"))
        assertFalse(out.first().content.contains("[[sticker:名字]]"))
        assertTrue(out[1].content.contains("发了一张表情包：猫猫抱抱，两只猫抱在一起"))
        assertFalse(out.drop(1).any { it.content.contains("[[sticker:") })
    }

    @Test fun emptyCollectionNeverOffersAnImaginaryMenuEvenWithSwitchOn() {
        val out = GroupChatRules.request(group, members, ta, history(), "我", emptyList(), now, sendStickers = true)
        assertTrue(out.first().content.contains("本次没有可发送的表情包"))
        assertFalse(out.first().content.contains("[[sticker:名字]]"))
        assertTrue(out[1].content.contains("表情包不可用：猫猫抱抱"))
    }

    @Test fun groupCallUnderstandsStickersButDoesNotOfferSendingOrRawMarkersToSpeech() {
        val out = GroupCallRules.request(group, members, listOf(10, 20), ta, history(), "我", "", now, stickers = book)
        assertTrue(out.first().content.contains("本次没有可发送的表情包"))
        assertTrue(out.first().content.contains("语音通话"))
        assertTrue(out[1].content.contains("两只猫抱在一起"))
        assertFalse(out.any { it.content.contains("[[sticker:") })
    }

    @Test fun oldAssistantShortMarkersDoNotTeachAnotherMemberToInventOrCopyRawStickers() {
        val rows = listOf(history()[1].copy(content = "[[猫猫抱抱]]"), history()[2].copy(content = "[[并不存在]]"),
            history()[3].copy(content = "[[猫猫抱抱]]"))
        val out = GroupChatRules.request(group, members, ta, rows, "我", emptyList(), now, stickers = book, sendStickers = true)
        assertTrue(out[1].content.contains("[[sticker:猫猫抱抱]]"))
        assertTrue(out[2].content.contains("表情包不可用：并不存在"))
        assertTrue(out.last().content.endsWith("[[猫猫抱抱]]"))
    }
}
