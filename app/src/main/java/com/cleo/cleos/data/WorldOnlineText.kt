package com.cleo.cleos.data

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicit, bounded Wikisource page retrieval. No model client, credentials, images or link crawling. */
class WorldOnlineText(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).callTimeout(25, TimeUnit.SECONDS).build()) {
    data class Page(val id: Long, val revision: Long, val title: String) {
        init { require(id > 0 && revision > 0 && validText(title, 120)) }
        val url: String get() = "https://zh.wikisource.org/w/index.php?oldid=$revision"
        val source: String get() = "维基文库页面文字 · $title\n固定版本：$url\n文字：CC BY-SA 4.0；附加条款见原页\n$LICENSE"
    }
    data class Preview(val page: Page, val content: String)
    enum class FailureKind { CONNECTION, TIMEOUT, TLS, HTTP, FORMAT, TOO_LARGE }
    class Failure(val kind: FailureKind, val status: Int? = null) : IllegalStateException(kind.name)

    suspend fun search(raw: String, fullText: Boolean = false): List<Page> {
        val term = searchTerm(raw)
        if (fullText) {
            val bytes = request(searchUrl(term), SEARCH_BYTES)
            return decode { parseSearch(bytes) }
        }
        val exactBytes = request(titleUrl(term), SEARCH_BYTES)
        val canonical = decode { canonicalTitle(exactBytes, term) }
        val exact = decode { parseSearch(exactBytes) }
        if (exact.isNotEmpty()) {
            if (exact.size != 1 || exact.single().title != canonical) throw Failure(FailureKind.FORMAT)
            return exact
        }
        // A failed exact-title request never falls back; only a verified missing title does.
        val phrase = canonical.takeIf { validText(it, 100) } ?: term
        val bytes = request(titleSearchUrl(phrase), SEARCH_BYTES)
        return decode { parseSearch(bytes).filter { titleMatches(it.title, phrase) } }
    }
    suspend fun fetch(page: Page): Preview {
        val bytes = request(pageUrl(page), PAGE_BYTES)
        return decode { parsePage(bytes, page) }
    }

    private suspend fun <T> decode(block: () -> T): T = withContext(Dispatchers.Default) {
        try { block().also { ensureActive() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw if (e is Failure) e else Failure(FailureKind.FORMAT) }
    }

    private suspend fun request(url: HttpUrl, maximum: Long): ByteArray {
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Cleos-Bookroom/1.0 (https://github.com/jqzhang921-sudo/cleos)").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!it.isSuccessful) throw Failure(FailureKind.HTTP, it.code)
                            val body = it.body ?: throw Failure(FailureKind.FORMAT)
                            if (body.contentType()?.let { type -> type.type == "application" && type.subtype == "json" } != true)
                                throw Failure(FailureKind.FORMAT)
                            if (body.contentLength() > maximum) throw Failure(FailureKind.TOO_LARGE)
                            val stream = body.source(); stream.request(maximum + 1)
                            if (stream.buffer.size > maximum) throw Failure(FailureKind.TOO_LARGE)
                            stream.readByteArray()
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }

    companion object {
        const val LICENSE = "https://creativecommons.org/licenses/by-sa/4.0/"
        const val SEARCH_BYTES = 131_072L
        const val PAGE_BYTES = 524_288L
        private const val API = "https://zh.wikisource.org/w/api.php"
        const val HOME = "https://zh.wikisource.org/"
        /** Fixed wording only: never surface an exception's URL, query, headers or response body. */
        fun failureMessage(error: Exception, searching: Boolean): String {
            val step = if (searching) "查找" else "取回原文"
            val detail = when {
                error is Failure -> when (error.kind) {
                    FailureKind.CONNECTION -> "当前手机未能连接中文维基文库。可用浏览器检查文库能否打开，或改用文件导入"
                    FailureKind.TIMEOUT -> "文库连接超时，请稍后重试，或改用文件导入"
                    FailureKind.TLS -> "未能建立与文库的安全连接，请检查网络和系统时间"
                    FailureKind.HTTP -> when (error.status) {
                        429 -> "文库暂时限制请求，请稍后重试"
                        403 -> "文库拒绝了本次访问，可用浏览器检查来源，或改用文件导入"
                        in 500..599 -> "文库服务暂时出错，请稍后重试"
                        else -> "文库暂时无法提供本次资料，请稍后重试"
                    }
                    FailureKind.FORMAT -> "文库返回的资料未通过格式或版本核对，请稍后重试，或改用文件导入"
                    FailureKind.TOO_LARGE -> "文库返回的资料超出大小限制，请改用 PDF、EPUB 或 TXT 导入"
                }
                error is SocketTimeoutException || error is InterruptedIOException -> "文库连接超时，请稍后重试，或改用文件导入"
                error is SSLException -> "未能建立与文库的安全连接，请检查网络和系统时间"
                error is UnknownHostException || error is ConnectException || error is IOException ->
                    "当前手机未能连接中文维基文库。可用浏览器检查文库能否打开，或改用文件导入"
                else -> "未能完成文库资料核对，请稍后重试，或改用文件导入"
            }
            return "$step 未完成：$detail；本次没有保存。这不表示文库没有这本书。"
        }
        private fun validText(value: String, maximum: Int): Boolean = value.isNotBlank() && value == value.trim() &&
            value.length <= maximum && value.none { it.isISOControl() } && value.indices.all { i -> when {
                value[i].isHighSurrogate() -> i + 1 < value.length && value[i + 1].isLowSurrogate()
                value[i].isLowSurrogate() -> i > 0 && value[i - 1].isHighSurrogate()
                else -> true
            } }
        internal fun searchUrl(raw: String): HttpUrl {
            require(validText(raw.trim(), 100) && raw.none { it.isISOControl() })
            return API.toHttpUrl().newBuilder().addQueryParameter("action", "query")
                .addQueryParameter("generator", "search").addQueryParameter("gsrsearch", raw.trim())
                .addQueryParameter("gsrnamespace", "0").addQueryParameter("gsrlimit", "5")
                .addQueryParameter("prop", "info").addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2").build()
        }
        internal fun searchTerm(raw: String): String {
            require(validText(raw.trim(), 100) && raw.none { it.isISOControl() })
            var value = raw.trim()
            val pairs = listOf('《' to '》', '〈' to '〉', '「' to '」', '『' to '』', '“' to '”', '"' to '"')
            repeat(3) {
                if (value.length >= 2 && pairs.any { value.first() == it.first && value.last() == it.second })
                    value = value.substring(1, value.length - 1).trim()
            }
            require(validText(value, 100))
            return value
        }
        internal fun titleUrl(term: String): HttpUrl {
            require(validText(term, 100) && '|' !in term)
            return API.toHttpUrl().newBuilder().addQueryParameter("action", "query")
                .addQueryParameter("titles", term).addQueryParameter("converttitles", "1")
                .addQueryParameter("redirects", "1").addQueryParameter("prop", "info")
                .addQueryParameter("format", "json").addQueryParameter("formatversion", "2").build()
        }
        internal fun titleSearchUrl(term: String): HttpUrl {
            require(validText(term, 100))
            val literal = term.replace("\\", "\\\\").replace("\"", "\\\"")
            return searchUrl(term).newBuilder().setQueryParameter("gsrsearch", "intitle:\"$literal\"").build()
        }
        internal fun titleMatches(title: String, term: String): Boolean {
            fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
            return key(title).contains(key(term))
        }
        private fun canonicalTitle(bytes: ByteArray, term: String): String {
            val query = json(bytes, SEARCH_BYTES)["query"] as? JsonObject ?: return term
            var title = term
            for (field in listOf("normalized", "converted", "redirects")) {
                val entries = query[field] ?: continue
                val rows = entries as? JsonArray ?: throw Failure(FailureKind.FORMAT)
                require(rows.size <= 5)
                // The fixed source may normalize, convert a Chinese variant, and resolve a title redirect.
                // Only the chain starting at the requested title is accepted; no returned URL is opened.
                repeat(rows.size) {
                    val row = rows.map { it.jsonObject }.firstOrNull { it.text("from") == title }
                    if (row != null) title = requireNotNull(row.text("to")).also { require(validText(it, 120)) }
                }
            }
            return title
        }
        internal fun pageUrl(page: Page): HttpUrl = API.toHttpUrl().newBuilder()
            .addQueryParameter("action", "parse").addQueryParameter("oldid", page.revision.toString())
            .addQueryParameter("prop", "text|revid").addQueryParameter("disableeditsection", "1")
            .addQueryParameter("disabletoc", "1").addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2").build()

        private fun json(bytes: ByteArray, maximum: Long): JsonObject {
            require(bytes.size <= maximum)
            val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            var depth = 0; var quoted = false; var escaped = false
            raw.forEach { ch ->
                if (quoted) { if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false }
                else when (ch) { '"' -> quoted = true; '{', '[' -> require(++depth <= 32); '}', ']' -> require(--depth >= 0) }
            }
            val root = Json.parseToJsonElement(raw).jsonObject
            require("error" !in root && "warnings" !in root)
            return root
        }
        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        internal fun parseSearch(bytes: ByteArray): List<Page> {
            val root = json(bytes, SEARCH_BYTES)
            val query = root["query"] as? JsonObject
            if (query == null) { require(root["batchcomplete"]?.jsonPrimitive?.booleanOrNull == true); return emptyList() }
            val pages = query["pages"] as? JsonArray ?: error("缺少页面列表")
            require(pages.size <= 5)
            return pages.map { it.jsonObject }.sortedBy { it["index"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
                .mapNotNull { row ->
                    if (row["ns"]?.jsonPrimitive?.intOrNull != 0 || "missing" in row || "redirect" in row) return@mapNotNull null
                    runCatching { Page(requireNotNull(row["pageid"]?.jsonPrimitive?.longOrNull),
                        requireNotNull(row["lastrevid"]?.jsonPrimitive?.longOrNull), requireNotNull(row.text("title"))) }.getOrNull()
                }.distinctBy { it.id }
        }
        internal fun parsePage(bytes: ByteArray, expected: Page): Preview {
            val row = json(bytes, PAGE_BYTES)["parse"]?.jsonObject ?: error("缺少页面文字")
            require(row["pageid"]?.jsonPrimitive?.longOrNull == expected.id &&
                row["revid"]?.jsonPrimitive?.longOrNull == expected.revision && row.text("title") == expected.title)
            val html = requireNotNull(row.text("text"))
            // Rendered MediaWiki body only. Keep copyright notices and footnotes; never load embedded resources.
            val blocks = html.replace(Regex("</(?:tr|td|th|table|pre|blockquote|dd|dt)\\s*>", RegexOption.IGNORE_CASE), "\n")
            val plain = WorldReading.normalize(WorldOutside.plain(blocks))
            if (plain.length > WorldReading.MAX_TEXT) throw Failure(FailureKind.TOO_LARGE)
            require(plain.isNotBlank())
            return Preview(expected, plain)
        }
        /** Only our exact, immutable attribution format gets a source action after save / backup restore. */
        fun reference(source: String): String? {
            val lines = source.lines()
            if (lines.size != 4 || !lines[0].startsWith("维基文库页面文字 · ") ||
                !validText(lines[0].removePrefix("维基文库页面文字 · "), 120) ||
                lines[2] != "文字：CC BY-SA 4.0；附加条款见原页" || lines[3] != LICENSE) return null
            val url = lines[1].removePrefix("固定版本：")
            return url.takeIf { lines[1].startsWith("固定版本：") && it.matches(Regex("https://zh\\.wikisource\\.org/w/index\\.php\\?oldid=[1-9][0-9]{0,18}")) }
        }
    }
}
