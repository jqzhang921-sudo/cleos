package com.cleo.cleos.glass

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TabDragMotionTest {
    @Test fun aFastDragStaysInsideTheBarEvenWhenItReversesAtAnEdge() {
        var frame = TabDragFrame(0f, 0f)
        repeat(240) { i ->
            val target = if (i % 40 < 20) 900f else 0f
            frame = TabDragMotion.follow(frame,target,900f,1f/120f)
            assertTrue(frame.position in 0f..900f);assertTrue(frame.velocity.isFinite())
        }
        repeat(120) { frame = TabDragMotion.follow(frame,0f,900f,1f/60f) }
        assertTrue(TabDragMotion.atRest(frame,0f))
    }
    @Test fun steadyMovementIsRefreshRateIndependent() {
        fun after(hz:Int):TabDragFrame {
            var frame=TabDragFrame(0f,0f)
            repeat(hz/2) { frame=TabDragMotion.follow(frame,900f,900f,1f/hz) }
            return frame
        }
        val sixty=after(60);val high=after(120)
        assertEquals(sixty.position,high.position,0.01f);assertEquals(sixty.velocity,high.velocity,0.01f)
    }
    @Test fun aMissedFrameOrRepeatedSampleCannotExplodeTheLens() {
        var frame=TabDragFrame(200f,8000f)
        repeat(20) { frame=TabDragMotion.follow(frame,0f,900f,0.5f) }
        assertTrue(frame.position.isFinite() && frame.position in 0f..900f)
        assertTrue(TabDragMotion.stretch(100_000f,300f)<=84f)
        assertTrue(TabDragMotion.stretch(-100_000f,300f)<=84f)
        assertEquals(0f,TabDragMotion.stretch(0f,300f),0f)
    }
    @Test fun firstAndLastTargetsAndReleaseSelectionAreClamped() {
        assertEquals(0f,TabDragMotion.target(-900f,4f,100f,4),0f)
        assertEquals(300f,TabDragMotion.target(1900f,4f,100f,4),0f)
        for (i in 0..3) assertEquals(i,TabDragMotion.index(TabDragMotion.target(4f+(i+0.5f)*100f,4f,100f,4),100f,4))
    }
    @Test fun resizingWhileMovingUsesTheNewBounds() {
        val frame=TabDragMotion.follow(TabDragFrame(900f,4000f),900f,450f,1f/60f)
        assertTrue(frame.position in 0f..450f);assertEquals(0f,frame.velocity,0f)
    }
    @Test fun restingFollowerDoesNotDriftAndCanSleepUntilTheNextTouchTarget() {
        val frame=TabDragFrame(300f,0f)
        assertTrue(TabDragMotion.atRest(frame,300f))
        val next=TabDragMotion.follow(frame,300f,900f,1f/60f)
        assertEquals(frame,next)
        assertFalse(TabDragMotion.atRest(frame,400f))
    }
}
