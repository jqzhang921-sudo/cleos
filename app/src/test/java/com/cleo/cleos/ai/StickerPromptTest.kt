package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.MessageReaction
import com.cleo.cleos.data.MessageReactions
import com.cleo.cleos.data.StickerBook
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.db.StickerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StickerPromptTest {
    private val now = ZonedDateTime.of(2026, 10, 1, 21, 5, 0, 0, ZoneId.of("Asia/Shanghai"))
    private val ta = CompanionEntity(id = 1, name = "", persona = "", apiBaseUrl = "", apiModel = "", createdAt = 0)
    private val dizzy = StickerEntity(id = 1, name = "兔子晕倒", description = "转圈圈倒下", file = "a.png", width = 1, height = 1, createdAt = 1)
    private val book = StickerBook(listOf(dizzy))

    private fun msg(id: Long, role: String, content: String, at: Long = id * 10, reactions: List<MessageReaction> = emptyList()) =
        MessageEntity(id = id, conversationId = 1, role = role, content = content, createdAt = at, reactions = MessageReactions.encode(reactions))

    @Test
    fun theRuleAndTheListComeOnlyWhenTheTaMaySend() {
        val history = listOf(msg(1, "user", "在吗"))
        val on = Prompt.messages(AppSettings(), ta, history, now, stickers = book, sendStickers = true).first().content
        assertTrue(on.contains("[[sticker:名字]]"))
        assertTrue(on.contains("兔子晕倒：转圈圈倒下"))
        val off = Prompt.messages(AppSettings(), ta, history, now, stickers = book, sendStickers = false).first().content
        assertFalse(off.contains("sticker"))
        assertFalse(off.contains("兔子晕倒"))
        val none = Prompt.messages(AppSettings(), ta, history, now, stickers = StickerBook.EMPTY, sendStickers = true).first().content
        assertFalse("no collection, nothing to offer", none.contains("sticker"))
    }

    @Test
    fun theListComesBeforeWhatChangesMoreOften() {
        val s = Prompt.system(AppSettings(), ta, recap = "我们聊了猫。", stickers = listOf(dizzy))
        assertTrue(s.indexOf("兔子晕倒") in 0 until s.indexOf("【前情提要】"))
    }

    @Test
    fun thePersonsStickerIsReadAsTheyWriteItOrInWords() {
        val history = listOf(msg(1, "user", "[[sticker:兔子晕倒]]"))
        val on = Prompt.messages(AppSettings(), ta, history, now, stickers = book, sendStickers = true).last().content
        assertTrue(on.endsWith("[[sticker:兔子晕倒]]"))
        val off = Prompt.messages(AppSettings(), ta, history, now, stickers = book, sendStickers = false).last().content
        assertTrue(off.endsWith("（发了一张表情包：兔子晕倒，转圈圈倒下）"))
    }

    @Test
    fun aReactionIsToldWithThePersonsNextMessage() {
        val history = listOf(
            msg(1, "user", "在吗"),
            msg(2, "assistant", "在呀，今天去看海了，风好大", reactions = listOf(MessageReaction("❤️", at = 25), MessageReaction("😂", at = 26))),
            msg(3, "user", "好看吗"),
        )
        val out = Prompt.messages(AppSettings(), ta, history, now)
        assertEquals("（消息编号 #1）\n在吗", out[1].content)
        assertTrue(out[3].content.endsWith("（对方给你说的「在呀，今天去看海了，风好大」贴了 ❤️ 😂）\n好看吗"))
    }

    @Test
    fun oneAfterTheirLastMessageWaitsForTheNext() {
        val history = listOf(
            msg(1, "user", "在吗"),
            msg(2, "assistant", "在呀", reactions = listOf(MessageReaction("❤️", at = 99))),
        )
        assertFalse(Prompt.messages(AppSettings(), ta, history, now).any { it.content.contains("贴了") })
    }

    @Test
    fun aReactionOnAStickerNamesTheSticker() {
        val history = listOf(
            msg(1, "assistant", "[[sticker:兔子晕倒]]", reactions = listOf(MessageReaction("😂", at = 15))),
            msg(2, "user", "哈哈哈"),
        )
        val out = Prompt.messages(AppSettings(), ta, history, now, stickers = book, sendStickers = true)
        assertTrue(out.last().content.contains("（对方给你发的表情包「兔子晕倒」贴了 😂）"))
    }

    @Test
    fun aLongMessageIsShownByItsBeginning() {
        val long = "今天去了海边，风很大，浪一阵一阵地打上来，沙子里全是贝壳"
        val history = listOf(
            msg(1, "assistant", long, reactions = listOf(MessageReaction("👍", at = 15))),
            msg(2, "user", "嗯"),
        )
        val line = Prompt.messages(AppSettings(), ta, history, now).last().content
        assertTrue(line.contains("「今天去了海边，风很大，浪一阵一阵地打上来…」"))
    }
}
