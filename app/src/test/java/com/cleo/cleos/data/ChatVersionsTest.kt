package com.cleo.cleos.data

import com.cleo.cleos.data.db.ChatVersionEntity
import com.cleo.cleos.data.db.ChatVersionMessageEntity
import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatVersionsTest {
    private fun line(id: Long, conversation: Long = 4) =
        MessageEntity(id, conversation, "assistant", "message $id", id)

    private fun version(root: Long, group: Long = root, display: Long = root) =
        ChatVersionEntity(root, 4, group, display, ChatVersionEntity.ORIGINAL, "preview", root)

    @Test fun replyVersionStartsAfterTheUserTurnEvenWhenTheModelSentMultipleBubbles() {
        val history = listOf(line(1), line(2).copy(role = "user"), line(3),
            line(4).copy(role = "tool"), line(5))
        assertEquals(2, ChatVersions.replyStart(history, 4))
        assertEquals(1, ChatVersions.replyStart(history, 1))
        assertEquals(4, ChatVersions.replyStart(history.dropLast(1) + line(5).copy(proactive = true), 4))
    }

    @Test fun backupAllowsVersionsInsideAnArchivedBranch() {
        ChatVersions.validateBackup(setOf(4), listOf(line(1), line(6)), listOf(
            version(2), version(6, 2), version(3, display = 4), version(5, 3),
        ), listOf(
            ChatVersionMessageEntity(line(2), 2), ChatVersionMessageEntity(line(3), 2),
            ChatVersionMessageEntity(line(4), 2), ChatVersionMessageEntity(line(5), 5),
        ))
    }

    @Test fun backupRejectsArchivedIdsCollidingWithLiveOrMissingRoots() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatVersions.validateBackup(setOf(4), listOf(line(2)), listOf(version(2)),
                listOf(ChatVersionMessageEntity(line(2), 2)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatVersions.validateBackup(setOf(4), listOf(line(3)), listOf(version(2), version(3, 2)),
                listOf(ChatVersionMessageEntity(line(4), 2)))
        }
    }
}
