package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test

class WorldOutsideSelectionTest {
    private fun article(id: Long, owner: Long = 1, source: String = "NASA") = WorldArticleEntity(
        id = id, companionId = owner, title = "Entry $id", source = source, content = "text", fingerprint = "v$id", createdAt = id, space = "outside")
    private fun note(id: Long, article: Long, owner: Long = 1) = WorldNoteEntity(id, owner, article, "run$id", "note$id", "", "", null, 0, 4, id)
    private fun plan(rows: List<WorldArticleEntity>, notes: List<WorldNoteEntity> = emptyList(), ids: List<Long> = listOf(1)) =
        WorldOutsideSelection.removal(1, 10, 20, ids, rows, notes, emptyList(), emptyList())

    @Test fun sourceFiltersNeverIncludeAnotherTaOrTheStudy() {
        val a = article(1); val b = article(2, source = "维基百科")
        val all = listOf(a, b, article(3, owner = 2), article(4).copy(space = "study"))
        assertEquals(listOf(a, b), WorldOutsideSelection.rows(1, all))
        assertEquals(listOf(a), WorldOutsideSelection.rows(1, all, "NASA"))
        assertTrue(WorldOutsideSelection.rows(null, all).isEmpty())
        assertTrue(WorldOutsideSelection.rows(1, all, "Other source").isEmpty())
    }

    @Test fun selectAllOrReadOnlyUsesTheCurrentListWithoutExpandingHiddenIds() {
        val a = article(1).copy(readOffset = 4); val b = article(2).copy(readOffset = 3)
        val empty = article(3).copy(content = "", readOffset = 0)
        assertEquals(listOf(1L), WorldOutsideSelection.readIds(listOf(a, b, empty)))
        assertEquals(listOf(b), WorldOutsideSelection.selected(listOf(b), listOf(1, 2, 9)))
        assertEquals(listOf(a, b), WorldOutsideSelection.selected(listOf(a, b), listOf(2, 1, 1)))
    }

    @Test fun removalRejectsEmptyMissingDuplicateStudyForeignAndOversizedSelections() {
        val all = listOf(article(1), article(2, owner = 2), article(3).copy(space = "study"))
        for (ids in listOf(emptyList(), listOf(99L), listOf(1L, 1L), listOf(2L), listOf(3L), listOf(-1L), (1L..61L).toList())) {
            assertTrue(runCatching { plan(all, ids = ids) }.isFailure)
        }
    }

    @Test fun confirmationCountsOnlySelectedOwnedAssociationsWithoutChangingInputs() {
        val all = listOf(article(1), article(2)); val notes = listOf(note(1, 1), note(2, 2), note(3, 1, 2))
        val result = plan(all, notes)
        assertEquals(listOf(1L), result.ids)
        assertEquals(listOf(notes[0]), result.notes)
        assertEquals(listOf(article(1), article(2)), all)
        assertTrue(result.matches(plan(all.reversed(), notes.reversed())))
    }

    @Test fun changedProgressVersionAssociatedNotesOrRestoreRevisionInvalidatesConfirmation() {
        val a = article(1); val original = plan(listOf(a), listOf(note(1, 1)))
        assertFalse(original.matches(plan(listOf(a.copy(readOffset = 2)), original.notes)))
        assertFalse(original.matches(plan(listOf(a.copy(fingerprint = "changed")), original.notes)))
        assertFalse(original.matches(plan(listOf(a), original.notes + note(2, 1))))
        assertFalse(original.matches(original.copy(revision = 21)))
        assertFalse(original.matches(original.copy(createdAt = 11)))
        assertFalse(original.matches(original.copy(owner = 2)))
    }

    @Test fun capacitySizedConfirmationIsExactAndDoesNotSelectFutureArrivals() {
        val rows = (1L..60L).map { article(it) }
        val expected = plan(rows, ids = rows.map { it.id })
        assertEquals(60, expected.ids.size)
        val subset = plan(rows, ids = listOf(2, 1))
        assertEquals(listOf(1L, 2L), subset.ids)
        assertTrue(subset.matches(plan(rows + article(61), ids = listOf(1, 2))))
    }
}
