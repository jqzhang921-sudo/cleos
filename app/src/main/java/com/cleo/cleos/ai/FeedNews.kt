package com.cleo.cleos.ai

import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class FeedNewsItem(val title: String, val url: String, val summary: String, val publishedAt: Long, val source: String)

/** Only bounded, dated feed excerpts go to the model, never instructions from a page. */
object FeedNewsParser {
    fun parse(xml: String, source: String, now: Long): List<FeedNewsItem> {
        require(xml.length <= 1000000 && !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "订阅内容无法读取" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val rss = doc.getElementsByTagName("item")
        val nodes = if (rss.length > 0) rss else doc.getElementsByTagNameNS("*", "entry")
        fun plain(text: String) = text.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        return (0 until minOf(nodes.length, 100)).mapNotNull { i ->
            val e = nodes.item(i) as? Element ?: return@mapNotNull null
            fun field(name: String): String = e.getElementsByTagNameNS("*", name).item(0)?.textContent.orEmpty().trim()
            val link = e.getElementsByTagNameNS("*", "link")
            val url = (0 until link.length).mapNotNull { n -> (link.item(n) as? Element)?.let {
                if (it.getAttribute("rel").let { r -> r.isEmpty() || r == "alternate" }) it.getAttribute("href").ifBlank { it.textContent.trim() } else null
            } }.firstOrNull { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
            val date = field("pubDate").ifBlank { field("published").ifBlank { field("updated") } }
            val at = runCatching { Instant.parse(date).toEpochMilli() }.getOrNull()
                ?: runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(date)?.time }.getOrNull()
                ?: return@mapNotNull null
            // Older stories remain dated, but cannot be offered as current news.
            if (at < now - 14L * 86400000 || at > now + 86400000) return@mapNotNull null
            val title = plain(field("title")).take(220)
            if (title.isBlank()) return@mapNotNull null
            FeedNewsItem(title, url.take(2000), plain(field("description").ifBlank { field("summary") }).take(450), at, source)
        }.distinctBy { it.url }.sortedByDescending { it.publishedAt }.take(12)
    }
}

class FeedNews(private val http: OkHttpClient) {
    suspend fun latest(customUrl: String): List<FeedNewsItem> {
        val sources = if (customUrl.isBlank()) listOf(
            "https://www.nasa.gov/news-release/feed/" to "NASA",
            "https://science.nasa.gov/feed/" to "NASA Science",
        ) else {
            require(customUrl.startsWith("https://")) { "订阅地址请使用 https://" }
            listOf(customUrl to "自选订阅")
        }
        for ((url, source) in sources) {
            try {
                val items = http.fetch(Request.Builder().url(url).header("Accept", "application/rss+xml, application/atom+xml, text/xml").build()) { response ->
                    check(response.isSuccessful) { "资讯源暂时不可用" }
                    val reader = requireNotNull(response.body).charStream()
                    val content = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        check(content.length + n <= 1000000) { "订阅内容过大" }
                        content.append(buffer, 0, n)
                    }
                    FeedNewsParser.parse(content.toString(), source, System.currentTimeMillis())
                }
                if (items.isNotEmpty()) return items
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (customUrl.isNotBlank()) throw IllegalStateException("这个订阅暂时读不到近期资讯，请检查地址或稍后再试", e) }
        }
        error("暂时读不到近期资讯，可以稍后重试或换一个 RSS 订阅")
    }
}
