package com.cleo.cleos.ui.todo

import org.junit.Assert.*
import org.junit.Test

class DateRailSelectionTest {
    @Test fun initializationDoesNotSelectAnUnfilteredDate() {
        val rail = DateRailSelection(null)
        assertNull(rail.frame(100, false, false))
        assertNull(rail.frame(100, false, false))
    }

    @Test fun selectsOnlyTheFinalCenterOnceAfterDragOrFling() {
        val rail = DateRailSelection(100)
        assertNull(rail.frame(101, true, false))
        assertNull(rail.frame(104, true, false))
        assertEquals(104L, rail.frame(104, false, false))
        assertNull(rail.frame(104, false, false))
    }

    @Test fun returningToSameCenterDoesNotVibrateOrSelectAgain() {
        val rail = DateRailSelection(100)
        rail.frame(101, true, false)
        assertNull(rail.frame(100, false, false))
        assertFalse(rail.choose(100))
    }

    @Test fun explicitTapWorksWithoutAnimationFramesAndDeduplicatesScrollCallback() {
        val rail = DateRailSelection(null)
        assertTrue(rail.choose(100))
        rail.frame(101, true, false)
        assertEquals(101L, rail.frame(101, false, false))
        assertFalse(rail.choose(101))
        rail.frame(102, true, false)
        assertTrue(rail.choose(102))
        assertNull(rail.frame(102, false, false))
    }

    @Test fun externalAlignmentCancelsPendingUserCommitAndMakesNoFeedback() {
        val rail = DateRailSelection(100)
        rail.frame(101, true, false)
        rail.external(200)
        assertNull(rail.frame(200, true, true))
        assertNull(rail.frame(200, false, false))
        assertFalse(rail.choose(200))
    }

    @Test fun allDatesAllowsSelectingCurrentCenterAgain() {
        val rail = DateRailSelection(100)
        rail.external(null)
        assertTrue(rail.choose(100))
        rail.external(null)
        rail.frame(101, true, false)
        assertEquals(100L, rail.frame(100, false, false))
    }
}
