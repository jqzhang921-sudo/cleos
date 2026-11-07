package com.cleo.cleos.ui.world

import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test

class WorldNotebookEntriesTest {
    @Test fun creativeEntriesHaveNoInventedSourceAndAreOwnedSearchableAndSeparatedByKind() {
        val writing = WorldWritingEntity(1, 7, "free", "story", "A boat", "Creative text", 40)
        val entries = WorldNotebookEntries.all(7, listOf(note), listOf(look), listOf(book, outside), listOf(writing, writing.copy(id = 2, kind = "draft"), writing.copy(id = 3, companionId = 8, content = "OTHER_OWNER")))
        assertEquals(2, WorldNotebookEntries.select(entries, "", "writing").size)
        assertEquals("writing:1", WorldNotebookEntries.select(entries, "boat", "story").single().key)
        assertEquals("writing:2", WorldNotebookEntries.select(entries, "草稿", "draft").single().key)
        assertNull(entries.first().source); assertEquals(0L, entries.first().article)
        assertTrue(WorldNotebookEntries.select(entries, "OTHER_OWNER", "all").isEmpty())
        assertEquals(1, WorldNotebookEntries.select(entries, "", "reading").size)
    }
    private val book = WorldArticleEntity(3, 7, "合成读物", "TXT", "原文", "fingerprint", 1)
    private val outside = book.copy(id = 4, title = "公开风景", space = "outside")
    private val note = WorldNoteEntity(1, 7, 3, "run-note", "A quiet morning", "为什么有风？", "原文", 0, 0, 2, 20)
    private val look = WorldPictureLookEntity(1, 7, 4, "sha", "run-look", "看见山间的颜色", "", 30)
    @Test fun mixesBothKindsInStableTimeOrderWithoutChangingInput() {
        val notes = listOf(note, note.copy(id = 2, createdAt = 30))
        val entries = WorldNotebookEntries.all(7, notes, listOf(look), listOf(book, outside))
        assertEquals(listOf("look:1", "note:2", "note:1"), entries.map { it.key })
        assertEquals(note, entries.last().note)
        assertEquals(book, entries.last().source)
    }
    @Test fun filtersQuestionsAcrossKindsAndCombinesWithSearch() {
        val entries = WorldNotebookEntries.all(7, listOf(note), listOf(look.copy(question = "山是什么颜色？")), listOf(book, outside))
        assertEquals(2, WorldNotebookEntries.select(entries, "", "questions").size)
        assertEquals(listOf("note:1"), WorldNotebookEntries.select(entries, "", "reading").map { it.key })
        assertEquals(listOf("look:1"), WorldNotebookEntries.select(entries, "", "picture").map { it.key })
        assertEquals(1, WorldNotebookEntries.select(entries, "山", "questions").size)
        assertTrue(WorldNotebookEntries.select(entries, "morning", "picture").isEmpty())
    }
    @Test fun searchesTitleQuoteQuestionAndCaseInsensitivelyButNotAcrossFieldBoundaries() {
        val entries = WorldNotebookEntries.all(7, listOf(note), listOf(look), listOf(book, outside))
        for (term in listOf(" quiet ", "合成读物", "原文", "为什么", "阅读笔记")) assertEquals("note:1", WorldNotebookEntries.select(entries, term, "all").single().key)
        assertEquals("look:1", WorldNotebookEntries.select(entries, "看图记录", "all").single().key)
        assertTrue(WorldNotebookEntries.select(entries, "morning为什么", "all").isEmpty())
    }
    @Test fun otherTaRecordsAndSourceTitlesCannotLeakIntoResults() {
        val entries = WorldNotebookEntries.all(7, listOf(note, note.copy(id = 9, companionId = 8, content = "另一位的秘密")), listOf(look.copy(companionId = 8)), listOf(book.copy(companionId = 8, title = "另一位的来源")))
        assertEquals(1, entries.size); assertNull(entries.single().source)
        assertTrue(WorldNotebookEntries.select(entries, "秘密", "all").isEmpty())
        assertTrue(WorldNotebookEntries.select(entries, "另一位的来源", "all").isEmpty())
    }
    @Test fun noOwnerIsEmptyAndUnknownFilterRemainsUsable() {
        assertTrue(WorldNotebookEntries.all(null, listOf(note), listOf(look), listOf(book)).isEmpty())
        val entries = WorldNotebookEntries.all(7, listOf(note.copy(question = " ")), emptyList(), listOf(book))
        assertTrue(WorldNotebookEntries.select(entries, "", "questions").isEmpty())
        assertEquals(entries, WorldNotebookEntries.select(entries, "", "future-filter"))
    }
}
