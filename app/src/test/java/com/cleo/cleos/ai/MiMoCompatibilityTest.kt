package com.cleo.cleos.ai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MiMoCompatibilityTest {
    private val call = ToolCall("weather1", "get_weather", """{"city":"test"}""")
    private val history = listOf(ApiMessage("assistant", "earlier", reasoning = "earlier reasoning"),
        ApiMessage("user", "weather"), ApiMessage("assistant", "", listOf(call), reasoning = "current reasoning"),
        ApiMessage("tool", "synthetic weather", toolCallId = call.id))

    @Test fun documentedMiMoModelsRespectTheThinkingSwitchIncludingPrefixedNames() {
        for (model in listOf("mimo-v2.5", "mimo-v2.5-pro", "mimo-v2.6-flash", "mimo-v2.6-pro", "mimo-v2.6-pro-ultraspeed", " Xiaomi/MiMo-v2.6-flash ")) {
            assertTrue(model, Thinking.canSwitchOff(model)); assertTrue(model, Thinking.keepsToolReasoning(model))
            assertFalse(Thinking.thinksAlways(model))
        }
        for (model in listOf("mimo-v2.5-asr", "mimo-v2.5-tts", "mimo-vl", "mimo-unknown", "mimo-v2.6-flash-unknown")) {
            assertFalse(model, Thinking.canSwitchOff(model)); assertFalse(model, Thinking.keepsToolReasoning(model))
        }
    }

    @Test fun disabledThinkingDoesNotReplayReasoningAndKeepsToolResultPairing() {
        val body = requestBody("mimo-v2.6-flash", history, listOf(ToolSpecs.getWeather), thinking = false)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val sent = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertTrue(sent.none { "reasoning_content" in it })
        assertEquals(JsonNull, sent[2]["content"])
        assertEquals("weather1", sent[3]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals(1, body["tools"]!!.jsonArray.size)
    }

    @Test fun enabledOrDefaultMiMoThinkingPreservesAvailableAssistantReasoning() {
        for (thinking in listOf<Boolean?>(null, true)) {
            val body = requestBody("mimo-v2.6-flash", history, listOf(ToolSpecs.getWeather), thinking)
            val sent = body["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals("earlier reasoning", sent[0]["reasoning_content"]!!.jsonPrimitive.content)
            assertEquals("current reasoning", sent[2]["reasoning_content"]!!.jsonPrimitive.content)
            assertFalse("reasoning_content" in sent[1]); assertFalse("reasoning_content" in sent[3])
            assertEquals(thinking != null, "thinking" in body)
        }
    }
}
