package com.cleo.cleos.data

import kotlinx.serialization.Serializable

/** Small local editor state, independent of assets and message content. */
@Serializable
data class BubbleDecoration(
    val faceEmoji: String = "",
    val starEmoji: String = "",
    val faceImage: String? = null,
    val starImage: String? = null,
    val faceShape: String = "original",
    val starShape: String = "original",
    val faceEnabled: Boolean = true,
    val starsEnabled: Boolean = true,
    val faceDistance: Int = 0,
    val starDistance: Int = 0,
    val faceSize: Int = 24,
    val starSize: Int = 7,
    val faceCorner: String = "auto",
    val starCorner: String = "auto",
) {
    fun normalized() = copy(faceEmoji = cleanEmoji(faceEmoji), starEmoji = cleanEmoji(starEmoji), faceImage = safeFile(faceImage), starImage = safeFile(starImage),
        faceShape = faceShape.takeIf { it in shapes } ?: "original",
        starShape = starShape.takeIf { it in shapes } ?: "original",
        faceDistance = faceDistance.coerceIn(-8, 16), starDistance = starDistance.coerceIn(-8, 16),
        faceSize = faceSize.coerceIn(16, 32), starSize = starSize.coerceIn(4, if (safeFile(starImage) != null || cleanEmoji(starEmoji).isNotEmpty()) 32 else 12),
        faceCorner = faceCorner.takeIf { it in corners } ?: "auto",
        starCorner = starCorner.takeIf { it in corners } ?: "auto")

    companion object {
        private fun cleanEmoji(value: String) = value.trim().takeIf { it.length <= 64 && it.none { ch -> ch.isISOControl() } } ?: ""
        val shapes = listOf("original", "circle", "rounded")
        private fun safeFile(name: String?) = name?.takeIf { it.isNotBlank() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9._-]+")) }
        val corners = listOf("auto", "tl", "tr", "bl", "br") }
}
