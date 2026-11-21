package com.cleo.cleos.data

import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.*
import org.junit.Test

class MessageEditsTest {
    private val user = MessageEntity(id = 3, conversationId = 2, role = "user", content = "原话", createdAt = 100)
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
}
