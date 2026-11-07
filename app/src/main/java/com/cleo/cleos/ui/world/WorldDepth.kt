package com.cleo.cleos.ui.world

import kotlin.math.abs

/** Bounded perspective: an off-screen page never flips or becomes a tiny touch target. */
internal data class WorldDepth(val scale: Float, val rotationY: Float, val alpha: Float, val inward: Float) {
    companion object {
        fun at(offset: Float, enabled: Boolean): WorldDepth {
            if (!enabled) return WorldDepth(1f, 0f, 1f, 0f)
            val position = if (offset.isFinite()) offset.coerceIn(-1f, 1f) else 0f
            val distance = abs(position)
            return WorldDepth(1f - .12f * distance, -24f * position, 1f - .10f * distance, position)
        }
    }
}
