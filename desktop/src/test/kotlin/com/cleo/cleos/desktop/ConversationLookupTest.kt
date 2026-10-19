package com.cleo.cleos.desktop

import org.junit.Assert.*
import org.junit.Test

class ConversationLookupTest {
    @Test fun `search uses visible literal text newest first with bounded contextual excerpts`() {
        val records=(0..65).map { Message(id="m$it",text="普通开头".repeat(80)+" Needle[cat] 位于长消息后部 $it",reasoning="仅思考中的秘密") }
        val chat=Conversation(memberIds=listOf("a"),messages=records+Message(text="Needle[cat] 内部通知",notice=true))
        val hits=searchConversation(chat,"  needle[CAT]  ")
        assertEquals(50,hits.size);assertEquals("m65",hits.first().message.id);assertEquals("m16",hits.last().message.id)
        assertTrue(hits.all {it.excerpt.contains("Needle[cat]") && it.excerpt.length<=182})
        assertTrue(searchConversation(chat,"仅思考中的秘密").isEmpty())
        assertTrue(searchConversation(chat,"   ").isEmpty())
        assertTrue(searchConversation(Conversation(memberIds=listOf("b")),"Needle[cat]").isEmpty())
    }
    @Test fun `recent media is bounded to this conversation and excludes drafts notices and unknown kinds`() {
        val records=(0..30).map {i->Message(id="m$i",attachments=listOf(Attachment("media/$i.png"),Attachment("media/$i.wav","audio"),Attachment("media/$i.dat","unknown"))) }
        val chat=Conversation(memberIds=listOf("a"),messages=records+Message(notice=true,attachments=listOf(Attachment("media/notice.png"))),draftImages=listOf(Attachment("media/draft.png")))
        val media=recentConversationMedia(chat)
        assertEquals(24,media.size);assertEquals("m30",media.first().message.id);assertEquals("media/30.wav",media.first().attachment.file)
        assertFalse(media.any {it.attachment.file.contains("draft") || it.attachment.file.contains("notice") || it.attachment.kind=="unknown"})
        assertTrue(recentConversationMedia(chat.copy(messages=emptyList())).isEmpty())
    }
}
