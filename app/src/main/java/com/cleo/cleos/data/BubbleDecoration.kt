package com.cleo.cleos.data

import kotlinx.serialization.Serializable

/** Small local editor state, independent of assets and message content. */
@Serializable
data class BubbleDecoration(
    val faceEnabled: Boolean = true,
    val starsEnabled: Boolean = true,
    val faceSize: Int = 24,
    val starSize: Int = 7,
    val faceCorner: String = "auto",
    val starCorner: String = "auto",
) {
    fun normalized() = copy(faceSize = faceSize.coerceIn(16, 32), starSize = starSize.coerceIn(4, 12),
        faceCorner = faceCorner.takeIf { it in corners } ?: "auto",
        starCorner = starCorner.takeIf { it in corners } ?: "auto")

    companion object { val corners = listOf("auto", "tl", "tr", "bl", "br") }
}
