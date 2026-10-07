package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ReactionToolTest {
    private val calls = mutableListOf<List<Any>>()
    private val box = ToolBox(unused(), unused(), unused(), reactBack = { conversation, message, emoji, remove ->
        calls += listOf(conversation, message, emoji, remove)
    })

    @Test fun routesReactionAndRemovalWithoutChatNoise() = runBlocking {
        val added = box.run(ToolCall("a", "react_message", """{"message_id":42,"emoji":"😼","finish":true}"""), AppSettings(), 7)
        assertEquals("表情回应已完成。", added.result)
        assertEquals("", added.note)
        assertNull(box.activity("react_message"))
        box.run(ToolCall("b", "react_message", """{"message_id":42,"emoji":"😼","remove":true}"""), AppSettings(), 7)
        assertEquals(listOf(listOf(7L, 42L, "😼", false), listOf(7L, 42L, "😼", true)), calls)
    }

    @Test fun disabledOrInvalidCallsDoNotAct() = runBlocking {
        for ((arguments, settings) in listOf(
            """{"message_id":42,"emoji":"😼"}""" to AppSettings(tools = emptySet()),
            """{"emoji":"😼"}""" to AppSettings(),
            """{"message_id":42,"emoji":"hello"}""" to AppSettings()
        )) {
            val result = box.run(ToolCall("a", "react_message", arguments), settings, 7)
            assertNotEquals("表情回应已完成。", result.result)
        }
        assertTrue(calls.isEmpty())
        assertFalse(ToolSpecs.offered(emptySet()).any { it.name == "react_message" })
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> error("unused") } as T
}
