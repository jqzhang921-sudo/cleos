package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GroupToolsHttpTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> error("private access") } as T
    @Test fun realHttpSseToolCallRunsReadExecutorWithCorrectTaAndFeedsResultIntoNextRequest() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val failure = AtomicReference<Throwable?>()
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true, name = "group-tool-http-fixture") {
            try {
                repeat(2) { round -> server.accept().use { peer ->
                    peer.soTimeout = 4000
                    val input = peer.getInputStream()
                    fun line(): String = buildString {
                        while (true) { val byte = input.read(); if (byte < 0 || byte == 10) break; if (byte != 13) append(byte.toChar()) }
                    }
                    line()
                    var length = 0
                    while (true) { val header = line(); if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    require(length in 1..100_000)
                    requests += Json.parseToJsonElement(input.readNBytes(length).toString(Charsets.UTF_8)).jsonObject
                    val sse = if (round == 0) """
                        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"feed1","type":"function","function":{"name":"read_feed","arguments":"{\"author\":"}}]}}]}

                        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"self\"}"}}]},"finish_reason":"tool_calls"}]}

                        data: [DONE]

                    """.trimIndent() + "\n\n" else "data: {\"choices\":[{\"delta\":{\"content\":\"Read my own fixture post\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    val bytes = sse.toByteArray(Charsets.UTF_8)
                    peer.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                } }
            } catch (e: Throwable) { failure.set(e) }
        }
        var actor: Long? = null
        val feedDao = Proxy.newProxyInstance(FeedDao::class.java.classLoader, arrayOf(FeedDao::class.java)) { _, method, args ->
            check(method.name == "search"); actor = args!![0] as Long
            listOf(FeedPostEntity(9, actor!!, "own synthetic post", 0))
        } as FeedDao
        val box = ToolBox(unused<TodoDao>(), unused<DiaryDao>(), object : WeatherSource {
            override suspend fun report(city: String, days: Int): WeatherReport = error("no weather")
        }, feed = FeedBook(feedDao) { "Fixture speaker" })
        val settings = AppSettings(tools = setOf(ToolGroup.Feed))
        val groupTools = GroupTools(box) { settings }
        val http = OkHttpClient.Builder().readTimeout(4, TimeUnit.SECONDS).build()
        try {
            val client = ChatClient(http, diagnostic = {})
            val endpoint = ApiEndpoint("http://127.0.0.1:${server.localPort}/v1", "synthetic-key", "synthetic-model")
            var trace = GroupToolTrace()
            val result = GroupToolReply.reply(listOf(ApiMessage("user", "read own fixture post")), GroupToolRules.offered(settings), false,
                { messages, specs, thinking -> client.stream(endpoint, messages, specs, thinking) },
                { groupTools.run(it, 7, 20) }, onTrace = { trace = it }, timeoutMillis = 6000) {}
            worker.join(1000); failure.get()?.let { throw it }
            assertEquals("Read my own fixture post", result); assertEquals(20L, actor)
            assertEquals(2, requests.size)
            assertEquals("read_feed", requests[0]["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            val messages = requests[1]["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals("feed1", messages.last()["tool_call_id"]!!.jsonPrimitive.content)
            assertEquals("tool", messages.last()["role"]!!.jsonPrimitive.content)
            assertTrue(messages.last()["content"]!!.jsonPrimitive.content.contains("\"written_by_you\":true"))
            assertTrue(messages[messages.lastIndex - 1]["tool_calls"]!!.jsonArray.isNotEmpty())
            assertEquals("returned", trace.records.single().state)
        } finally {
            server.close(); worker.join(1000); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
