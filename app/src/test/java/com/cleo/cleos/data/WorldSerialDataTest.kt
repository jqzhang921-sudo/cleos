package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorldSerialDataTest {
    private val one = WorldWritingEntity(1, 7, "one", "story", "同题", "First story", 10)
    private val two = one.copy(id = 2, runId = "two", content = "Second story", createdAt = 20)
    private val other = one.copy(id = 3, companionId = 8, runId = "other", title = "OTHER_OWNER_SECRET")
    private val book = WorldSerialEntity(1, 7, "一段夜航", 30, 31)
    private val links = listOf(WorldSerialChapterEntity(2, 1, 0), WorldSerialChapterEntity(1, 1, 1))
    @Test fun orderIsExplicitAndIndependentOfTitleOrCreationTime() {
        WorldSerialData.validate(setOf(7, 8), listOf(book), links, listOf(one, two, other))
        assertEquals(listOf(two, one), WorldSerialData.ordered(book, links.reversed(), listOf(one, two)))
        assertEquals("First story", one.content); assertEquals("Second story", two.content)
    }
    @Test fun rejectsForeignOrMissingOwnersBooksAndWritings() {
        reject { WorldSerialData.validate(setOf(8), listOf(book), links, listOf(one, two)) }
        reject { WorldSerialData.validate(setOf(7, 8), listOf(book), listOf(WorldSerialChapterEntity(3, 1, 0)), listOf(other)) }
        reject { WorldSerialData.validate(setOf(7), listOf(book), listOf(WorldSerialChapterEntity(9, 1, 0)), listOf(one)) }
        reject { WorldSerialData.validate(setOf(7), emptyList(), links, listOf(one, two)) }
    }
    @Test fun eachWritingAndPositionCanOccurOnlyOnce() {
        reject { WorldSerialData.validate(setOf(7), listOf(book, book.copy(id = 2)), listOf(links[0], links[0].copy(serialId = 2)), listOf(two)) }
        reject { WorldSerialData.validate(setOf(7), listOf(book), links.map { it.copy(position = 0) }, listOf(one, two)) }
        reject { WorldSerialData.validate(setOf(7), listOf(book, book), links, listOf(one, two)) }
    }
    @Test fun deletingAChapterMayLeaveAGapOrAnEmptyBookWithoutInvalidatingBackup() {
        WorldSerialData.validate(setOf(7), listOf(book), listOf(links[1]), listOf(one))
        WorldSerialData.validate(setOf(7), listOf(book), emptyList(), emptyList())
        reject { WorldSerialData.validate(setOf(7), listOf(book), listOf(links[1].copy(position = -1)), listOf(one)) }
    }
    @Test fun displayProjectionNeverExposesForeignWritingEvenForForgedLink() {
        assertEquals(listOf(one), WorldSerialData.ordered(book, listOf(WorldSerialChapterEntity(3, 1, 0), WorldSerialChapterEntity(1, 1, 1)), listOf(one, other)))
    }
    @Test fun titlesSupportUnicodeButRejectMalformedOrControlCharacters() {
        assertTrue(WorldSerialData.validTitle("夜航 👩‍🚀"))
        listOf("", " ", " x", "x\n", "x".repeat(81), "\uD83D", "\uDE42").forEach { assertFalse(WorldSerialData.validTitle(it)) }
    }
    @Test fun backupRoundTripsTheOrderAndOldBackupDefaultsToNoSerials() {
        val settings = BackupSettings(apiBaseUrl = "https://synthetic.invalid/v1", apiModel = "synthetic", aiName = "TA", userName = "User", persona = "", historySize = 10,
            wallpaper = null, glassMode = "Auto", wallpaperDark = null, wallpaperHue = null, wallpaperChroma = null)
        val data = BackupFile(BackupFile.FORMAT, BackupFile.VERSION, 1, settings, emptyList(), emptyList(), emptyList(), emptyList(),
            worldWritings = listOf(one, two), worldSerials = listOf(book), worldSerialChapters = links)
        val codec = Json { encodeDefaults = true }
        val raw = codec.encodeToString(BackupFile.serializer(), data)
        assertEquals(data, codec.decodeFromString(BackupFile.serializer(), raw))
        val old = Json.parseToJsonElement(raw).jsonObject.toMutableMap().apply {
            remove("worldSerials"); remove("worldSerialChapters"); put("version", JsonPrimitive(28))
        }
        val read = codec.decodeFromString(BackupFile.serializer(), JsonObject(old).toString())
        assertTrue(read.worldSerials.isEmpty()); assertTrue(read.worldSerialChapters.isEmpty()); assertEquals(listOf(one, two), read.worldWritings)
        assertEquals(29, BackupFile.VERSION)
    }
    private fun reject(block: () -> Unit) { try { block(); fail("Invalid serial was accepted") } catch (_: IllegalArgumentException) { } }
}
