package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GroupBackupTest {
    private val group = ChatGroupEntity(1, "一起聊", 0, 0, draft = "没发的话", mentions = "[10]")
    private val members = listOf(GroupMemberEntity(1, 10, 0, "甲", "message-test.jpg"), GroupMemberEntity(1, 20, 1, "乙"))
    private val user = GroupMessageEntity(1, 1, "user", "你好", 0)
    private val reply = GroupMessageEntity(2, 1, "assistant", "你好呀", 0, speakerId = 10, turnId = 1)
    private val turn = GroupTurnEntity(1, 1, 1, "[10]", 0, "completed")
    private val settings = BackupSettings(apiBaseUrl = "https://example.com", apiModel = "test", aiName = "TA", userName = "用户",
        persona = "", historySize = 40, wallpaper = null, glassMode = "Auto", wallpaperDark = null, wallpaperHue = null, wallpaperChroma = null)

    @Test fun oldBackupHasNoGroupsAndKeepsPrivateConversations() {
        val backup = BackupFile(BackupFile.FORMAT, 1, 0, settings, listOf(ConversationEntity(5, "私聊", 0, 0)), emptyList(), emptyList(), emptyList())
        val encoded = Json.encodeToString(backup)
        val restored = Json.decodeFromString<BackupFile>(encoded)
        assertTrue(restored.groups.isEmpty()); assertTrue(restored.groupMessages.isEmpty())
        assertEquals(5L, restored.conversations.single().id)
        GroupBackupRules.validate(restored.groups, restored.groupMembers, restored.groupTurns, restored.groupMessages)
    }
    @Test fun groupBackupRoundTripPreservesSpeakersDraftsAndDeletedTaSnapshots() {
        // Historical members may have no matching CompanionEntity after the TA was removed.
        val backup = BackupFile(BackupFile.FORMAT, BackupFile.VERSION, 0, settings, emptyList(), emptyList(), emptyList(), emptyList(),
            groups = listOf(group), groupMembers = members, groupTurns = listOf(turn), groupMessages = listOf(user, reply))
        val restored = Json.decodeFromString<BackupFile>(Json.encodeToString(backup))
        GroupBackupRules.validate(restored.groups, restored.groupMembers, restored.groupTurns, restored.groupMessages)
        assertEquals(backup, restored); assertEquals(15, BackupFile.VERSION)
    }
    @Test fun invalidCrossGroupOrDuplicateReplyIsRejectedBeforeRestore() {
        for (bad in listOf(listOf(user, reply.copy(groupId = 99)), listOf(user, reply, reply.copy(id = 3)), listOf(user, reply.copy(speakerId = 99)))) {
            try { GroupBackupRules.validate(listOf(group), members, listOf(turn), bad); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun unsafeAvatarsAndMissingAnchorAreRejected() {
        try { GroupBackupRules.validate(listOf(group), members.map { it.copy(avatar = "../private") }, listOf(turn), listOf(user, reply)); fail() }
        catch (_: IllegalArgumentException) { }
        try { GroupBackupRules.validate(listOf(group), members, listOf(turn.copy(userMessageId = 88)), listOf(user, reply)); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun pendingRestoreDoesNotAutomaticallySpendAnotherRequest() {
        assertEquals("interrupted", GroupBackupRules.restored(reply.copy(status = "running")).status)
        assertEquals("interrupted", GroupBackupRules.restored(turn.copy(status = "running")).status)
        assertEquals("sent", GroupBackupRules.restored(reply).status)
        assertEquals("completed", GroupBackupRules.restored(turn).status)
    }
    @Test fun malformedMentionMetadataCannotSilentlyDisappearOnRestore() {
        try { GroupBackupRules.validate(listOf(group.copy(mentions = "broken")), members, listOf(turn), listOf(user, reply)); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun v2MembersWithoutTheNewFlagDefaultToStillParticipating() {
        val restored = Json.decodeFromString<GroupMemberEntity>("""{"groupId":1,"companionId":10,"position":0,"name":"甲"}""")
        assertTrue(restored.active)
        val backup = BackupFile(BackupFile.FORMAT, 2, 0, settings, emptyList(), emptyList(), emptyList(), emptyList(),
            groups = listOf(group), groupMembers = members, groupTurns = listOf(turn), groupMessages = listOf(user, reply))
        val decoded = Json.decodeFromString<BackupFile>(Json.encodeToString(backup))
        assertTrue(decoded.groupMembers.all { it.active })
        GroupBackupRules.validate(decoded.groups, decoded.groupMembers, decoded.groupTurns, decoded.groupMessages)
    }
    @Test fun leftMemberAndTheirOldReplySurviveBackupWithMoreThanFourHistoricalMembers() {
        val roster = members.map { it.copy(active = false) } + (30L..33L).mapIndexed { index, id -> GroupMemberEntity(1, id, index + 2, "新伙伴$id") }
        val backup = BackupFile(BackupFile.FORMAT, 3, 0, settings, emptyList(), emptyList(), emptyList(), emptyList(),
            groups = listOf(group.copy(mentions = "[]")), groupMembers = roster, groupTurns = listOf(turn), groupMessages = listOf(user.copy(mentions = "[10]"), reply))
        val restored = Json.decodeFromString<BackupFile>(Json.encodeToString(backup))
        GroupBackupRules.validate(restored.groups, restored.groupMembers, restored.groupTurns, restored.groupMessages)
        assertFalse(restored.groupMembers.first().active)
        assertEquals("message-test.jpg", restored.groupMembers.first().avatar)
        assertEquals(10L, restored.groupMessages.last().speakerId)
    }
    @Test fun draftCannotMentionAnInactiveMemberButPastMessagesCan() {
        val roster = members.map { it.copy(active = false) } + listOf(GroupMemberEntity(1, 30, 2, "丙"), GroupMemberEntity(1, 40, 3, "丁"))
        GroupBackupRules.validate(listOf(group.copy(mentions = "[]")), roster, listOf(turn), listOf(user.copy(mentions = "[10]"), reply))
        try { GroupBackupRules.validate(listOf(group), roster, listOf(turn), listOf(user, reply)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun inactiveMembersCannotDisguiseAnInvalidActiveRoster() {
        for (roster in listOf(members.map { it.copy(active = false) }, members + (30L..32L).mapIndexed { index, id -> GroupMemberEntity(1, id, index + 2, "TA$id") })) {
            try { GroupBackupRules.validate(listOf(group), roster, listOf(turn), listOf(user, reply)); fail() } catch (_: IllegalArgumentException) { }
        }
    }
}
