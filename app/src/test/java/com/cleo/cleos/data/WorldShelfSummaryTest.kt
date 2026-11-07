package com.cleo.cleos.data

import com.cleo.cleos.data.db.WorldArticleEntity
import org.junit.Assert.*
import org.junit.Test

class WorldShelfSummaryTest {
    private fun part(n: Int) = WorldArticleEntity(
        id = n.toLong(), companionId = 1, title = "A Book - Part $n of 3",
        source = "EPUB 提取文字 · A-Book-Part-$n.epub · An Author",
        content = "x".repeat(100), fingerprint = "part-$n", createdAt = n.toLong(),
    )
    private val three get() = (3 downTo 1).map(::part)

    @Test fun oneBookCountsOnceAndRecentUsesRealVolumeTimeWithWholeBookProgress() {
        val input = three.map { it.copy(readOffset = if (it.id == 1L) 100 else 20,
            readAt = if (it.id == 2L) 500 else 100) }
        val summary = WorldShelfSummary.from(1, input)
        assertEquals(1, summary.books.size)
        val recent = summary.recent!!
        assertEquals("A Book", recent.book.title)
        assertEquals(2L, recent.part.id)
        assertEquals(140L, recent.book.readCharacters)
        assertEquals(300L, recent.book.characters)
        assertEquals(input.sortedBy { it.id }, recent.book.parts)
    }

    @Test fun importedUnreadMaterialsDoNotInventARecentRead() {
        val input = three.map { it.copy(readOffset = 12) }
        assertEquals(1, WorldShelfSummary.from(1, input).books.size)
        assertNull(WorldShelfSummary.from(1, input).recent)
        assertTrue(WorldShelfSummary.from(1, emptyList()).books.isEmpty())
    }

    @Test fun latestReadChoosesTheBookRatherThanLatestImportOrHighestVolume() {
        val group = three.map { it.copy(readAt = if (it.id == 1L) 200 else null) }
        val single = part(4).copy(id = 4, title = "Single article", source = "user", readAt = 250)
        assertEquals(4L, WorldShelfSummary.from(1, group + single).recent!!.part.id)
        val newerImport = single.copy(createdAt = 1000, readAt = 150)
        assertEquals(1L, WorldShelfSummary.from(1, group + newerImport).recent!!.part.id)
        val recent = WorldShelfSummary.from(1, listOf(single)).recent!!
        assertFalse(recent.book.grouped)
        assertEquals(single, recent.part)
    }

    @Test fun anotherTaOrOutsideCannotAffectCountTitleOrRecentProgress() {
        val foreign = three.map { it.copy(id = it.id + 10, companionId = 2, readAt = 900, readOffset = 90) }
        val outside = part(8).copy(id = 88, space = "outside", readAt = 1000)
        val all = three + foreign + outside
        val summary = WorldShelfSummary.from(1, all)
        assertEquals(1, summary.books.size)
        assertNull(summary.recent)
        assertTrue(WorldShelfSummary.from(null, all).books.isEmpty())
        assertTrue(WorldShelfSummary.from(9, all).books.isEmpty())
        assertEquals(2L, WorldShelfSummary.from(2, all).recent!!.part.companionId)
    }

    @Test fun missingVolumeKeepsRemainingOriginalTitlesOffsetsAndCount() {
        val input = three.filter { it.id != 2L }.map { it.copy(readOffset = 14, readAt = it.id) }
        val summary = WorldShelfSummary.from(1, input)
        assertEquals(2, summary.books.size)
        assertTrue(summary.books.none { it.grouped })
        assertEquals("A Book - Part 3 of 3", summary.recent!!.book.title)
        assertEquals(input, summary.books.flatMap { it.parts })
        assertEquals(14, summary.recent!!.part.readOffset)
    }
}
