package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test

class WorldShelfDataTest {
    private val text = "A small book 🌊 and another paragraph."
    private val article = WorldArticleEntity(id = 7, companionId = 1, title = "Fixture", source = "", content = text, fingerprint = WorldReading.fingerprint(text), createdAt = 1)
    private val mark = WorldBookmarkEntity(2, 1, 7, article.fingerprint, 2, "回看", 2)
    @Test fun bookmarksNeedNotBeInsideTaReadProgress() {
        WorldShelfData.validateBackup(setOf(1), listOf(article), listOf(mark), emptyList())
        assertEquals(0, article.readOffset)
    }
    @Test fun rejectsWrongOwnerVersionOffsetAndDuplicatePositions() {
        listOf(mark.copy(companionId = 2), mark.copy(fingerprint = "wrong"), mark.copy(offset = -1), mark.copy(offset = text.length),
            mark.copy(offset = text.indexOf("🌊") + 1), mark.copy(label = "bad\nlabel")).forEach { bad ->
            try { WorldShelfData.validateBackup(setOf(1, 2), listOf(article), listOf(bad), emptyList()); fail("accepted $bad") } catch (_: IllegalArgumentException) { }
        }
        try { WorldShelfData.validateBackup(setOf(1), listOf(article), listOf(mark, mark.copy(id = 3)), emptyList()); fail("duplicate position") } catch (_: IllegalArgumentException) { }
    }
    @Test fun validatesMagazineMetadataWithoutInventingItsPublication() {
        assertTrue(WorldShelfData.valid("magazine", "自然杂志", "2026 第 2 期"))
        assertTrue(WorldShelfData.valid("book", "", ""))
        assertFalse(WorldShelfData.valid("magazine", "", "2"))
        assertFalse(WorldShelfData.valid("book", "series", "2"))
        val m = WorldShelfEntity(7, 1, "magazine", "自然", "第 2 期")
        WorldShelfData.validateBackup(setOf(1), listOf(article), emptyList(), listOf(m))
        try { WorldShelfData.validateBackup(setOf(1, 2), listOf(article), emptyList(), listOf(m.copy(companionId = 2))); fail("wrong owner") } catch (_: IllegalArgumentException) { }
    }
    @Test fun numericIssueSortingHandlesLongNumbersAndKeepsZeroEquivalence() {
        val list = listOf("第 10 期", "第 2 期", "第 1 期").sortedWith(WorldShelfData::compareIssues)
        assertEquals(listOf("第 1 期", "第 2 期", "第 10 期"), list)
        assertEquals(0, WorldShelfData.compareIssues("002", "2"))
        assertTrue(WorldShelfData.compareIssues("9".repeat(40), "1".repeat(41)) < 0)
    }
    @Test fun previewDoesNotSplitSurrogateOrRewriteSource() {
        val source = "a".repeat(119) + "🌊" + "tail"
        assertEquals("a".repeat(119), WorldShelfData.preview(source, 0))
        assertTrue(WorldShelfData.preview(source, 119).startsWith("🌊"))
        assertEquals("a".repeat(119) + "🌊tail", source)
    }
    @Test fun oldBackupFieldsDefaultToEmptyAndFormatProtectsOlderReaders() {
        assertTrue(BackupFile.VERSION >= 24)
        WorldShelfData.validateBackup(setOf(1), listOf(article), emptyList(), emptyList())
    }
    @Test fun backupSummaryIncludesActualNewRecords() {
        val summary = BackupSummary(1, 0, 0, 0, 0, 0, 0, worldBookmarks = 2, worldShelf = 3).toString()
        assertTrue(summary.contains("2 枚书签")); assertTrue(summary.contains("3 条读物整理信息"))
    }
}
