package com.cleo.cleos.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GroupImagesHttpTest {
    @Test fun textOnlyApiRefusalRemovesActualImagePartsOnTheWire() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val failure = AtomicReference<Throwable?>()
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true, name = "group-image-http-fixture") {
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
                    val body = if (round == 0) """{"error":{"message":"messages.content.type 参数非法，取值范围 ['text']"}}"""
                        else "data: {\"choices\":[{\"delta\":{\"content\":\"This turn contains text only\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val status = if (round == 0) "400 Bad Request" else "200 OK"
                    val type = if (round == 0) "application/json" else "text/event-stream"
                    peer.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                } }
            } catch (e: Throwable) { failure.set(e) }
        }
        val http = OkHttpClient.Builder().readTimeout(4, TimeUnit.SECONDS).build()
        try {
            val client = ChatClient(http, diagnostic = {})
            val endpoint = ApiEndpoint("http://127.0.0.1:${server.localPort}/v1", "synthetic-key", "synthetic-model")
            val picture = "data:image/jpeg;base64,c3ludGhldGlj"
            val input = listOf(ApiMessage("system", "Synthetic group"), ApiMessage("user",
                "[User]\n[本群附图：本次提供这条消息的第 1 张图片画面。]", images = listOf(picture)))
            var trace = GroupToolTrace()
            val reply = GroupToolReply.reply(input, emptyList(), false,
                { messages, specs, thinking -> client.stream(endpoint, messages, specs, thinking) },
                { error("No tool should run") }, onTrace = { trace = it }, timeoutMillis = 6000) {}
            worker.join(1000); failure.get()?.let { throw it }
            assertEquals("This turn contains text only", reply)
            assertEquals(2, requests.size)
            val parts = requests.first()["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
            assertEquals(picture, parts.single { it.jsonObject["type"]!!.jsonPrimitive.content == "image_url" }
                .jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            val retried = requests.last()["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
            assertTrue(retried.contains(GroupVisionRules.NO_PIXELS))
            assertFalse(requests.last().toString().contains("image_url"))
            assertFalse(requests.last().toString().contains("本群附图"))
            assertTrue(trace.notice.contains("本次不接受图片"))
            assertEquals(listOf(picture), input.last().images) // No model-wide or caller mutation.
        } finally {
            server.close(); worker.join(1000); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
