package com.cleo.cleos.ui.world

import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test

class NotebookSerialViewsTest {
    private val first = WorldWritingEntity(1, 7, "one", "story", "第一章", "the first chapter", 10)
    private val second = first.copy(id = 2, runId = "two", title = "第二章", content = "the second chapter", createdAt = 20)
    private val foreign = first.copy(id = 3, companionId = 8, runId = "other", title = "FOREIGN_TITLE")
    private val book = WorldSerialEntity(1, 7, "夜航", 30, 31)
    private val links = listOf(WorldSerialChapterEntity(1, 1, 0), WorldSerialChapterEntity(2, 1, 1), WorldSerialChapterEntity(3, 1, 2))
    private val entries get() = WorldNotebookEntries.all(7, emptyList(), emptyList(), emptyList(), listOf(first, second, foreign))
    @Test fun titleAndChapterBodySearchFindTheBookWithoutChangingChapterOrder() {
        val a = NotebookSerialViews.books(7, listOf(book), links, entries, "夜航").single()
        assertEquals(listOf("writing:1", "writing:2"), a.matches.map { it.key })
        val b = NotebookSerialViews.books(7, listOf(book), links, entries, "SECOND").single()
        assertEquals(listOf("writing:2"), b.matches.map { it.key }); assertEquals(2, b.entries.size)
        assertTrue(NotebookSerialViews.books(7, listOf(book), links, entries, "FOREIGN_TITLE").isEmpty())
        assertTrue(NotebookSerialViews.books(8, listOf(book), links, entries).isEmpty())
    }
    @Test fun filtersAndEmptyBooksRemainPredictable() {
        assertTrue(NotebookSerialViews.books(7, listOf(book), links, entries, "夜航", "draft").isEmpty())
        assertEquals(1, NotebookSerialViews.books(7, listOf(book), emptyList(), entries).size)
        assertTrue(NotebookSerialViews.books(7, listOf(book), emptyList(), entries, "absent").isEmpty())
    }
    @Test fun draftReorderAndRemovalDoNotChangeOriginalRowsAndCanBeDiscarded() {
        val original = listOf(first, second)
        val draft = NotebookSerialDraft(book, original)
        assertFalse(draft.dirty)
        draft.move(1, -1); assertEquals(listOf(second, first), draft.chapters); assertTrue(draft.dirty)
        draft.move(0, -1); assertEquals(listOf(second, first), draft.chapters)
        draft.chapters = listOf(first); draft.title = "改名"
        assertEquals(listOf(first, second), original); assertEquals("夜航", book.title)
        val fresh = NotebookSerialDraft(book, original)
        assertEquals(original, fresh.chapters); assertFalse(fresh.dirty)
    }
}
