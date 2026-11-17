package com.cleo.cleos.ui.common

import org.junit.Assert.*
import org.junit.Test

class TabBarScrollPolicyTest {
    @Test fun upwardTravelHidesOnlyAfterThreshold() {
        val p = TabBarScrollPolicy(24f)
        repeat(3) { p.scroll(-7f, false); assertFalse(p.hidden) }
        p.scroll(-3f, false)
        assertTrue(p.hidden)
    }

    @Test fun downwardTravelRevealsOnlyAfterThreshold() {
        val p = TabBarScrollPolicy(24f)
        p.scroll(-50f, false)
        p.scroll(23f, false)
        assertTrue(p.hidden)
        p.scroll(1f, false)
        assertFalse(p.hidden)
    }

    @Test fun alternatingJitterDoesNotAccumulateAcrossDirections() {
        val p = TabBarScrollPolicy(24f)
        repeat(100) { p.scroll(-12f, false); p.scroll(12f, false) }
        assertFalse(p.hidden)
        p.scroll(-24f, false)
        repeat(100) { p.scroll(12f, false); p.scroll(-12f, false) }
        assertTrue(p.hidden)
    }

    @Test fun idleHorizontalAndUnconsumedOverscrollKeepState() {
        val p = TabBarScrollPolicy(24f)
        p.scroll(-24f, false)
        repeat(100) { p.scroll(0f, false) }
        assertTrue(p.hidden)
        p.scroll(24f, false)
        repeat(100) { p.scroll(0f, false) }
        assertFalse(p.hidden)
    }

    @Test fun topAlwaysShowsAndResetsTravel() {
        val p = TabBarScrollPolicy(24f)
        p.scroll(-24f, false)
        p.scroll(0f, true)
        assertFalse(p.hidden)
        p.scroll(-23f, false)
        assertFalse(p.hidden)
        p.scroll(-1f, false)
        assertTrue(p.hidden)
        p.scroll(-100f, true)
        assertFalse(p.hidden)
    }

    @Test fun showingNewPageResetsOldScrollDirection() {
        val p = TabBarScrollPolicy(24f)
        p.scroll(-24f, false)
        p.show()
        p.scroll(-1f, false)
        assertFalse(p.hidden)
    }

    @Test fun invalidSamplesDoNotChangeState() {
        val p = TabBarScrollPolicy(24f)
        p.scroll(Float.NaN, false)
        p.scroll(Float.NEGATIVE_INFINITY, false)
        assertFalse(p.hidden)
    }
}
