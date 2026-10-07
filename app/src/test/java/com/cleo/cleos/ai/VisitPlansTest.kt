package com.cleo.cleos.ai

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class VisitPlansTest {
    @Test fun inputInvalidatesPendingAndInFlightProposals() {
        val plans = VisitPlans()
        val old = plans.revision(1)
        assertTrue(plans.propose(1, 30, old))
        plans.invalidate(1)
        assertFalse(plans.propose(1, 60, old))
        assertNull(plans.finish(1).second)
    }
    @Test fun lastDecisionWinsAndCompletionInvalidatesDelayedReset() {
        val plans = VisitPlans()
        val reset = plans.invalidate(1)
        plans.propose(1, 30)
        plans.propose(1, 45)
        val completed = plans.finish(1)
        assertEquals(45, completed.second)
        assertFalse(plans.current(1, reset))
        assertTrue(plans.current(1, completed.first))
        assertNull(plans.finish(1).second)
    }
    @Test fun conversationsDoNotShareDecisions() {
        val plans = VisitPlans()
        plans.propose(1, 30)
        plans.propose(2, 60)
        plans.invalidate(1)
        assertEquals(60, plans.finish(2).second)
    }
    @Test fun frequencyQuietAndDailyLimitOverrideModelChoice() {
        val now = ZonedDateTime.parse("2026-10-07T22:55:00+08:00[Asia/Shanghai]")
        val level = FreeTopicRules.level(1)
        assertEquals(now.plusMinutes(30).toInstant().toEpochMilli(), FreeTopicRules.planned(now, level, 1, false, 1380, 480))
        assertEquals(now.plusMinutes(60).toInstant().toEpochMilli(), FreeTopicRules.planned(now, level, Int.MAX_VALUE, false, 1380, 480))
        val morning = now.plusDays(1).withHour(8).withMinute(0)
        assertEquals(morning.toInstant().toEpochMilli(), FreeTopicRules.planned(now, level, 30, true, 1380, 480))
        assertEquals(morning.toInstant().toEpochMilli(), FreeTopicRules.planned(now, level, 30, false, 1380, 480, exhausted = true))
    }
}
