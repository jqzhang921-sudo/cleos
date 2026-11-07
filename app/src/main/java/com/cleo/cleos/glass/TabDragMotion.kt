package com.cleo.cleos.glass

import kotlin.math.abs
import kotlin.math.exp

internal data class TabDragFrame(val position: Float, val velocity: Float)

/** Display-frame follower, independent of how many touch samples the device delivers. */
internal object TabDragMotion {
    fun target(finger: Float, inset: Float, width: Float, count: Int): Float =
        (finger - inset - width / 2f).coerceIn(0f, width * (count - 1))

    fun index(position: Float, width: Float, count: Int): Int =
        ((position + width / 2f) / width).toInt().coerceIn(0, count - 1)

    fun atRest(frame: TabDragFrame, target: Float) = abs(frame.position - target) < 0.1f && abs(frame.velocity) < 0.5f

    fun stretch(velocity: Float, width: Float): Float = (abs(velocity) * 0.012f).coerceAtMost(width * 0.28f)

    /** Exact critically damped spring integration; stable even after a long missed frame. */
    fun follow(frame: TabDragFrame, target: Float, max: Float, seconds: Float): TabDragFrame {
        require(max.isFinite() && max >= 0f && target.isFinite() && seconds.isFinite() && seconds >= 0f)
        val goal = target.coerceIn(0f, max)
        val position = frame.position.coerceIn(0f, max)
        val dt = seconds.coerceAtMost(0.05f)
        val omega = 32f
        val distance = position - goal
        val c = frame.velocity + omega * distance
        val decay = exp(-omega * dt)
        val raw = goal + (distance + c * dt) * decay
        val next = raw.coerceIn(0f, max)
        val velocity = if (next != raw) 0f else (frame.velocity - omega * c * dt) * decay
        return TabDragFrame(next, velocity)
    }
}
