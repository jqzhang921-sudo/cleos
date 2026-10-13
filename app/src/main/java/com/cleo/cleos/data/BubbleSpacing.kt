package com.cleo.cleos.data

/** Shared by chat, previews and portable bubble presets. Decorations still reserve their own room. */
object BubbleSpacing {
    const val DEFAULT = 6
    const val MAX = 24
    fun normalized(value: Int) = value.coerceIn(0, MAX)
    // Preserve the old appearance at 6; compact spacing also removes the bubble's ordinary outer air.
    fun top(value: Int) = 4f * normalized(value).coerceAtMost(DEFAULT) / DEFAULT
    fun bottom(value: Int) = 7f * normalized(value).coerceAtMost(DEFAULT) / DEFAULT
}
