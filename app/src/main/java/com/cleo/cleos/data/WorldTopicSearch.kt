package com.cleo.cleos.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A bounded query of Chinese Wikipedia lead extracts, never arbitrary pages or images. */
class WorldTopicSearch(private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false)
    .followSslRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()) {
    suspend fun search(raw: String): List<WorldOutside.Entry> {
        val call = client.newCall(Request.Builder().url(url(raw)).header("Accept", "application/json")
            .header("User-Agent", "Cleos-Outside/1.0 (https://github.com/jqzhang921-sudo/cleos)").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val entries = response.use {
                            check(it.isSuccessful) { "百科检索暂时不可用" }
                            val stream = requireNotNull(it.body).source()
                            stream.request(MAX_BYTES + 1L)
                            require(stream.buffer.size <= MAX_BYTES) { "检索结果过大" }
                            parse(stream.readByteArray())
                        }
                        if (continuation.isActive) continuation.resume(entries)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }

    companion object {
        const val MAX_QUERY = 160
        const val SOURCE = "维基百科"
        const val MAX_BYTES = 131_072L
        val source = WorldOutside.Source("wikipedia", SOURCE, "https://zh.wikipedia.org/", "中文百科导言摘要")
        fun query(raw: String): String {
            require(raw.isNotBlank() && raw.length <= MAX_QUERY && raw.none { it.isISOControl() } &&
                raw.indices.all { i -> when {
                    raw[i].isHighSurrogate() -> i + 1 < raw.length && raw[i + 1].isLowSurrogate()
                    raw[i].isLowSurrogate() -> i > 0 && raw[i - 1].isHighSurrogate()
                    else -> true
                } }) { "请填写 1–160 字的检索词" }
            return raw.trim()
        }
        internal fun url(raw: String): HttpUrl = "https://zh.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", query(raw)).addQueryParameter("gsrnamespace", "0").addQueryParameter("gsrlimit", "3")
            .addQueryParameter("prop", "extracts|info").addQueryParameter("inprop", "url")
            .addQueryParameter("exintro", "1").addQueryParameter("explaintext", "1")
            .addQueryParameter("exchars", "1200").addQueryParameter("exlimit", "3")
            .addQueryParameter("format", "json").addQueryParameter("formatversion", "2").build()
        fun validUrl(raw: String): Boolean {
            if (raw.length !in 1..2000 || raw != raw.trim() || raw.any { it.isISOControl() }) return false
            val url = raw.toHttpUrlOrNull() ?: return false
            return url.isHttps && url.port == 443 && url.host == "zh.wikipedia.org" && url.username.isEmpty() &&
                url.password.isEmpty() && url.encodedPath.startsWith("/wiki/") && url.encodedPath.length > 6 &&
                url.query == null && url.fragment == null
        }
        private fun limit(text: String, max: Int): String {
            var end = minOf(text.length, max)
            if (!WorldReading.boundary(text, end)) end--
            return text.take(end)
        }
        internal fun parse(bytes: ByteArray): List<WorldOutside.Entry> {
            require(bytes.size <= MAX_BYTES)
            val raw = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            var depth = 0; var quoted = false; var escaped = false
            raw.forEach { char ->
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> require(++depth <= 32) { "检索结构过深" }
                    '}', ']' -> require(--depth >= 0)
                }
            }
            val root = Json.parseToJsonElement(raw).jsonObject
            require("error" !in root && "warnings" !in root) { "检索没有返回可确认的结果" }
            val result = root["query"] as? JsonObject
            if (result == null) { require(root["batchcomplete"]?.jsonPrimitive?.booleanOrNull == true); return emptyList() }
            val pages = (result["pages"] as? JsonArray) ?: error("检索结果格式不受支持")
            require(pages.size <= 10)
            return pages.map { it.jsonObject }.sortedBy { it["index"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
                .mapNotNull { row ->
                    fun text(key: String) = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                    val title = limit(WorldOutside.plain(text("title")), 120)
                    val link = text("fullurl")
                    val extract = WorldOutside.plain(text("extract"))
                    if (row["ns"]?.jsonPrimitive?.intOrNull != 0 || (row["pageid"]?.jsonPrimitive?.longOrNull ?: 0) <= 0 ||
                        "missing" in row || title.isBlank() || !validUrl(link) || extract.isBlank()) return@mapNotNull null
                    WorldOutside.Entry(title, link, "", WorldReading.normalize("百科导言摘要（未读取完整条目或图片）\n\n" + limit(extract, 1500)))
                }.distinctBy { it.url }.take(3)
        }
    }
}
