package com.cleo.cleos.data

import com.cleo.cleos.data.db.WorldArticleEntity
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public feed summaries only. No model credentials, cookies, redirects, images or page fetches. */
class WorldOutside(private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false)
    .followSslRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()) {
    data class Source(val id: String, val name: String, val url: String, val description: String)
    data class Entry(val title: String, val url: String, val published: String, val summary: String) {
        fun article(ta: Long, source: Source, at: Long) = WorldArticleEntity(companionId = ta, title = title,
            source = source.name, content = summary, fingerprint = WorldReading.fingerprint(summary), createdAt = at,
            space = "outside", originUrl = url, published = published)
    }
    suspend fun fetch(id: String): List<Entry> {
        val source = sources.single { it.id == id }
        val call = client.newCall(Request.Builder().url(source.url).header("Accept", "application/rss+xml, application/atom+xml, application/xml")
            .header("User-Agent", "Cleos-Outside/1.0").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val entries = response.use {
                            check(it.isSuccessful) { "订阅源暂时不可用" }
                            val stream = requireNotNull(it.body).source()
                            stream.request(MAX_BYTES + 1L)
                            check(stream.buffer.size <= MAX_BYTES) { "订阅内容过大" }
                            parse(stream.readByteArray(), source)
                        }
                        if (continuation.isActive) continuation.resume(entries)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }
    companion object {
        const val MAX_ITEMS = 60
        const val MAX_TEXT = 3_000
        private const val MAX_BYTES = 524_288L
        val sources = listOf(
            Source("un", "联合国新闻", "https://news.un.org/feed/subscribe/zh/news/all/rss.xml", "中文 · 全球与社会"),
            Source("nasa", "NASA", "https://www.nasa.gov/feed/", "英文 · 科学与宇宙"),
            Source("sspai", "少数派", "https://sspai.com/feed", "中文 · 数字生活与创作"),
            Source("gcores", "机核", "https://www.gcores.com/rss", "中文 · 游戏与文化，含节目简介"),
        )
        private val hosts = setOf("news.un.org", "www.nasa.gov", "science.nasa.gov", "www.jpl.nasa.gov")
        private val chineseHosts = mapOf("sspai" to setOf("sspai.com"), "gcores" to setOf("www.gcores.com"))
        fun validUrl(raw: String): Boolean = WorldTopicSearch.validUrl(raw) || validFeedUrl(raw)
        /** New publisher domains have a fixed label; retain historical labels on legacy sources. */
        fun validAttribution(source: String, raw: String): Boolean {
            val host = raw.toHttpUrlOrNull()?.host ?: return false
            val publisher = chineseHosts.entries.firstOrNull { host in it.value }?.key ?: return true
            return sources.single { it.id == publisher }.name == source
        }
        private fun validFeedUrl(raw: String, source: Source? = null): Boolean {
            if (raw.length !in 1..2000 || raw.any { it.isISOControl() } || raw != raw.trim()) return false
            val url = raw.toHttpUrlOrNull() ?: return false
            val allowed = if (source == null) hosts + chineseHosts.values.flatten() else chineseHosts[source.id] ?: hosts
            return url.isHttps && url.port == 443 && url.host in allowed && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null
        }
        private fun limited(text: String, count: Int): String = text.take(if (WorldReading.boundary(text, minOf(count, text.length))) minOf(count, text.length) else count - 1)
        private fun clean(text: String) = text.filter { !it.isISOControl() || it == '\n' || it == '\t' }.trim()
        /** No HTML rendering. Discard markup, scripts and images rather than fetching embedded resources. */
        internal fun plain(raw: String): String {
            require(raw.length <= MAX_BYTES)
            val text = raw.replace(Regex("<(script|style|iframe)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<br\\s*/?>|</(?:p|div|li|h[1-6])\\s*>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]*>"), "")
                .replace(Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|lt|gt|quot|apos|nbsp);")) { m ->
                    val entity = m.groupValues[1]
                    val point = when {
                        entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)
                        entity.startsWith('#') -> entity.drop(1).toIntOrNull()
                        else -> null
                    }
                    if (point != null && point in 1..0x10ffff && point !in 0xd800..0xdfff) String(Character.toChars(point))
                    else mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")[entity].orEmpty()
                }
            return clean(text).lineSequence().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
                .filter(String::isNotBlank).joinToString("\n\n")
        }
        /** A link to the article is navigation, not a readable summary. Keep genuine short text. */
        internal fun summary(raw: String): String {
            val linksOnly = setOf("点击查看原文", "查看原文", "阅读原文", "点击阅读原文", "阅读全文", "点击阅读全文", "readmore", "continuereading")
            return plain(raw).lineSequence().filterNot { line ->
                line.filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT) in linksOnly
            }.joinToString("\n").trim()
        }
        internal fun parse(bytes: ByteArray, source: Source): List<Entry> {
            require(source in sources && bytes.size <= MAX_BYTES)
            val raw = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            require(!raw.contains("<!DOCTYPE", true) && !raw.contains("<!ENTITY", true) && '\u0000' !in raw) { "订阅格式暂不支持" }
            // Reject pathological nesting before constructing a DOM (including on Android's parser).
            try {
                var depth = 0; var count = 0
                javax.xml.parsers.SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader.apply {
                    contentHandler = object : org.xml.sax.helpers.DefaultHandler() {
                        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes?) {
                            if (++depth > 64 || ++count > 50_000) throw org.xml.sax.SAXException("bounded feed")
                        }
                        override fun endElement(uri: String?, localName: String?, qName: String?) { depth-- }
                    }
                    setEntityResolver { _, _ -> org.xml.sax.InputSource(StringReader("")) }
                }.parse(org.xml.sax.InputSource(StringReader(raw)))
            } catch (e: org.xml.sax.SAXException) { throw IllegalArgumentException("订阅 XML 结构不受支持", e) }
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; setExpandEntityReferences(false) }
                .newDocumentBuilder().apply { setEntityResolver { _, _ -> org.xml.sax.InputSource(StringReader("")) } }
                .parse(org.xml.sax.InputSource(StringReader(raw)))
            var nodes = 0
            fun bounded(node: Node, depth: Int) { require(depth <= 64 && ++nodes <= 50_000); val children = node.childNodes; for (i in 0 until children.length) bounded(children.item(i), depth + 1) }
            bounded(doc, 0)
            val root = doc.documentElement
            require(root.localName in setOf("rss", "feed")) { "不是可支持的订阅源" }
            val atom = root.localName == "feed"
            val rows = doc.getElementsByTagNameNS("*", if (atom) "entry" else "item")
            require(rows.length <= 500) { "订阅条目过多" }
            fun Element.child(tag: String): Element? = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.firstOrNull { it.localName == tag }
            return (0 until rows.length).mapNotNull { index ->
                val row = rows.item(index) as Element
                val title = limited(plain(row.child("title")?.textContent.orEmpty()), 120)
                val link = if (atom) (0 until row.childNodes.length).mapNotNull { row.childNodes.item(it) as? Element }
                    .firstOrNull { it.localName == "link" && it.getAttribute("rel") in setOf("", "alternate") }?.getAttribute("href")
                    else row.child("link")?.textContent
                val url = link?.trim()?.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString().orEmpty()
                val text = summary(row.child(if (atom) "summary" else "description")?.textContent.orEmpty())
                if (title.isBlank() || !validFeedUrl(url, source) || text.isBlank()) return@mapNotNull null
                val published = limited(clean(row.child(if (atom) "published" else "pubDate")?.textContent.orEmpty()).replace(Regex("\\s+"), " "), 100)
                // Scope is part of the actual stored text; even a truncated summary is never labelled full text.
                val summary = "订阅摘要（未读取网页全文）\n\n" + limited(text, 2700) + if (text.length > 2700) "\n\n〔摘要较长，仅保留开头片段〕" else ""
                Entry(title, url, published, WorldReading.normalize(summary))
            }.distinctBy { it.url }.take(20)
        }
    }
}
