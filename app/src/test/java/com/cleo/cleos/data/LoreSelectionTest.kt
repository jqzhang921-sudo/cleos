package com.cleo.cleos.data

import org.junit.Assert.*
import org.junit.Test

class LoreSelectionTest {
    @Test fun selectingFilteredResultsKeepsSelectionsOutsideTheFilter() {
        assertEquals(setOf(1L, 2L, 3L), LoreSelection.toggle(setOf(1L), listOf(2L, 3L)))
        assertEquals(setOf(1L), LoreSelection.toggle(setOf(1L, 2L, 3L), listOf(2L, 3L)))
        assertEquals(setOf(1L, 2L, 3L), LoreSelection.toggle(setOf(1L, 2L), listOf(2L, 3L)))
        assertEquals(setOf(1L), LoreSelection.toggle(setOf(1L), emptyList()))
    }

    @Test fun aLargeBookCanBeSelectedAndDeselectedWithoutDuplicates() {
        val ids = (1L..13_382L).toList()
        val selected = LoreSelection.toggle(setOf(20_000L), ids + ids.take(20))
        assertEquals(13_383, selected.size)
        assertEquals(setOf(20_000L), LoreSelection.toggle(selected, ids))
    }
}
