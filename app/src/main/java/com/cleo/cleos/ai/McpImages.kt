package com.cleo.cleos.ai

import com.cleo.cleos.data.MessageImage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

/** Only inline MCP images intended for the person: never fetch a URL from a tool result. */
object McpImages {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_PER_TOOL = 3
    const val MAX_PER_REPLY = 6
    const val MAX_EDGE = 1536
    private val formats = setOf("image/png", "image/jpeg", "image/webp")

    /** One instance belongs to the whole reply, shared by all of its tool round trips. */
    class Budget(limit: Int = MAX_PER_REPLY) {
        var remaining = limit.coerceIn(0, MAX_PER_REPLY)
            private set
        fun used(count: Int) { remaining = (remaining - count.coerceAtLeast(0)).coerceAtLeast(0) }
    }

    data class Delivery(val images: List<MessageImage>, val failures: Int, val receipt: String) {
        fun result(text: String): String = if (receipt.isBlank()) text else "$text\n$receipt"
    }

    /** Save independently so one broken picture doesn't discard the valid ones. No Base64 in history. */
    suspend fun deliver(
        result: JsonObject,
        source: String,
        budget: Int = MAX_PER_TOOL,
        save: suspend (ByteArray, String) -> MessageImage,
    ): Delivery {
        if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) return Delivery(emptyList(), 0, "")
        val images = ArrayList<MessageImage>()
        var failures = 0
        var hidden = 0
        var attempted = 0
        for (part in (result["content"] as? JsonArray).orEmpty()) {
            val o = part as? JsonObject ?: continue
            if ((o["type"] as? JsonPrimitive)?.contentOrNull != "image") continue
            val audience = (o["annotations"] as? JsonObject)?.get("audience") as? JsonArray
            if (audience != null && audience.none { (it as? JsonPrimitive)?.contentOrNull == "user" }) {
                hidden++
                continue
            }
            if (attempted++ >= budget.coerceIn(0, MAX_PER_TOOL)) {
                failures++
                continue
            }
            try {
                val mime = (o["mimeType"] as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
                require(mime in formats)
                val data = (o["data"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                // Reject before allocating a decoded copy. Whitespace is allowed, other junk isn't.
                require(data.isNotEmpty() && data.length <= ((MAX_BYTES + 2) / 3 * 4) + 4096)
                val compact = if (data.any { it.isWhitespace() }) data.filterNot { it.isWhitespace() } else data
                val bytes = Base64.getDecoder().decode(compact)
                require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
                images += save(bytes, mime).copy(source = source.take(160))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failures++
            }
        }
        val receipt = buildList {
            if (images.isNotEmpty()) add("Cleos 已在聊天中显示 ${images.size} 张服务返回的图片。图片来自外部服务，不代表现实拍摄；没有来源证据时不要声称是实拍或 AI 生成。")
            if (failures > 0) add("另有 $failures 张图片未能显示（格式、大小、数量限制或保存失败），不要声称已发出这些图片。支持 PNG、JPEG、WebP，单张最多 2 MiB，每次工具最多 3 张、每轮回复最多 6 张。")
            if (hidden > 0) add("另有 $hidden 张图片仅供模型参考，未展示给用户。")
        }.joinToString("\n")
        return Delivery(images, failures, receipt)
    }
}
