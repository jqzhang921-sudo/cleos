package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GroupDeviceHttpTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> error("private access") } as T

    @Test fun systemCalendarToolTravelsThroughActualHttpAndItsResultReturnsToTheModel() = scenario(false)
    @Test fun alarmToolTravelsThroughActualHttpWithoutTouchingARealPhoneClock() = scenario(true)

    private fun scenario(alarm: Boolean) = runBlocking {
        val name = if (alarm) "set_alarm" else "read_calendar"
        val args = if (alarm) """{"time":"07:30","label":"fixture"}""" else """{"days":1}"""
        val requests = mutableListOf<JsonObject>()
        val failure = AtomicReference<Throwable?>()
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true, name = "group-device-http-fixture") {
            try {
                repeat(2) { round -> server.accept().use { peer ->
                    peer.soTimeout = 4000
                    val input = peer.getInputStream()
                    fun line(): String = buildString { while (true) {
                        val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) append(b.toChar())
                    } }
                    line(); var length = 0
                    while (true) { val h = line(); if (h.isEmpty()) break
                        if (h.startsWith("Content-Length:", true)) length = h.substringAfter(':').trim().toInt()
                    }
                    require(length in 1..100_000)
                    requests += Json.parseToJsonElement(input.readNBytes(length).toString(Charsets.UTF_8)).jsonObject
                    val delta = if (round == 0) buildJsonObject {
                        put("tool_calls", buildJsonArray { add(buildJsonObject {
                            put("index", 0); put("id", "device1"); put("type", "function")
                            put("function", buildJsonObject { put("name", name); put("arguments", args) })
                        }) })
                    } else buildJsonObject { put("content", "fixture completed") }
                    val chunk = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                        put("delta", delta); put("finish_reason", if (round == 0) "tool_calls" else "stop")
                    }) }) }
                    val bytes = "data: $chunk\n\ndata: [DONE]\n\n".toByteArray()
                    peer.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                } }
            } catch (e: Throwable) { failure.set(e) }
        }
        val now = ZonedDateTime.parse("2026-10-16T10:00:00+08:00")
        var reads = 0; var writes = 0
        val calendar = object : CalendarSource {
            override suspend fun events(from: Long, to: Long): List<CalEvent> {
                reads++; return listOf(CalEvent(11, "fixture-course", now.toInstant().toEpochMilli(), now.plusHours(1).toInstant().toEpochMilli(), false))
            }
            override suspend fun event(id: Long): CalEvent? = error("not offered")
            override suspend fun add(draft: EventDraft): Long = error("not offered")
            override suspend fun update(id: Long, draft: EventDraft) = error("not offered")
            override suspend fun delete(id: Long) = error("not offered")
        }
        val clock = object : AlarmSource {
            override fun setAlarm(hour: Int, minute: Int, label: String, days: List<Int>) {
                assertEquals(7, hour); assertEquals(30, minute); assertEquals("fixture", label); assertTrue(days.isEmpty()); writes++
            }
            override fun setTimer(seconds: Int, label: String) = error("not requested")
        }
        val box = ToolBox(unused(), unused(), unused(), alarms = clock, calendar = calendar,
            clock = { now.toInstant().toEpochMilli() }, zone = { ZoneId.of("Asia/Shanghai") })
        val setting = AppSettings(tools = setOf(if (alarm) ToolGroup.Alarm else ToolGroup.Calendar))
        val tools = GroupTools(box) { setting }
        val http = OkHttpClient.Builder().readTimeout(4, TimeUnit.SECONDS).build()
        try {
            val client = ChatClient(http, diagnostic = {})
            val endpoint = ApiEndpoint("http://127.0.0.1:${server.localPort}/v1", "synthetic-key", "synthetic-model")
            var trace = GroupToolTrace()
            val answer = GroupToolReply.reply(listOf(ApiMessage("user", "fixture request")), GroupToolRules.offered(setting), false,
                { messages, specs, thinking -> client.stream(endpoint, messages, specs, thinking) },
                { tools.run(it, 7, 20) }, onTrace = { trace = it }, timeoutMillis = 6000) {}
            worker.join(1000); failure.get()?.let { throw it }
            assertEquals("fixture completed", answer)
            assertEquals(if (alarm) 0 else 1, reads); assertEquals(if (alarm) 1 else 0, writes)
            val offered = requests.first()["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
            assertTrue(name in offered); assertFalse("add_event" in offered); assertFalse("write_diary" in offered)
            val result = requests.last()["messages"]!!.jsonArray.last().jsonObject
            assertEquals("tool", result["role"]!!.jsonPrimitive.content)
            assertEquals("device1", result["tool_call_id"]!!.jsonPrimitive.content)
            assertTrue(result["content"]!!.jsonPrimitive.content.contains(if (alarm) "闹钟定好了" else "fixture-course"))
            assertEquals(name, trace.records.single().name); assertEquals("returned", trace.records.single().state)
        } finally {
            server.close(); worker.join(1000); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
