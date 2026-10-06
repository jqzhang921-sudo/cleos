package com.cleo.cleos.data

import kotlinx.serialization.Serializable

@Serializable
data class BubbleBackground(
    val material: String = "glass",
    val gradient: Boolean = true,
    val startColor: Int = 0xFFDBE7F2.toInt(),
    val endColor: Int = 0xFFF9F8F4.toInt(),
    val direction: Int = 2,
    val opacity: Int = 75,
    val softness: Int = 3,
) {
    fun normalized() = copy(material = if (material == "soft") "soft" else "glass",
        startColor = startColor or 0xFF000000.toInt(), endColor = endColor or 0xFF000000.toInt(),
        direction = direction.coerceIn(0, 3), opacity = opacity.coerceIn(15, 100), softness = softness.coerceIn(0, 8))
    companion object {
        fun key(mine: Boolean, companionId: Long, theme: String) = if (mine) "me:$theme" else "ta:$companionId:$theme"
        fun defaults(theme: String) = when(theme) {
            "peach" -> BubbleBackground(startColor = 0xFFF5E2E7.toInt())
            "glass", "clear" -> BubbleBackground(gradient = false, startColor = 0xFFF9F8F4.toInt())
            else -> BubbleBackground()
        }
    }
}
