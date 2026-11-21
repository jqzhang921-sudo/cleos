package com.cleo.cleos.ai

/** A line shown during one call. This history lives with the call, separately from its saved record. */
data class CallCaption(val id: Long, val fromUser: Boolean, val text: String)

internal const val CALL_CAPTION_LIMIT = 120

internal fun List<CallCaption>.withCaption(text: String, fromUser: Boolean): List<CallCaption> {
    if (text.isBlank()) return this
    val line = CallCaption((lastOrNull()?.id ?: 0L) + 1L, fromUser, text)
    return takeLast(CALL_CAPTION_LIMIT - 1) + line
}
