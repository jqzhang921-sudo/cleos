package com.cleo.cleos.ai

import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.McpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

class McpImagesTest {
    private fun image(data: String = "AQID", mime: String = "image/png", audience: String? = null) = buildJsonObject {
        put("type", "image"); put("mimeType", mime); put("data", data)
        audience?.let { put("annotations", Json.parseToJsonElement("""{"audience":$it}""")) }
    }
    private fun result(vararg content: JsonObject, error: Boolean = false) = buildJsonObject {
        put("content", JsonArray(content.toList())); put("isError", error)
    }

    @Test fun clientPreservesRealInlineContentAndDeliversWithoutASecondCall() = runBlocking {
        val methods = mutableListOf<String>()
        val payload = result(buildJsonObject { put("type", "text"); put("text", "来自服务的插图") }, image())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val json = Json.parseToJsonElement(body).jsonObject
            val method = json["method"]!!.jsonPrimitive.content.also { methods += it }
            val reply = buildJsonObject {
                put("jsonrpc", "2.0"); json["id"]?.let { put("id", it) }
                put("result", if (method == "tools/call") payload else buildJsonObject { put("protocolVersion", Mcp.PROTOCOL) })
            }
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val raw = McpClient(http, "test").callToolRaw(McpServer(id = "fixture", url = "https://fixture.invalid/mcp"), "picture", JsonObject(emptyMap()))
        val sent = McpImages.deliver(raw, "测试服务") { bytes, mime ->
            assertArrayEquals(byteArrayOf(1, 2, 3), bytes); assertEquals("image/png", mime)
            MessageImage("saved.png", 100, 80)
        }
        assertEquals(1, methods.count { it == "tools/call" })
        assertEquals("测试服务", sent.images.single().source)
        assertTrue(sent.result(Mcp.text(raw)).contains("已在聊天中显示 1 张"))
        assertFalse(sent.result(Mcp.text(raw)).contains("AQID"))
        assertEquals(sent.images, MessageImages.decode(MessageImages.encode(sent.images)))
        assertNull(MessageImages.decode("""[{"file":"old.jpg","width":10,"height":12}]""").single().source)
    }

    @Test fun errorAndAssistantOnlyImagesAreNeverDisplayed() = runBlocking {
        var saves = 0
        val save: suspend (ByteArray, String) -> MessageImage = { _, _ -> saves++; MessageImage("x.png", 1, 1) }
        val failed = McpImages.deliver(result(image(), error = true), "服务", save = save)
        assertTrue(failed.images.isEmpty()); assertEquals("", failed.receipt)
        val hidden = McpImages.deliver(result(image(audience = "[\"assistant\"]")), "服务", save = save)
        assertTrue(hidden.images.isEmpty()); assertTrue(hidden.receipt.contains("未展示给用户"))
        val visible = McpImages.deliver(result(image(audience = "[\"user\"]")), "服务", save = save)
        assertEquals(1, visible.images.size); assertEquals(1, saves)
    }

    @Test fun badDataAndUnsupportedFormatsDontReachTheImageDecoder() = runBlocking {
        val data = result(image("!!!"), image("", "image/png"), image("AQID", "image/svg+xml"))
        val sent = McpImages.deliver(data, "服务") { _, _ -> fail("must reject before decoding"); MessageImage("x.png", 1, 1) }
        assertEquals(3, sent.failures); assertTrue(sent.images.isEmpty())
        assertFalse(sent.receipt.contains("已在聊天中显示"))
    }

    @Test fun oversizedBase64IsRejectedBeforeSaving() = runBlocking {
        val encoded = Base64.getEncoder().encodeToString(ByteArray(McpImages.MAX_BYTES + 1))
        val sent = McpImages.deliver(result(image(encoded)), "服务") { _, _ -> fail("too large"); MessageImage("x", 1, 1) }
        assertEquals(1, sent.failures); assertTrue(sent.images.isEmpty())
    }

    @Test fun partiallyBrokenResultsStillDeliverRealFilesAndRespectRemainingBudget() = runBlocking {
        var saves = 0
        val sent = McpImages.deliver(result(image(), image(), image(), image()), "服务", budget = 2) { _, _ ->
            if (++saves == 1) throw java.io.IOException("private detail")
            MessageImage("good.png", 10, 10)
        }
        assertEquals(2, saves); assertEquals(1, sent.images.size); assertEquals(3, sent.failures)
        assertTrue(sent.receipt.contains("1 张")); assertTrue(sent.receipt.contains("3 张图片未能显示"))
        assertFalse(sent.receipt.contains("private detail"))
        val none = McpImages.deliver(result(image()), "服务", budget = 0) { _, _ -> fail("no budget"); MessageImage("x", 1, 1) }
        assertTrue(none.images.isEmpty()); assertEquals(1, none.failures)
    }

    @Test fun whitespaceWorksButSavingCancellationIsNotReportedAsAnImageFailure() = runBlocking {
        assertEquals(1, McpImages.deliver(result(image("AQ\nID")), "服务") { _, _ -> MessageImage("x.png", 1, 1) }.images.size)
        try {
            McpImages.deliver(result(image()), "服务") { _, _ -> throw CancellationException("stopped") }
            fail("must propagate cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun aReplyBudgetIsSharedAcrossToolRounds() = runBlocking {
        val budget = McpImages.Budget()
        var saved = 0
        repeat(5) {
            val delivery = McpImages.deliver(result(image(), image(), image()), "服务", budget.remaining) { _, _ ->
                MessageImage("saved-${++saved}.png", 1, 1)
            }
            budget.used(delivery.images.size)
        }
        assertEquals(6, saved); assertEquals(0, budget.remaining)
    }

    @Test fun payloadLimitCountsSingleByteAndBulkReadsAndBoundsAnSseLine() {
        assertEquals("abc", McpPayloadStream(ByteArrayInputStream("abc".toByteArray()), 3).bufferedReader().readText())
        assertThrows(McpException::class.java) {
            McpPayloadStream(ByteArrayInputStream("abcd".toByteArray()), 3).bufferedReader().readText()
        }
        val bytes = McpPayloadStream(ByteArrayInputStream("abcd".toByteArray()), 3)
        assertEquals('a'.code, bytes.read()); assertEquals(2, bytes.read(ByteArray(2)))
        assertThrows(McpException::class.java) { bytes.read() }
        val sse = "data: " + "x".repeat(100)
        assertThrows(McpException::class.java) { McpPayloadStream(ByteArrayInputStream(sse.toByteArray()), 20).bufferedReader().readLine() }
    }
}
