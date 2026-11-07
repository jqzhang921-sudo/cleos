package com.cleo.cleos.data

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class WorldScheduleRulesTest {
    private val now = ZonedDateTime.parse("2026-10-20T12:00:00+08:00[Asia/Shanghai]")
    @Test fun enablingDoesNotImmediatelyCallTheModel() {
        assertEquals(15 * 60_000L, WorldScheduleRules.first(now) - now.toInstant().toEpochMilli())
    }
    @Test fun overdueOpportunityAdvancesFromNowInsteadOfCatchingUp() {
        assertEquals(now.plusHours(4).toInstant().toEpochMilli(), WorldScheduleRules.next(now, false))
        val late = now.plusDays(6)
        assertEquals(late.plusHours(4).toInstant().toEpochMilli(), WorldScheduleRules.next(late, false))
    }
    @Test fun exhaustedBudgetWaitsUntilTomorrowWithoutACompressedInterval() {
        assertEquals(now.plusDays(1).withHour(0).withMinute(15).toInstant().toEpochMilli(), WorldScheduleRules.next(now, true))
        val late = now.withHour(23)
        assertEquals(late.plusHours(4).toInstant().toEpochMilli(), WorldScheduleRules.next(late, true))
    }
    @Test fun waitsRemainInTheFutureAcrossDstAndOffsetChanges() {
        for (clock in listOf("2026-11-01T01:30:00-04:00[America/New_York]", "2026-03-08T01:30:00-05:00[America/New_York]")) {
            val time = ZonedDateTime.parse(clock)
            assertEquals(4 * 3_600_000L, WorldScheduleRules.next(time, false) - time.toInstant().toEpochMilli())
            assertTrue(WorldScheduleRules.next(time, true) > time.toInstant().toEpochMilli())
        }
    }
}
