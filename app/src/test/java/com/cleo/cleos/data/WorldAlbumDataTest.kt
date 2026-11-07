package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorldAlbumDataTest {
    private val photo = WorldAlbumPhotoEntity(1, 10, "album-00000000-0000-0000-0000-000000000001.jpg", 400, 300, "0".repeat(64), "Private caption", 1)
    private val look = WorldAlbumLookEntity(1, 10, 1, photo.sha256, "album-run", "A view", "What is next?", 2)
    private fun invalid(p: List<WorldAlbumPhotoEntity> = listOf(photo), l: List<WorldAlbumLookEntity> = listOf(look)) {
        try { WorldAlbumData.validate(setOf(10, 20), p, l); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun albumKeepsItsOwnOwnerAndPixelVersion() {
        WorldAlbumData.validate(setOf(10), listOf(photo), listOf(look))
        for (bad in listOf(photo.copy(id = 0), photo.copy(companionId = 30), photo.copy(file = "../private.jpg"), photo.copy(file = "world-00000000-0000-0000-0000-000000000001.jpg"), photo.copy(width = 961), photo.copy(sha256 = "bad"), photo.copy(caption = "x".repeat(501)))) invalid(p = listOf(bad))
        for (bad in listOf(look.copy(id = 0), look.copy(companionId = 20), look.copy(photoId = 2), look.copy(photoSha256 = "1".repeat(64)), look.copy(runId = ""), look.copy(createdAt = 0), look.copy(content = "x".repeat(4001)))) invalid(l = listOf(bad))
        invalid(p = emptyList()); invalid(l = listOf(look, look))
    }
    @Test fun samePixelsRequireSeparateCopiesForSeparateTas() {
        val other = photo.copy(id = 2, companionId = 20, file = "album-00000000-0000-0000-0000-000000000002.jpg")
        WorldAlbumData.validate(setOf(10, 20), listOf(photo, other), listOf(look))
        invalid(p = listOf(photo, other.copy(file = photo.file)))
        invalid(l = listOf(look.copy(companionId = 20)))
    }
    @Test fun cappedAlbumAndCombinedRecordBudgetRejectMalformedBackup() {
        val full = (1..50).map { photo.copy(id = it.toLong(), file = "album-00000000-0000-0000-0000-${it.toString().padStart(12, '0')}.jpg") }
        WorldAlbumData.validate(setOf(10), full, emptyList())
        invalid(p = full + photo.copy(id = 51, file = "album-00000000-0000-0000-0000-000000000051.jpg"))
        val records = (1..500).map { look.copy(id = it.toLong(), runId = "album-$it") }
        WorldAlbumData.validate(setOf(10), listOf(photo), records)
        val note = WorldNoteEntity(1, 10, 1, "public", "note", "", "", null, 0, 1, 1)
        try { WorldAlbumData.validate(setOf(10), listOf(photo), records, listOf(note)); fail() } catch (_: IllegalArgumentException) { }
        try { WorldAlbumData.validate(setOf(10), listOf(photo), listOf(look), listOf(note.copy(runId = look.runId))); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun backupRoundTripIsTypedAndNeverExportsPermission() {
        assertEquals(photo, Json.decodeFromString<WorldAlbumPhotoEntity>(Json.encodeToString(photo)))
        assertEquals(look, Json.decodeFromString<WorldAlbumLookEntity>(Json.encodeToString(look)))
        val raw = Json.encodeToString(listOf(photo)) + Json.encodeToString(listOf(look))
        assertFalse(raw.contains("uri", ignoreCase = true)); assertFalse(raw.contains("permission", ignoreCase = true))
        assertTrue(BackupFile.VERSION >= 24)
    }
}
