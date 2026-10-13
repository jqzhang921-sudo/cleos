package com.cleo.cleos.data

import com.cleo.cleos.ai.GroupMemoryRules
import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GroupMemoryBackupTest {
    private val group = ChatGroupEntity(1, "group", 0, 0)
    private val members = listOf(GroupMemberEntity(1, 10, 0, "甲"), GroupMemberEntity(1, 20, 1, "乙"))
    private val source = GroupMessageEntity(5, 1, "user", "星期天给猫取个名字", 0)
    private val note = GroupMemoryEntity(1, 1, "猫的名字", "我们想叫它小饼", 0, 1, 5, "message")
    private fun validate(notes: List<GroupMemoryEntity>) = GroupBackupRules.validate(listOf(group), members, emptyList(), listOf(source), notes)
    @Test fun memoryRoundTripKeepsGroupAndSourceWithoutPrivateMemoryFields() {
        val decoded = Json.decodeFromString<GroupMemoryEntity>(Json.encodeToString(note))
        validate(listOf(decoded)); assertEquals(note, decoded)
        assertFalse(Json.encodeToString(note).contains("companionId"))
        val manual = note.copy(id = 2, sourceMessageId = null, sourceKind = "manual")
        validate(listOf(note, manual))
    }
    @Test fun invalidGroupSourceStatusAndDuplicateIdsAreRejectedBeforeRestore() {
        for (bad in listOf(note.copy(groupId = 2), note.copy(sourceMessageId = 9), note.copy(sourceKind = "private"),
            note.copy(sourceKind = "manual"), note.copy(title = " "), note.copy(content = "x".repeat(GroupMemoryRules.MAX_CONTENT + 1)))) {
            try { validate(listOf(bad)); fail("Invalid note accepted") } catch (_: IllegalArgumentException) { }
        }
        for (bad in listOf(listOf(note, note), listOf(note, note.copy(id = 2)))) {
            try { validate(bad); fail("Duplicate accepted") } catch (_: IllegalArgumentException) { }
        }
        try {
            GroupBackupRules.validate(listOf(group), members, emptyList(), listOf(source.copy(groupId = 2)), listOf(note)); fail()
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun missingOriginalAfterDeletionRetainsCuratedNoteAndSharedGroupLimitApplies() {
        validate(listOf(note.copy(sourceMessageId = null)))
        val oversized = (1L..101L).map { note.copy(id = it, sourceMessageId = null, sourceKind = "manual") }
        try { validate(oversized); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun oldGroupBackupHasNoNotesAndNewBackupRequiresNewReader() {
        val settings = BackupSettings(apiBaseUrl = "https://example.invalid", apiModel = "fixture", aiName = "TA", userName = "我", persona = "",
            historySize = 10, wallpaper = null, glassMode = "clear", wallpaperDark = null, wallpaperHue = null, wallpaperChroma = null)
        val old = BackupFile(BackupFile.FORMAT, 3, 0, settings, emptyList(), emptyList(), emptyList(), emptyList(),
            groups = listOf(group), groupMembers = members, groupMessages = listOf(source))
        val raw = Json.encodeToString(old)
        val decoded = Json.decodeFromString<BackupFile>(raw)
        assertTrue(decoded.groupMemories.isEmpty())
        val newer = old.copy(version = BackupFile.VERSION, groupMemories = listOf(note))
        val restored = Json.decodeFromString<BackupFile>(Json.encodeToString(newer))
        GroupBackupRules.validate(restored.groups, restored.groupMembers, restored.groupTurns, restored.groupMessages, restored.groupMemories)
        assertEquals(listOf(note), restored.groupMemories); assertTrue(BackupFile.VERSION > 3)
    }
}
