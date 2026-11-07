package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorldWritingDataTest {
    private val row = WorldWritingEntity(1, 7, "write-1", "story", "A story", "A little boat 🌙", 10)
    private fun raw(action: String = "write", kind: String = "story", title: String = "A story", body: String = "A boat 🌙") = buildJsonObject {
        put("action", action); put("kind", kind); put("title", title); put("content", body)
    }.toString()
    @Test fun acceptsDisplayableKindsButPauseCannotCarryHiddenSavedText() {
        for (kind in WorldWritingData.kinds.keys) assertEquals(kind, WorldWritingData.result(raw(kind = kind))!!.kind)
        assertNull(WorldWritingData.result(raw("pause", "", "", "")))
        rejected { WorldWritingData.result(raw("pause", "", "", "hidden")) }
        rejected { WorldWritingData.result(raw(kind = "thought")) }
    }
    @Test fun rejectsReasoningExtraFieldsAndOrdinaryProse() {
        for (text in listOf("My thoughts", raw().dropLast(1) + ",\"reasoning\":\"secret\"}", raw(body = ""), "[]", raw(action = "read"))) rejected { WorldWritingData.result(text) }
    }
    @Test fun checksActualLengthsControlsAndUnicodeWithoutSilentlyTruncating() {
        assertNotNull(WorldWritingData.result(raw(title = "", body = "x".repeat(4000))))
        for (body in listOf("x".repeat(4001), "a\u0000b", "\uD800", "\uDC00")) rejected { WorldWritingData.result(raw(body = body)) }
        rejected { WorldWritingData.result(raw(title = "x".repeat(121))) }
        rejected { WorldWritingData.result(raw(title = "a\nb")) }
    }
    @Test fun rejectsWrongOwnerDuplicateIdsRunsAndInvalidStoredContent() {
        WorldWritingData.validate(setOf(7), listOf(row))
        for (rows in listOf(listOf(row.copy(companionId = 8)), listOf(row, row.copy(runId = "second")), listOf(row, row.copy(id = 2)), listOf(row.copy(content = " ")), listOf(row.copy(id = 0)))) rejected { WorldWritingData.validate(setOf(7), rows) }
        val note = WorldNoteEntity(1, 7, 2, row.runId, "note", "", "", null, 0, 1, 1)
        rejected { WorldWritingData.validate(setOf(7), listOf(row), listOf(note)) }
    }
    @Test fun sharesTheWorldRecordCapacityWithoutDeletingOldRecords() {
        val notes = (1..500).map { WorldNoteEntity(it.toLong(), 7, 2, "reading-$it", "note", "", "", null, 0, 1, 1) }
        WorldWritingData.validate(setOf(7), emptyList(), notes)
        rejected { WorldWritingData.validate(setOf(7), listOf(row), notes) }
    }
    @Test fun oldBackupsDefaultEmptyAndNewBackupsRoundTripCreativeTextOnly() {
        val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val settings = BackupSettings(apiBaseUrl = "https://synthetic.invalid/v1", apiModel = "synthetic", aiName = "TA", userName = "User", persona = "", historySize = 10,
            wallpaper = null, glassMode = "liquid", wallpaperDark = null, wallpaperHue = null, wallpaperChroma = null)
        val backup = BackupFile(BackupFile.FORMAT, BackupFile.VERSION, 1, settings, emptyList(), emptyList(), emptyList(), emptyList(), worldWritings = listOf(row))
        val text = codec.encodeToString(BackupFile.serializer(), backup)
        assertEquals(listOf(row), codec.decodeFromString(BackupFile.serializer(), text).worldWritings)
        val old = Json.parseToJsonElement(text).jsonObject.toMutableMap().apply { remove("worldWritings"); put("version", JsonPrimitive(24)) }
        assertTrue(codec.decodeFromString(BackupFile.serializer(), JsonObject(old).toString()).worldWritings.isEmpty())
        assertFalse(text.contains("contextHash")); assertFalse(text.contains("worldWritingSchedules")); assertTrue(BackupFile.VERSION >= 26)
    }
    private fun rejected(block: () -> Unit) { try { block(); fail("Invalid creative record was accepted") } catch (_: IllegalArgumentException) { } }
}
