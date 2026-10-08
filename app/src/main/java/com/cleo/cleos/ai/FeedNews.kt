package com.cleo.cleos.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import java.util.concurrent.ConcurrentHashMap

data class FeedNewsItem(val title: String, val url: String, val summary: String, val publishedAt: Long, val source: String, val category: String = "")

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
    private data class Cached(val at: Long, val items: List<FeedNewsItem>)
    private val cache = ConcurrentHashMap<String, Cached>()
    suspend fun check(customUrl: String, selection: String): FeedNewsBatch =
        FeedNewsSources.collect(FeedNewsSources.groups(selection, customUrl), read = ::read)
    suspend fun latest(customUrl: String, selection: String = "", excluded: Set<String> = emptySet(), maximum: Int = 12): List<FeedNewsItem> {
        val batch = FeedNewsSources.collect(FeedNewsSources.groups(selection, customUrl), excluded, maximum, ::read)
        check(batch.items.isNotEmpty()) {
            if (batch.statuses.any { it.count > 0 }) "近期资讯已经分享过了，稍后再逛逛吧"
            else "这些来源暂时读不到近期内容，可以检查来源或稍后再试"
        }
        return batch.items
    }
    private suspend fun read(source: FeedNewsSource): List<FeedNewsItem> {
        val now = System.currentTimeMillis()
        cache[source.url]?.takeIf { now - it.at in 0..300_000 }?.let { return it.items }
        val items = http.fetch(Request.Builder().url(source.url).header("Accept", "application/rss+xml, application/atom+xml, text/xml").build()) { response ->
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
            FeedNewsParser.parse(content.toString(), source.name, System.currentTimeMillis())
        }
        if (items.isNotEmpty()) {
            cache[source.url] = Cached(now, items)
            if (cache.size > 16) cache.entries.minByOrNull { it.value.at }?.let { cache.remove(it.key, it.value) }
        }
        return items
    }
}
