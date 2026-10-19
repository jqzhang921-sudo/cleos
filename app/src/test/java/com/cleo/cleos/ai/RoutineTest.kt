package com.cleo.cleos.ai

import com.cleo.cleos.data.db.TodoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutineTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    @Test
    fun aMessageAfterMidnightBelongsToTheDayBefore() {
        assertEquals(LocalDate.of(2026, 9, 29), RoutineRules.dayOf(at(30, 1, 30)))
        assertEquals(LocalDate.of(2026, 9, 30), RoutineRules.dayOf(at(30, 5, 0)))
        assertEquals(at(30, 5, 0), RoutineRules.startOf(LocalDate.of(2026, 9, 30), zone))
    }

    @Test
    fun fewerThanFiveDaysIsNothingToGoOn() {
        val times = (20..23).flatMap { d -> listOf(ms(at(d, 8, 0)), ms(at(d, 23, 0))) }
        val h = RoutineRules.habits(times, zone)
        assertNull(h.firstSaid)
        assertNull(h.lastSaid)
        assertEquals(4, h.days)
        assertNull(RoutineRules.morning(h, LocalDate.of(2026, 9, 29), zone))
        assertNull(RoutineRules.night(h, LocalDate.of(2026, 9, 29), zone))
    }

    @Test
    fun theUsualStartAndEndAreMediansAndLateNightsCountAsLate() {
        val times = listOf(
            // Days whose last word is after midnight: 00:40 comes after 23:10, not before 08:00.
            at(20, 8, 10), at(20, 13, 0), at(21, 0, 40),
            at(21, 8, 20), at(21, 23, 10),
            at(22, 8, 30), at(23, 0, 50),
            at(23, 7, 50), at(23, 22, 0),
            // One late start doesn't move the middle.
            at(24, 11, 0), at(25, 1, 10),
        ).map(::ms)
        val h = RoutineRules.habits(times, zone)
        assertEquals(5, h.days)
        assertEquals(LocalTime.of(8, 20), h.firstSaid)
        assertEquals(LocalTime.of(0, 40), h.lastSaid)
    }

    @Test
    fun theMorningIsLookedIntoFromJustBeforeTheUsualTimeAndNeverBeforeTheDayStarts() {
        val day = LocalDate.of(2026, 9, 29)
        val w = RoutineRules.morning(Habits(LocalTime.of(8, 20), LocalTime.of(23, 0), 10), day, zone)!!
        assertEquals(at(29, 8, 0), w.start)
        assertEquals(at(29, 10, 50), w.endInclusive)
        val early = RoutineRules.morning(Habits(LocalTime.of(5, 10), LocalTime.of(23, 0), 10), day, zone)!!
        assertEquals(at(29, 5, 0), early.start)
        // Up at one in the afternoon: no morning to greet.
        assertNull(RoutineRules.morning(Habits(LocalTime.of(13, 0), LocalTime.of(23, 0), 10), day, zone))
    }

    @Test
    fun theNightCanRunPastMidnightAndAnEarlyEveningIsNoNight() {
        val day = LocalDate.of(2026, 9, 28)
        val late = RoutineRules.night(Habits(LocalTime.of(8, 0), LocalTime.of(0, 40), 10), day, zone)!!
        assertEquals(at(29, 0, 10), late.start)
        assertEquals(at(29, 1, 40), late.endInclusive)
        val evening = RoutineRules.night(Habits(LocalTime.of(8, 0), LocalTime.of(23, 10), 10), day, zone)!!
        assertEquals(at(28, 22, 40), evening.start)
        assertNull(RoutineRules.night(Habits(LocalTime.of(8, 0), LocalTime.of(18, 0), 10), day, zone))
    }

    /**
     * What keeps a greeting's job from running itself in a loop: whenever it looks, the next day's
     * window starts later than that moment (it is queued for then).
     */
    @Test
    fun theNextDaysWindowAlwaysStartsLaterThanNow() {
        val usual = listOf(LocalTime.of(5, 0), LocalTime.of(5, 10), LocalTime.of(7, 30), LocalTime.of(11, 50), LocalTime.of(20, 0), LocalTime.of(23, 50), LocalTime.of(0, 0), LocalTime.of(4, 50))
        var now = at(29, 0, 0)
        while (now.isBefore(at(30, 0, 0))) {
            for (t in usual) {
                val h = Habits(t, t, 10)
                val next = RoutineRules.dayOf(now).plusDays(1)
                for (g in Greeting.entries) {
                    val start = RoutineRules.window(g, h, next, zone)?.start ?: RoutineRules.startOf(next, zone)
                    assertTrue("$g $t at $now: $start", start.isAfter(now))
                }
            }
            now = now.plusMinutes(5)
        }
    }

    @Test
    fun settingsSayWhatTheGreetingsGoByAndWhichThereAre() {
        assertTrue(RoutineRules.describe(Habits(null, null, 3)).startsWith("再多聊几天"))
        val both = RoutineRules.describe(Habits(LocalTime.of(8, 20), LocalTime.of(0, 40), 10))
        assertEquals("你一般 8:20 左右开始找 TA 说话，0:40 左右说最后一句。早上醒了、晚上睡前，TA 可能会来打个招呼。", both)
        assertTrue(RoutineRules.describe(Habits(LocalTime.of(13, 0), LocalTime.of(23, 30), 10)).endsWith("晚上睡前，TA 可能会来打个招呼。"))
        val neither = RoutineRules.describe(Habits(LocalTime.of(13, 0), LocalTime.of(18, 0), 10))
        assertFalse(neither.contains("招呼"))
    }

    @Test
    fun theGreetingTextsLetTheTaDecideAndDontGiveAwayThePhone() {
        val now = at(29, 8, 5)
        val morning = RoutineRules.morningText(now, null)
        assertFalse(morning.contains("SKIP"))
        assertTrue(morning.contains("直接结束，不输出文字"))
        assertTrue(morning.contains("今天你们还没说过话"))
        assertFalse(morning.contains("清单"))
        // It is told the person seems up, not how the app can tell.
        assertFalse(morning.contains("手机"))
        val withDay = RoutineRules.morningText(now, "日程：今天 10:00 组会")
        assertTrue(withDay.contains("日程：今天 10:00 组会"))
        assertTrue(withDay.contains("别念清单"))

        val night = RoutineRules.nightText(at(29, 23, 40), at(29, 21, 10), null)
        assertFalse(night.contains("SKIP"))
        assertTrue(night.contains("直接结束，不输出文字"))
        assertTrue(night.contains("你们上次说话是今天 21:10"))
        assertTrue(night.contains("别催对方睡"))
        assertFalse(night.contains("手机"))
        assertFalse(RoutineRules.nightText(at(29, 23, 40), null, null).contains("上次说话"))
        // Past midnight it is still the same night; the last word was yesterday by the calendar.
        assertTrue(RoutineRules.nightText(at(30, 0, 30), at(29, 22, 0), null).contains("你们上次说话是昨天 22:00"))
    }

    @Test
    fun todosNotDoneComeDueFirstThenTheNewestWithoutADate() {
        val today = LocalDate.of(2026, 9, 29)
        val t = today.toEpochDay()
        assertNull(Glance.todos(listOf(TodoEntity(title = "交作业", done = true, createdAt = 1)), today))
        val todos = listOf(
            TodoEntity(title = "买牛奶", createdAt = 1),
            TodoEntity(title = "回邮件 ", createdAt = 5),
            TodoEntity(title = "交报告", dueDay = t + 1, createdAt = 2),
            TodoEntity(title = "还书", dueDay = t - 2, createdAt = 3),
            TodoEntity(title = "体检", dueDay = t, createdAt = 4),
            TodoEntity(title = "旅行", dueDay = LocalDate.of(2026, 10, 3).toEpochDay(), createdAt = 6),
            TodoEntity(title = "做完了", dueDay = t, done = true, createdAt = 7),
        )
        assertEquals(
            "没做完的待办：还书（过期 2 天）；体检（今天）；交报告（明天）；旅行（10月3日）；回邮件",
            Glance.todos(todos, today),
        )
    }
}
