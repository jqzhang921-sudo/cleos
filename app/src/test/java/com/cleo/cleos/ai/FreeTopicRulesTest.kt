package com.cleo.cleos.ai

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.time.Instant
import java.time.ZoneId

class FreeTopicRulesTest {
    private fun at(hour: Int, minute: Int = 0) = ZonedDateTime.parse("2026-10-06T00:00:00+08:00[Asia/Shanghai]").withHour(hour).withMinute(minute)
    @Test fun overnightQuietIncludesItsStartButNotItsEnd() {
        assertTrue(FreeTopicRules.quiet(at(23), true, 1380, 480))
        assertTrue(FreeTopicRules.quiet(at(0), true, 1380, 480))
        assertTrue(FreeTopicRules.quiet(at(7, 59), true, 1380, 480))
        assertFalse(FreeTopicRules.quiet(at(8), true, 1380, 480))
        assertFalse(FreeTopicRules.quiet(at(22, 59), true, 1380, 480))
    }
    @Test fun daytimeQuietDisabledQuietAndWholeDayAreDistinct() {
        assertTrue(FreeTopicRules.quiet(at(12), true, 600, 840))
        assertFalse(FreeTopicRules.quiet(at(9), true, 600, 840))
        assertFalse(FreeTopicRules.quiet(at(0), false, 1380, 480))
        assertTrue(FreeTopicRules.quiet(at(16), true, 480, 480))
    }
    @Test fun nextOpportunitySkipsQuietWithoutReplayingMissedIntervals() {
        val next = FreeTopicRules.next(at(22), FreeTopicRules.level(2), true, 1380, 480, 0.0)
        val resume = Instant.ofEpochMilli(next).atZone(ZoneId.of("Asia/Shanghai"))
        assertEquals(8, resume.hour)
        assertEquals(at(22).toLocalDate().plusDays(1), resume.toLocalDate())
        assertEquals(at(8), FreeTopicRules.outsideQuiet(at(8), true, 1380, 480))
    }
    @Test fun idleGuardAndSharedUnansweredFuseLeaveTheUserAlone() {
        assertNotNull(FreeTopicRules.held(true, false, false, 1000, 1000 + FreeTopicRules.IDLE_MS - 1, 0, 0, 6))
        assertNull(FreeTopicRules.held(true, false, false, 1000, 1000 + FreeTopicRules.IDLE_MS, 1, 0, 6))
        assertNotNull(FreeTopicRules.held(true, false, false, 1000, 1000 + FreeTopicRules.IDLE_MS, 2, 0, 6))
        assertNotNull(FreeTopicRules.held(true, false, false, null, 9999999, 0, 0, 6))
    }
    @Test fun disabledQuietBusyAndDailyBudgetPreventRequests() {
        assertNotNull(FreeTopicRules.held(false, false, false, 1, 9999999, 0, 0, 6))
        assertNotNull(FreeTopicRules.held(true, true, false, 1, 9999999, 0, 0, 6))
        assertNotNull(FreeTopicRules.held(true, false, true, 1, 9999999, 0, 0, 6))
        assertNotNull(FreeTopicRules.held(true, false, false, 1, 9999999, 0, 6, 6))
    }
    @Test fun allLevelsHaveBoundedIntervalsAndInvalidSettingsFallBack() {
        FreeTopicRules.LEVELS.forEach { l ->
            val start = at(8)
            assertEquals(l.minHours * 3600000L, FreeTopicRules.next(start, l, false, 1380, 480, -1.0) - start.toInstant().toEpochMilli())
            assertEquals(l.maxHours * 3600000L, FreeTopicRules.next(start, l, false, 1380, 480, 2.0) - start.toInstant().toEpochMilli())
        }
        assertEquals(1, FreeTopicRules.level(999).id)
        assertEquals(1380, FreeTopicRules.minute(-1, 1380))
    }
}
