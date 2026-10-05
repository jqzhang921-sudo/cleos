package com.cleo.cleos.ui.chat

import com.cleo.cleos.ai.ToolSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolKindsTest {
    @Test
    fun theToolsCleosHasAreSortedIntoKinds() {
        assertEquals(ToolKind.Todo, ToolKinds.of(ToolSpecs.addTodo.name))
        assertEquals(ToolKind.Todo, ToolKinds.of(ToolSpecs.updateTodo.name))
        assertEquals(ToolKind.Calendar, ToolKinds.of(ToolSpecs.deleteEvent.name))
        assertEquals(ToolKind.Alarm, ToolKinds.of(ToolSpecs.setAlarm.name))
        assertEquals(ToolKind.Timer, ToolKinds.of(ToolSpecs.setTimer.name))
        assertEquals(ToolKind.Memory, ToolKinds.of(ToolSpecs.memory.name))
        assertEquals(ToolKind.Weather, ToolKinds.of(ToolSpecs.getWeather.name))
        assertEquals(ToolKind.Location, ToolKinds.of(ToolSpecs.getLocation.name))
    }

    @Test
    fun servicesThePersonAddedAreOneKindAndWhatIsUnknownIsOther() {
        assertEquals(ToolKind.Outside, ToolKinds.of("mcp_a1b2_map_search_places"))
        assertEquals(ToolKind.Outside, ToolKinds.of("mcp_c3d4_order_burger"))
        assertEquals(ToolKind.Other, ToolKinds.of("something_new"))
        assertEquals(ToolKind.Other, ToolKinds.of(null))
    }

    @Test
    fun aRunShowsEachKindOnceUpToTheMaxAndCountsTheRest() {
        val kinds = listOf(ToolKind.Location, ToolKind.Outside, ToolKind.Outside, ToolKind.Weather, ToolKind.Alarm, ToolKind.Todo)
        val s = ToolKinds.stack(kinds, List(kinds.size) { false }, 3)
        assertEquals(listOf(ToolKind.Location, ToolKind.Outside, ToolKind.Weather), s.shown.map { it.first })
        // Alarm and Todo are not shown: two calls left over.
        assertEquals(2, s.more)
        assertFalse(s.hiddenFailed)
    }

    @Test
    fun aFailedCallMarksItsKindOrShowsOnItsOwnWhenItsKindIsHidden() {
        val kinds = listOf(ToolKind.Location, ToolKind.Outside, ToolKind.Outside, ToolKind.Weather)
        val inShown = ToolKinds.stack(kinds, listOf(false, false, true, false), 3)
        assertEquals(listOf(false, true, false), inShown.shown.map { it.second })
        assertFalse(inShown.hiddenFailed)
        val hidden = ToolKinds.stack(kinds + ToolKind.Alarm, listOf(false, false, false, false, true), 3)
        assertEquals(1, hidden.more)
        assertTrue(hidden.hiddenFailed)
        assertEquals(0, ToolKinds.stack(listOf(ToolKind.Alarm, ToolKind.Alarm), listOf(false, false), 3).more)
    }
}
