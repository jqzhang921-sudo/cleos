package com.cleo.cleos.ui.todo

import com.cleo.cleos.data.db.TodoEntity
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class TodoCalendarModelTest {
    @Test fun november2026StartsSundayAndHasFiveWholeWeeks() {
        val weeks = TodoCalendarModel.weeks(YearMonth.of(2026, 11))
        assertEquals(5, weeks.size)
        assertEquals(LocalDate.of(2026, 11, 1), weeks.first().first())
        assertEquals((1..30).toList(), weeks.flatten().filterNotNull().map { it.dayOfMonth })
        assertEquals(5, weeks.flatten().count { it == null })
    }
    @Test fun leapDayAndSixWeekMonthHaveNoLostOrDuplicatedDates() {
        for (month in listOf(YearMonth.of(2024, 2), YearMonth.of(2026, 8), YearMonth.of(2027, 2))) {
            val weeks = TodoCalendarModel.weeks(month)
            assertTrue(weeks.all { it.size == 7 })
            assertEquals((1..month.lengthOfMonth()).toList(), weeks.flatten().filterNotNull().map { it.dayOfMonth })
        }
        assertEquals(6, TodoCalendarModel.weeks(YearMonth.of(2026, 8)).size)
    }
    @Test fun unarrangedAndAllNeverHideOrAssignADateToUndatedTasks() {
        val tasks = listOf(todo(1, null), todo(2, 20758), todo(3, null, true))
        assertEquals(tasks, tasks.filter { TodoCalendarModel.matches(it, TodoCalendarModel.ALL) })
        assertEquals(listOf(1L, 3L), tasks.filter { TodoCalendarModel.matches(it, TodoCalendarModel.UNDATED) }.map { it.id })
        assertEquals(listOf(2L), tasks.filter { TodoCalendarModel.matches(it, 20758) }.map { it.id })
        assertNull(TodoCalendarModel.addDay(TodoCalendarModel.UNDATED))
        assertNull(TodoCalendarModel.addDay(TodoCalendarModel.ALL))
        assertEquals(20758L, TodoCalendarModel.addDay(20758))
    }
    @Test fun pendingTasksPrecedeDoneTasksAndAllReferencesRetainIdentity() {
        val tasks = listOf(todo(1, 20758, true), todo(2, 20758), todo(3, null), todo(4, 20759))
        val grouped = TodoCalendarModel.byDay(tasks)
        assertEquals(listOf(2L, 1L), grouped[20758L]!!.map { it.id })
        assertEquals(3, grouped.values.sumOf { it.size })
        assertSame(tasks[1], grouped[20758L]!!.first())
        assertSame(tasks[0], grouped[20758L]!!.last())
    }
    private fun todo(id: Long, day: Long?, done: Boolean = false) =
        TodoEntity(id = id, title = "task$id", dueDay = day, done = done, createdAt = id)
}
