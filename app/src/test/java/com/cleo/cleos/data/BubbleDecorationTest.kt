package com.cleo.cleos.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BubbleDecorationTest {
    @Test fun customAnchorsStayTheSameAcrossOwnersThemesAndMessageLengths() {
        for (d in listOf(BubbleDecoration(faceEmoji = "😼", starEmoji = "✨", faceOffsetX = -12),
            BubbleDecoration(faceImage = "face.png", starImage = "star.png"))) {
            for (theme in listOf("glass", "blue", "peach", "clear")) {
                assertEquals("tl", d.resolvedCorner(true, theme))
                assertEquals("br", d.resolvedCorner(false, theme))
                assertEquals("tr", d.copy(faceCorner = "tr").resolvedCorner(true, theme))
                assertEquals("bl", d.copy(starCorner = "bl").resolvedCorner(false, theme))
            }
        }
    }
    @Test fun builtInDecorationsKeepTheirThemeAnchors() {
        assertEquals("tr", BubbleDecoration().resolvedCorner(true, "blue"))
        assertEquals("bl", BubbleDecoration().resolvedCorner(false, "blue"))
        assertEquals("tl", BubbleDecoration().resolvedCorner(true, "peach"))
        assertEquals("br", BubbleDecoration().resolvedCorner(false, "peach"))
    }
    @Test fun rejectsPathsFromImportedSettings() {
        for (name in listOf("../outside.png", "folder/image.png", "folder\\image.png", "..", "")) {
            val d = BubbleDecoration(faceImage = name, starImage = name, starSize = 32).normalized()
            assertNull(d.faceImage)
            assertNull(d.starImage)
            assertEquals(12, d.starSize)
        }
    }
    @Test fun imageSettingsRoundTripAndOldSettingsRemainCompatible() {
        val d = BubbleDecoration(faceImage = "bubble-test.png", starImage = "bubble-star.jpg", starSize = 24, faceShape = "circle")
        assertEquals(d, Json.decodeFromString<BubbleDecoration>(Json.encodeToString(d)).normalized())
        assertEquals(BubbleDecoration(), Json.decodeFromString<BubbleDecoration>("{}"))
    }
    @Test fun combinedEmojiSurvivesSettingsSerializationWithoutSplitting() {
        val d = BubbleDecoration(faceEmoji = "👩🏽‍🚀", starEmoji = "🇨🇳", starSize = 24)
        assertEquals(d, Json.decodeFromString<BubbleDecoration>(Json.encodeToString(d)).normalized())
        assertEquals("", BubbleDecoration(faceEmoji = "x\ny").normalized().faceEmoji)
    }
}
