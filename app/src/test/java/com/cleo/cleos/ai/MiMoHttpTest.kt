package com.cleo.cleos.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MiMoHttpTest {
    @Test fun actualDefaultMiMoRequestDisablesThinkingAndCompletesToolLoop() = checkLoop(false)
    @Test fun explicitlyEnabledThinkingStillReplaysCurrentAndEarlierReasoning() = checkLoop(true)

    private fun checkLoop(thinking: Boolean) = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val diagnostics = Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<Throwable?>()
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true, name = "mimo-http-fixture") {
            try {
                repeat(2) { round -> server.accept().use { peer ->
                    peer.soTimeout = 4000
                    val input = peer.getInputStream()
                    fun line() = buildString { while (true) {
                        val byte = input.read(); if (byte < 0 || byte == 10) break
                        if (byte != 13) append(byte.toChar())
                    } }
                    line()
                    var length = 0
                    while (true) {
                        val header = line(); if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    require(length in 1..100_000)
                    requests += Json.parseToJsonElement(input.readNBytes(length).toString(Charsets.UTF_8)).jsonObject
                    val payload = if (round == 0)
                        """{"choices":[{"delta":{"reasoning_content":"synthetic reasoning","tool_calls":[{"index":0,"id":"mimo1","function":{"name":"get_weather","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
                    else """{"choices":[{"delta":{"content":"Done"},"finish_reason":"stop"}]}"""
                    val bytes = "data: $payload\n\ndata: [DONE]\n\n".toByteArray()
                    peer.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                } }
            } catch (e: Throwable) { failure.set(e) }
        }
        val http = OkHttpClient.Builder().readTimeout(4, TimeUnit.SECONDS).build()
        try {
            val client = ChatClient(http) { diagnostics += it }
            val endpoint = ApiEndpoint("http://127.0.0.1:${server.localPort}/v1", "synthetic-key", "mimo-v2.6-flash")
            val result = GroupToolReply.reply(listOf(ApiMessage("assistant", "earlier", reasoning = "earlier reasoning"), ApiMessage("user", "weather")),
                listOf(ToolSpecs.getWeather), thinking,
                { context, tools, mode -> client.stream(endpoint, context, tools, mode) },
                { ToolOutcome("synthetic weather", "fixture") }, timeoutMillis = 6000) {}
            worker.join(1000); failure.get()?.let { throw it }
            assertEquals("Done", result); assertEquals(2, requests.size)
            for (request in requests) assertEquals(if (thinking) "enabled" else "disabled", request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            val sent = requests.last()["messages"]!!.jsonArray.map { it.jsonObject }
            val called = sent.single { "tool_calls" in it }
            if (thinking) {
                assertEquals("synthetic reasoning", called["reasoning_content"]!!.jsonPrimitive.content)
                assertEquals("earlier reasoning", sent.first()["reasoning_content"]!!.jsonPrimitive.content)
            } else assertTrue(sent.none { "reasoning_content" in it })
            val tool = sent.single { it["role"]?.jsonPrimitive?.content == "tool" }
            assertEquals("mimo1", tool["tool_call_id"]!!.jsonPrimitive.content)
            assertEquals("synthetic weather", tool["content"]!!.jsonPrimitive.content)
            assertTrue(diagnostics.any { it.contains("event=response_structure") && it.contains("toolChunks=1 namedToolChunks=1") })
            assertTrue(diagnostics.none { it.contains("synthetic reasoning") || it.contains("synthetic-key") })
        } finally {
            server.close(); worker.join(1000)
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
