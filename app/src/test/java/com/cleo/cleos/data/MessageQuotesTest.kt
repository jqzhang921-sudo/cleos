package com.cleo.cleos.data

import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.db.WorldAlbumLookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageQuotesTest {
    private var id = 0L
    private fun msg(role: String, content: String) =
        MessageEntity(id = ++id, conversationId = 1, role = role, content = content, createdAt = id)

    @Test
    fun albumReplyKeepsTheWholeCommentAndQuestionWithoutAChatId() {
        val comment = "这张照片".repeat(80)
        val look = WorldAlbumLookEntity(id = 41, companionId = 2, photoId = 3, photoSha256 = "hash", runId = "look",
            content = comment, question = "那天是几点？", createdAt = 100)
        val quote = checkNotNull(MessageQuotes.fromAlbum(look))
        assertEquals(MessageQuote(0L, "assistant", "$comment\n\n那天是几点？", fromAlbum = true), quote)
        assertEquals(quote, MessageQuotes.decode(MessageQuotes.encode(quote)))
        assertNull(MessageQuotes.fromAlbum(look.copy(content = "  ", question = "")))
    }

    @Test
    fun whatAQuoteKeeps() {
        val m = msg("assistant", "盗走了")
        assertEquals(MessageQuote(m.id, "assistant", "盗走了"), MessageQuotes.of(m))
        assertEquals(MessageQuotes.of(m), MessageQuotes.decode(MessageQuotes.encode(MessageQuotes.of(m)!!)))
        // Something with no words still has something to show; lines and cards have nothing.
        assertEquals("[语音]", MessageQuotes.of(msg("user", "").copy(audio = """{"file":"v.wav","ms":900}"""))!!.text)
        assertEquals("[图片]", MessageQuotes.of(msg("user", "").copy(images = """[{"file":"a.jpg","width":1,"height":1}]"""))!!.text)
        assertNull(MessageQuotes.of(msg("note", "").copy(note = "记下了待办")))
        assertNull(MessageQuotes.of(msg("user", "给TA看了").copy(note = "给TA看了")))
        assertEquals(MessageQuotes.MAX_TEXT, MessageQuotes.of(msg("user", "字".repeat(500)))!!.text.length)
    }

    @Test
    fun theModelsQuoteFindsTheMessageItMeant() {
        val first = msg("user", "今天好累啊，想早点睡")
        val second = msg("user", "明天还要考试！")
        val third = msg("user", "你吃饭了吗？")
        val recent = listOf(third, second, first) // newest first
        // A few of its words, punctuation and spaces aside.
        assertEquals(first.id, MessageQuotes.find(recent, "想早点睡")?.id)
        assertEquals(second.id, MessageQuotes.find(recent, "明天还要考试")?.id)
        assertEquals(third.id, MessageQuotes.find(recent, " 你吃饭了吗 ")?.id)
        // The whole message and a little more around it still counts.
        assertEquals(third.id, MessageQuotes.find(recent, "「你吃饭了吗？」这句")?.id)
        // The newest of several that match.
        val again = msg("user", "想早点睡")
        assertEquals(again.id, MessageQuotes.find(listOf(again) + recent, "早点睡")?.id)
        // Nothing like it, or nothing at all: no quote.
        assertNull(MessageQuotes.find(recent, "周末去哪"))
        assertNull(MessageQuotes.find(recent, "……"))
    }
}
