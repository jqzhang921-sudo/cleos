package com.cleo.cleos.data

import kotlin.math.roundToInt

/** Store distance from the nearest edge, using the visible (clamped) starting position. */
internal fun moveBubbleAnchor(corner: String, offset: Int, width: Float, size: Int, dx: Float): Pair<String, Int> {
    val span = (width - size).coerceAtLeast(0f)
    val left = if (corner.endsWith("l")) offset.toFloat().coerceIn(-24f, span)
        else span + offset.toFloat().coerceIn(-span, 24f)
    val moved = (left + dx).coerceIn(-24f, span + 24f)
    val topBottom = if (corner.startsWith("b")) "b" else "t"
    return if (moved <= span / 2f) (topBottom + "l") to moved.roundToInt().coerceIn(-64, 64)
        else (topBottom + "r") to (moved - span).roundToInt().coerceIn(-64, 64)
}
