package com.cleo.cleos.ui.world

import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test

class NotebookDeckTest {
    private val one = WorldWritingEntity(1, 7, "one", "story", "同题", "First original", 10)
    private val two = one.copy(id = 2, runId = "two", content = "Second original", createdAt = 20)
    private val book = WorldSerialEntity(1, 7, "连载", 30, 30)
    private fun entries(vararg rows: WorldWritingEntity) = WorldNotebookEntries.all(7, emptyList(), emptyList(), emptyList(), rows.toList())
    @Test fun orderedChapterIdentityRemainsDistinctDespiteRepeatedTitlesAndFiltering() {
        val ordered = entries(one).plus(entries(two))
        val cards = NotebookDeckCards.all(emptyList(), listOf(ordered[1]), ordered)
        assertEquals("writing:2", cards.single().key)
        assertTrue(cards.single().kind.startsWith("第 2 章"))
        assertEquals("Second original", cards.single().preview)
    }
    @Test fun bookAndLooseEntryWithSameNumericIdHaveDifferentRoutes() {
        val loose = entries(one)
        val cards = NotebookDeckCards.all(listOf(NotebookBookView(book, emptyList(), emptyList())), loose)
        assertEquals(listOf("serial:1", "writing:1"), cards.map { it.key })
        assertEquals(1L, cards[0].book); assertEquals("", cards[0].entry)
        assertEquals(0L, cards[1].book); assertEquals("writing:1", cards[1].entry)
        assertEquals("0 章 · 打开目录", cards[0].detail)
    }
    @Test fun characterPreviewDoesNotSplitEmojiOrRewriteOriginal() {
        val row = one.copy(content = "x".repeat(239) + "🙂" + "结尾")
        val card = NotebookDeckCards.all(emptyList(), entries(row)).single()
        assertEquals("x".repeat(239), card.preview)
        assertEquals("x".repeat(239) + "🙂结尾", row.content)
    }
    @Test fun insertionBeforeSavedCardStillOpensThatCardAndDeletedCardFallsBackSafely() {
        val keys = listOf("writing:3", "writing:2", "writing:1")
        assertEquals(2, NotebookDeckCards.opening(keys, "writing:1"))
        assertEquals(0, NotebookDeckCards.opening(keys, "writing:999"))
        assertEquals(0, NotebookDeckCards.opening(emptyList(), "writing:1"))
    }
}
