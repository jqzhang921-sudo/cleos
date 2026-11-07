package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorldReadingTest {
    private val text = "春天的树。\n风吹过窗外。"
    private val article = WorldArticleEntity(1, 10, "小文章", "合成作者", text, WorldReading.fingerprint(text), 1, text.length, 2)
    private val note = WorldNoteEntity(1, 10, 1, "run-one", "想留下一句风。", "风从哪里来？", "树", 3, 0, text.length, 2)
    private fun validate(a: List<WorldArticleEntity> = listOf(article), n: List<WorldNoteEntity> = listOf(note)) = WorldReading.validateBackup(setOf(10, 20), a, n)
    @Test fun normalizedSourceHasStableVersionAndSegmentsDoNotSplitSurrogates() {
        assertEquals("a\nb", WorldReading.normalize(" \r\na\r\nb\r "))
        val body = "x".repeat(2_999) + "😺" + "y".repeat(3_050)
        val pieces = mutableListOf<String>(); var start = 0
        while (start < body.length) {
            val end = WorldReading.end(body, start)
            assertTrue(WorldReading.boundary(body, start)); assertTrue(WorldReading.boundary(body, end)); assertTrue(end > start)
            pieces += body.substring(start, end); start = end
        }
        assertEquals(body, pieces.joinToString("")); assertEquals(2_999, pieces.first().length)
        assertNotEquals(WorldReading.fingerprint(body), WorldReading.fingerprint(body + "!"))
    }
    @Test fun backupRoundTripKeepsExactSourceAndBacklinks() {
        validate()
        val restored = Json.decodeFromString<WorldArticleEntity>(Json.encodeToString(article))
        val restoredNote = Json.decodeFromString<WorldNoteEntity>(Json.encodeToString(note))
        validate(listOf(restored), listOf(restoredNote)); assertEquals(article, restored); assertEquals(note, restoredNote)
        assertTrue(BackupFile.VERSION > 15)
    }
    @Test fun wrongOwnerMissingSourceForgedQuoteAndUnreadRangeAreRejected() {
        for (bad in listOf(note.copy(companionId = 20), note.copy(articleId = 9), note.copy(quote = "海"), note.copy(quoteStart = 4), note.copy(toOffset = text.length + 1), note.copy(fromOffset = 5), note.copy(content = "x".repeat(4_001)))) {
            try { validate(n = listOf(bad)); fail("Bad note accepted") } catch (_: IllegalArgumentException) { }
        }
        try { validate(a = listOf(article.copy(readOffset = 3))); fail() } catch (_: IllegalArgumentException) { }
        try { validate(a = listOf(article.copy(content = text + "bad"))); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun duplicateIdsSourceVersionsAndRunIdsAreRejected() {
        for (pair in listOf(listOf(article, article), listOf(article, article.copy(id = 2)))) {
            try { validate(a = pair); fail() } catch (_: IllegalArgumentException) { }
        }
        try { validate(n = listOf(note, note.copy(id = 2))); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun emptyLegacyWorldIsAcceptedAndNotesNeedPublicWriting() {
        validate(emptyList(), emptyList())
        validate(n = listOf(note.copy(content = "", question = "还想知道")))
        try { validate(n = listOf(note.copy(content = "", question = ""))); fail() } catch (_: IllegalArgumentException) { }
    }
}
