package com.cleo.cleos.data

import kotlinx.serialization.Serializable

@Serializable
data class BubbleComponent(
    val id: String,
    val emoji: String = "",
    val image: String? = null,
    val shape: String = "original",
    val enabled: Boolean = true,
    val size: Int = 24,
    val corner: String = "tl",
    val offsetX: Int = 0,
    val distance: Int = 0,
) {
    fun normalized(): BubbleComponent {
        val checked = BubbleDecoration(faceEmoji = emoji, faceImage = image, faceShape = shape,
            faceOffsetX = offsetX, faceDistance = distance, faceCorner = corner).normalized()
        return copy(emoji = checked.faceEmoji, image = checked.faceImage, shape = checked.faceShape,
            size = size.coerceIn(8, 40), corner = checked.faceCorner, offsetX = checked.faceOffsetX, distance = checked.faceDistance)
    }
}
