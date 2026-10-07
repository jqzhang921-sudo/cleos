package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class VisitToolTest {
    @Test fun disabledAndInvalidPlansNeverReachScheduler() = runBlocking {
        val calls = mutableListOf<Int>()
        val box = ToolBox(unused(), unused(), unused(), planVisit = { _, minutes -> calls += minutes; "recorded" })
        val enabled = AppSettings(tools = setOf(ToolGroup.FreeVisit))
        for ((args, settings) in listOf("""{"minutes":30}""" to AppSettings(tools = emptySet()),
            """{"minutes":0}""" to enabled, """{"minutes":-1}""" to enabled, "{}" to enabled)) {
            assertEquals("", box.run(ToolCall("a", "plan_next_visit", args), settings, 7).note)
        }
        assertTrue(calls.isEmpty())
        val accepted = box.run(ToolCall("a", "plan_next_visit", """{"minutes":45}"""), enabled, 7)
        assertEquals(listOf(45), calls)
        assertEquals("recorded", accepted.result)
        assertEquals("", accepted.note)
        assertNull(box.activity("plan_next_visit"))
        assertFalse(ToolSpecs.offered(setOf(ToolGroup.Later)).any { it.name == "plan_next_visit" })
    }
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> error("unused") } as T
}
