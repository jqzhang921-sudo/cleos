package com.cleo.cleos.data

import com.cleo.cleos.ai.WorldReplyException
import org.junit.Assert.*
import org.junit.Test

class WorldTopicChoiceTest {
    @Test fun completeTopicOrExplicitPause() {
        assertEquals("海边 潮汐", WorldTopicChoice.parse("""{"query":" 海边 潮汐 "}"""))
        assertNull(WorldTopicChoice.parse("""{"query":null}"""))
        assertEquals("🌊".repeat(80), WorldTopicChoice.parse("""{"query":"${"🌊".repeat(80)}"}"""))
    }
    @Test fun rejectsProsePartialExtraFieldsAndInvalidTerms() {
        listOf("{}", "{\"query\":", "reasoning {\"query\":\"sea\"}", "{\"query\":42}", "{\"query\":true}",
            "{\"query\":\"\"}", "{\"query\":\"x\",\"note\":\"private\"}", "{\"query\":\"a\\nb\"}",
            "{\"query\":\"${"x".repeat(161)}\"}", "{\"query\":\"\\ud800\"}").forEach { raw ->
            try { WorldTopicChoice.parse(raw); fail(raw) } catch (e: WorldReplyException) { assertEquals("choice_request", e.code) }
        }
    }
    @Test fun normalizedCooldownDoesNotConfuseDifferentTopics() {
        assertTrue(WorldTopicChoice.repeated("ＫＹＯＴＯ", listOf("kyoto")))
        assertTrue(WorldTopicChoice.repeated(" Sea   level ", listOf("sea level")))
        assertFalse(WorldTopicChoice.repeated("sea level", listOf("sea life")))
        assertEquals(86_400_000L, WorldTopicChoice.COOLDOWN_MS)
    }
}
