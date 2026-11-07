package com.cleo.cleos.ui.records

import androidx.compose.runtime.saveable.SaverScope
import com.cleo.cleos.ui.todo.TodoCalendarModel
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RecordCalendarStateTest {
    @Test fun undatedDoesNotReplaceSharedDiaryDateAndPickReturnsToDatedScope() {
        val s = RecordCalendarState(LocalDate.of(2026,11,1))
        s.pick(LocalDate.of(2026,10,29)); s.filterEpoch = TodoCalendarModel.UNDATED
        assertEquals(LocalDate.of(2026,10,29), s.day)
        assertEquals(TodoCalendarModel.UNDATED, s.filterEpoch)
        s.undated = false; assertEquals(s.dayEpoch, s.filterEpoch)
        s.pick(LocalDate.of(2026,11,2)); assertFalse(s.undated)
        s.filterEpoch = TodoCalendarModel.ALL; assertNull(s.day)
    }
    @Test fun sharedListDateUpdatesTheMonthAndWeekAnchor() {
        val s = RecordCalendarState(LocalDate.of(2026,11,1))
        val chosen = LocalDate.of(2027,2,15)
        s.filterEpoch = chosen.toEpochDay()
        assertEquals(chosen.withDayOfMonth(1).toEpochDay(), s.monthEpoch)
        assertTrue(chosen in s.week())
    }
    @Test fun collapsedWeeksCrossYearsAndRemainSundayFirst() {
        val s = RecordCalendarState(LocalDate.of(2026,12,31));s.pick(LocalDate.of(2026,12,31));s.collapsed=true
        assertEquals(LocalDate.of(2026,12,27), s.week().first())
        s.step(1);assertEquals(LocalDate.of(2027,1,7),s.day)
        assertEquals(LocalDate.of(2027,1,3),s.week().first())
        s.step(-1);assertEquals(LocalDate.of(2026,12,31),s.day)
    }
    @Test fun monthPagingClampsShortMonthsAndUndatedMonthHasMatchingWeekAnchor() {
        val s=RecordCalendarState(LocalDate.of(2024,1,31));s.pick(LocalDate.of(2024,1,31));s.step(1)
        assertEquals(LocalDate.of(2024,2,29),s.day)
        s.filterEpoch=TodoCalendarModel.ALL;s.step(1)
        assertTrue(LocalDate.of(2024,3,1) in s.week())
    }
    @Test fun recreationRetainsDateMonthWeekModeAndUndatedScope() {
        val s=RecordCalendarState(LocalDate.of(2026,11,1));s.pick(LocalDate.of(2026,10,29));s.collapsed=true;s.undated=true
        s.todoDraft="还没发送的待办 🙂"
        val scope=object:SaverScope { override fun canBeSaved(value:Any)=true }
        val restored=with(RecordCalendarState.Saver) { restore(scope.save(s)!!)!! }
        assertEquals(s.dayEpoch,restored.dayEpoch);assertEquals(s.monthEpoch,restored.monthEpoch)
        assertEquals(s.week(),restored.week());assertTrue(restored.collapsed);assertTrue(restored.undated)
        assertEquals(s.todoDraft,restored.todoDraft)
    }
    @Test fun oldSavedCalendarStateRestoresWithAnEmptyUnsentComposer() {
        val day=LocalDate.of(2026,10,29).toEpochDay()
        val old=listOf(day,LocalDate.of(2026,10,1).toEpochDay(),day,true,true)
        val restored=with(RecordCalendarState.Saver) { restore(old)!! }
        assertEquals(day,restored.dayEpoch);assertTrue(restored.collapsed);assertTrue(restored.undated)
        assertEquals("",restored.todoDraft)
    }
}
