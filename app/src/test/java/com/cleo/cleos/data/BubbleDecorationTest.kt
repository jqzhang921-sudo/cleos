package com.cleo.cleos.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BubbleDecorationTest {
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
