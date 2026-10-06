package com.cleo.cleos.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BubbleBackgroundTest {
    @Test fun oldEmptyDataUsesUsableDefaults() {
        val restored = Json.decodeFromString<BubbleBackground>("{}")
        assertEquals("glass", restored.material)
        assertEquals(75, restored.opacity)
        assertEquals(3, restored.softness)
        assertTrue(Json.decodeFromString<Map<String, BubbleBackground>>("{}").isEmpty())
    }

    @Test fun ownersAndThemesSurviveSerializationIndependently() {
        val me = BubbleBackground.key(true, 1L, "peach")
        val ta = BubbleBackground.key(false, 1L, "peach")
        val other = BubbleBackground.key(false, 2L, "peach")
        val anotherTheme = BubbleBackground.key(true, 1L, "blue")
        val data = mapOf(me to BubbleBackground(opacity = 30), ta to BubbleBackground(opacity = 90),
            other to BubbleBackground(material = "soft"), anotherTheme to BubbleBackground(direction = 3))
        val restored = Json.decodeFromString<Map<String, BubbleBackground>>(Json.encodeToString(data))
        assertEquals(4, restored.size)
        assertEquals(30, restored[me]?.opacity)
        assertEquals(90, restored[ta]?.opacity)
        assertEquals("soft", restored[other]?.material)
        assertEquals(3, restored[anotherTheme]?.direction)
    }

    @Test fun malformedImportedValuesStayWithinRendererBounds() {
        val imported = Json.decodeFromString<BubbleBackground>("""{"material":"unknown","opacity":-50,"softness":999,"direction":99,"startColor":0} """).normalized()
        assertEquals("glass", imported.material)
        assertEquals(15, imported.opacity)
        assertEquals(8, imported.softness)
        assertEquals(3, imported.direction)
        assertEquals(0xFF000000.toInt(), imported.startColor)
    }
}
