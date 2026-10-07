package com.cleo.cleos.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class BubblePreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "我的气泡",
    val myTheme: String = "glass",
    val taTheme: String = "glass",
    val myBackground: BubbleBackground? = null,
    val taBackground: BubbleBackground? = null,
    val paddingX: Int = 10,
    val paddingY: Int = 6,
    val decoration: BubbleDecoration = BubbleDecoration(),
    val myBubbleColor: Int? = null,
) {
    fun normalized() = copy(name = name.trim().take(64).ifBlank { "我的气泡" },
        myTheme = theme(myTheme), taTheme = theme(taTheme), myBackground = myBackground?.normalized(),
        taBackground = taBackground?.normalized(), paddingX = paddingX.coerceIn(6, 24), paddingY = paddingY.coerceIn(4, 16),
        decoration = decoration.normalized())
    fun apply(settings: AppSettings, companionId: Long): AppSettings {
        val p = normalized()
        val me = BubbleBackground.key(true, companionId, p.myTheme)
        val ta = BubbleBackground.key(false, companionId, p.taTheme)
        var backgrounds = settings.bubbleBackgrounds - me - ta
        p.myBackground?.let { backgrounds = backgrounds + (me to it) }
        p.taBackground?.let { backgrounds = backgrounds + (ta to it) }
        return settings.copy(myBubbleTheme = p.myTheme, taBubbleThemes = settings.taBubbleThemes + (companionId.toString() to p.taTheme),
            bubbleBackgrounds = backgrounds, bubblePaddingX = p.paddingX, bubblePaddingY = p.paddingY,
            bubbleDecoration = p.decoration, myBubble = p.myBubbleColor)
    }
    companion object {
        private fun theme(name: String) = name.takeIf { it in listOf("glass", "blue", "peach", "clear") } ?: "glass"
        fun capture(settings: AppSettings, companionId: Long, name: String): BubblePreset {
            val mine = theme(settings.myBubbleTheme)
            val ta = theme(settings.taBubbleThemes[companionId.toString()] ?: "glass")
            return BubblePreset(name = name, myTheme = mine, taTheme = ta,
                myBackground = settings.bubbleBackgrounds[BubbleBackground.key(true, companionId, mine)],
                taBackground = settings.bubbleBackgrounds[BubbleBackground.key(false, companionId, ta)],
                paddingX = settings.bubblePaddingX, paddingY = settings.bubblePaddingY,
                decoration = settings.bubbleDecoration, myBubbleColor = settings.myBubble).normalized()
        }
    }
}

fun BubbleDecoration.imageFiles() = (components.mapNotNull { it.image } + listOfNotNull(faceImage, starImage)).distinct()
fun BubbleDecoration.mapImages(transform: (String) -> String?) = copy(faceImage = faceImage?.let(transform),
    starImage = starImage?.let(transform), components = components.map { it.copy(image = it.image?.let(transform)) })
