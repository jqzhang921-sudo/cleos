package com.cleo.cleos.data

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CourseCalendarFileTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private fun calendar(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + body + "\r\nEND:VCALENDAR"

    @Test fun weeklyCourseUsesItsOwnZoneAndKeepsFoldedTextAndExcludedDates() {
        val course = CourseCalendarFile.parse(calendar("""
            BEGIN:VEVENT
            UID:course-1
            SUMMARY:离散数学与
             逻辑
            DTSTART;TZID=Asia/Shanghai:20261118T094500
            DTEND;TZID=Asia/Shanghai:20261118T112000
            RRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=6
            EXDATE;TZID=Asia/Shanghai:20261202T094500
            LOCATION:教学楼A\,101
            DESCRIPTION:第一排\n带课本
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:不导入提醒内容
            END:VALARM
            END:VEVENT
        """.trimIndent()), ZoneId.of("UTC")).single()
        assertEquals("离散数学与逻辑", course.title)
        assertEquals("教学楼A,101", course.location)
        assertEquals("第一排\n带课本", course.notes)
        assertEquals(Instant.parse("2026-11-18T01:45:00Z").toEpochMilli(), course.begin)
        assertEquals(Instant.parse("2026-11-18T03:20:00Z").toEpochMilli(), course.end)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;COUNT=6", course.recurrence)
        assertEquals("20261202T014500Z", course.excluded)
        assertFalse(course.allDay)
    }

    @Test fun defaultAllDayEndIsExclusiveUtcMidnight() {
        val course = CourseCalendarFile.parse(calendar("""
            BEGIN:VEVENT
            SUMMARY:考试周
            DTSTART;VALUE=DATE:20261118
            END:VEVENT
        """.trimIndent()), shanghai).single()
        assertTrue(course.allDay)
        assertEquals(Instant.parse("2026-11-18T00:00:00Z").toEpochMilli(), course.begin)
        assertEquals(Instant.parse("2026-11-19T00:00:00Z").toEpochMilli(), course.end)
    }

    @Test fun cancelledEventIsSkippedAndUnsupportedRescheduledInstanceFailsClearly() {
        val cancelled = """
            BEGIN:VEVENT
            UID:cancelled
            STATUS:CANCELLED
            END:VEVENT
        """.trimIndent()
        val kept = """
            BEGIN:VEVENT
            UID:valid
            DTSTART:20261118T130000
            END:VEVENT
        """.trimIndent()
        assertEquals("valid", CourseCalendarFile.parse(calendar("$cancelled\n$kept"), shanghai).single().uid)
        val error = assertThrows(ImportException::class.java) {
            CourseCalendarFile.parse(calendar(kept.replace("UID:valid", "UID:valid\nRECURRENCE-ID:20261118T120000")), shanghai)
        }
        assertTrue(error.message.orEmpty().contains("单次调课"))
    }
}
