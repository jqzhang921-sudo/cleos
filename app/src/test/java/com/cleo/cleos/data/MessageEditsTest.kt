package com.cleo.cleos.data

import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.db.ConversationEntity
import org.junit.Assert.*
import org.junit.Test

class MessageEditsTest {
    private val user = MessageEntity(id = 3, conversationId = 2, role = "user", content = "原话", createdAt = 100)
    @Test fun branchStopsAtEditedMessageUsingTimeAndId() {
        val rows = listOf(user.copy(id = 4), user.copy(id = 2), user,
            user.copy(id = 1, createdAt = 99), user.copy(id = 5, createdAt = 101), user.copy(conversationId = 9))
        assertEquals(listOf(1L, 2L, 3L), MessageEdits.prefix(rows, user).map { it.id })
        assertEquals("原话", rows.first { it.id == 3L }.content)
    }
    @Test fun onlyTypedUserMessagesAreEditable() {
        assertTrue(MessageEdits.eligible(user.copy(images = "[]", quote = "引用")))
        for (m in listOf(user.copy(role = "assistant"), user.copy(audio = "录音"), user.copy(call = 5),
            user.copy(note = "授权"), user.copy(content = ""), user.copy(content = "[[sticker:猫]]"))) assertFalse(MessageEdits.eligible(m))
    }
    @Test fun staleMissingBlankAndUnchangedEditsAreRejected() {
        assertNotNull(MessageEdits.problem(null, user, "新话"))
        assertNotNull(MessageEdits.problem(user.copy(content = "别人刚改过"), user, "新话"))
        assertNotNull(MessageEdits.problem(user.copy(conversationId = 9), user, "新话"))
        assertNotNull(MessageEdits.problem(user, user, "   "))
        assertNotNull(MessageEdits.problem(user, user, " 原话 "))
        assertNull(MessageEdits.problem(user, user, "新话"))
    }
    @Test fun branchCannotLeakLaterSummaryOrScheduledFollowUps() {
        val conversation = ConversationEntity(id = 2, title = "聊天", createdAt = 1, updatedAt = 1,
            companionId = 9, recap = "后续发生的事", recapUntilAt = 100, recapUntilId = 3,
            followUpAt = 999, followUpMessageId = 4)
        val branch = MessageEdits.branchConversation(conversation, 200)
        assertEquals(0L, branch.id); assertEquals(9L, branch.companionId)
        assertNull(branch.recap); assertNull(branch.recapUntilAt); assertNull(branch.recapUntilId)
        assertNull(branch.followUpAt); assertNull(branch.followUpMessageId)
        assertEquals(200L, branch.updatedAt)
        assertEquals("后续发生的事", conversation.recap)
    }
}
