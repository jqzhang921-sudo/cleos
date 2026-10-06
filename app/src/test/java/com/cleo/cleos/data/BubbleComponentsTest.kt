package com.cleo.cleos.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BubbleComponentsTest {
    @Test fun oldDecorationsRemainIntactAndComponentsRoundTripIndependently() {
        val old = Json.decodeFromString<BubbleDecoration>("{\"faceEmoji\":\"🥹\",\"starSize\":7}").normalized()
        assertEquals("🥹", old.faceEmoji)
        assertTrue(old.components.isEmpty())
        val next = old.copy(components = listOf(BubbleComponent("one", emoji = "🎀", corner = "tr", size = 32),
            BubbleComponent("two", image = "bubble-test.png", corner = "bl", offsetX = 18)))
        assertEquals(next, Json.decodeFromString<BubbleDecoration>(Json.encodeToString(next)).normalized())
    }
    @Test fun importedComponentsRejectPathsAndDeduplicateIds() {
        val d = BubbleDecoration(components = listOf(BubbleComponent("one", image = "../other.png", size = 900, offsetX = -999),
            BubbleComponent("one", emoji = "✨"), BubbleComponent("face", emoji = "🥹"))).normalized()
        assertEquals(1, d.components.size)
        assertNull(d.components[0].image)
        assertEquals(40, d.components[0].size)
        assertEquals(-64, d.components[0].offsetX)
    }
}
