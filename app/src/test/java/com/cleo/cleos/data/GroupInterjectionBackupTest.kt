package com.cleo.cleos.data

import com.cleo.cleos.ai.*
import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GroupInterjectionBackupTest {
    private val members = listOf(10L, 20L, 30L).mapIndexed { index, id -> GroupMemberEntity(1, id, index, "同名") }
    private val user = GroupMessageEntity(1, 1, "user", "fixture", 0)
    private val turn = GroupTurnEntity(1, 1, 1, "[10,20,30]", 0, "completed")
    private val messages = listOf(user) + members.mapIndexed { index, m -> GroupMessageEntity(index + 2L, 1, "assistant", "reply$index", 0,
        speakerId = m.companionId, speakerName = m.name, turnId = 1) }

    @Test fun oldGroupsAndNewGroupsDefaultToNoAdditionalRequests() {
        val old = Json.decodeFromString<ChatGroupEntity>("""{"id":1,"title":"old","createdAt":0,"updatedAt":0}""")
        assertFalse(old.allowInterjections); assertFalse(ChatGroupEntity(1, "new", 0, 0).allowInterjections)
    }

    @Test fun settingAndThreeDistinctSpeakersRoundTripWithoutDependingOnNames() {
        val group = ChatGroupEntity(1, "fixture", 0, 0, allowInterjections = true)
        val decoded = Json.decodeFromString<ChatGroupEntity>(Json.encodeToString(group))
        assertTrue(decoded.allowInterjections)
        val trace = GroupToolRules.encode(GroupToolTrace(listOf(GroupToolRecord("invite1", GroupInterjectionRules.TOOL,
            """{"member_id":30}""", "returned", "pending until completed"))))
        val records = messages.map { if (it.speakerId == 20L) it.copy(toolTrace = trace) else it }
        GroupBackupRules.validate(listOf(decoded), members, listOf(turn), records)
        assertEquals(15, BackupFile.VERSION)
    }

    @Test fun restoredPendingInvitationNeverBecomesAnAutomaticRequest() {
        val interrupted = GroupBackupRules.restored(messages.last().copy(status = "pending"))
        assertEquals("interrupted", interrupted.status)
        assertEquals("interrupted", GroupBackupRules.restored(turn.copy(status = "running")).status)
        val group = ChatGroupEntity(1, "fixture", 0, 0, allowInterjections = true)
        GroupBackupRules.validate(listOf(group), members, listOf(turn.copy(status = "interrupted")), messages.dropLast(1) + interrupted)
    }

    @Test fun duplicateOrForeignHandoffCannotPassRestoreValidation() {
        val group = ChatGroupEntity(1, "fixture", 0, 0, allowInterjections = true)
        for (bad in listOf(messages.dropLast(1) + messages.last().copy(speakerId = 20),
            messages.dropLast(1) + messages.last().copy(groupId = 2))) {
            try { GroupBackupRules.validate(listOf(group), members, listOf(turn), bad); fail() } catch (_: IllegalArgumentException) { }
        }
    }
}
