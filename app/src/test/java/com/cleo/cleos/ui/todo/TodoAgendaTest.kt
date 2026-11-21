package com.cleo.cleos.ui.todo

import com.cleo.cleos.ai.CalEvent
import com.cleo.cleos.data.db.TodoEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TodoAgendaTest {
    private val day = LocalDate.of(2026, 11, 18)
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun stamp(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun dateCombinesDatedTasksAndSystemEventsWithoutDraggingInUndatedTasks() {
        val timed = CalEvent(1, "课程", stamp("2026-11-18T17:00:00Z"), stamp("2026-11-18T18:30:00Z"), false)
        val allDay = CalEvent(2, "考试周", stamp("2026-11-18T00:00:00Z"), stamp("2026-11-19T00:00:00Z"), true)
        val task = TodoEntity(id = 1, title = "交作业", dueDay = day.toEpochDay(), createdAt = 10L)
        val undated = TodoEntity(id = 2, title = "买东西", createdAt = 11L)
        val items = TodoAgenda.items(day, zone, listOf(task, undated), listOf(timed, allDay))
        assertEquals(listOf("考试周", "课程", "交作业"), items.map { it.title })
        assertFalse(TodoAgenda.eventOn(allDay, day.minusDays(1), zone))
        assertFalse(TodoAgenda.eventOn(allDay, day.plusDays(1), zone))
    }

    @Test fun lateEventEndingAtMidnightDoesNotRepeatIntoFollowingDate() {
        val timed = CalEvent(1, "晚间活动", stamp("2026-11-19T07:00:00Z"), stamp("2026-11-19T08:00:00Z"), false)
        assertTrue(TodoAgenda.eventOn(timed, day, zone))
        assertFalse(TodoAgenda.eventOn(timed, day.plusDays(1), zone))
    }

    @Test fun calendarDragSettlesAtThreeLevelsInEitherDirection() {
        val travel = AgendaCalendarTravel(month = 192f, schedule = 352f, stretch = 130f, snap = 36f)
        assertEquals(AgendaCalendarLevel.Week, travel.release(AgendaCalendarLevel.Week, 0f, 12f))
        assertEquals(AgendaCalendarLevel.Month, travel.release(AgendaCalendarLevel.Week, 0f, 85f))
        assertEquals(AgendaCalendarLevel.Schedule, travel.release(AgendaCalendarLevel.Week, 0f, 250f))
        assertEquals(AgendaCalendarLevel.Month, travel.release(AgendaCalendarLevel.Schedule, 352f, 240f))
        assertEquals(AgendaCalendarLevel.Week, travel.release(AgendaCalendarLevel.Month, 192f, 100f))
    }

    @Test fun draggingBeyondTheScheduleReturnsToTheScheduleLevel() {
        val travel = AgendaCalendarTravel(month = 192f, schedule = 352f, stretch = 130f, snap = 36f)
        assertEquals(2f, travel.progress(352f), .001f)
        assertEquals(3f, travel.progress(482f), .001f)
        assertEquals(6f, travel.progress(2000f), .001f)
        assertEquals(AgendaCalendarLevel.Schedule, travel.release(AgendaCalendarLevel.Schedule, 352f, 482f))
        assertEquals(AgendaCalendarLevel.Schedule, travel.release(AgendaCalendarLevel.Schedule, 482f, 352f))
    }

    @Test fun diaryTravelStopsAtMonthEvenOnALongPull() {
        val travel = AgendaCalendarTravel(192f, 352f, 130f, 36f, AgendaCalendarLevel.Month)
        assertEquals(192f, travel.end, .001f)
        assertEquals(1f, travel.progress(500f), .001f)
        assertEquals(AgendaCalendarLevel.Month, travel.release(AgendaCalendarLevel.Week, 0f, 500f))
        assertEquals(AgendaCalendarLevel.Week, travel.release(AgendaCalendarLevel.Month, 192f, 100f))
    }
}
