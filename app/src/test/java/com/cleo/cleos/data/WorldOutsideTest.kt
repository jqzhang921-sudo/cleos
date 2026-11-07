package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WorldOutsideTest {
    private val source = WorldOutside.sources.first()
    private fun rss(items: String) = """<?xml version="1.0"?><rss version="2.0"><channel>$items</channel></rss>"""
    private fun item(link: String = "https://news.un.org/zh/story/1", summary: String = "&lt;p&gt;风与树。&lt;/p&gt;", title: String = "合成见闻") =
        "<item><title>$title</title><link>$link</link><description>$summary</description><pubDate>Tue, 20 Oct 2026 08:00:00 GMT</pubDate></item>"
    private fun parse(raw: String) = WorldOutside.parse(raw.toByteArray(), source)
    @Test fun rssIsOnlySummaryWithPublicLinkAndSourceDate() {
        val result = parse(rss(item())).single()
        assertEquals("合成见闻", result.title); assertEquals("https://news.un.org/zh/story/1", result.url)
        assertEquals("订阅摘要（未读取网页全文）\n\n风与树。", result.summary)
        assertTrue(result.published.contains("2026"))
        val article = result.article(10, source, 100)
        assertEquals("outside", article.space); assertEquals(0, article.readOffset)
        WorldReading.validateBackup(setOf(10), listOf(article.copy(id = 1)), emptyList())
    }
    @Test fun htmlIsTextOnlyWithoutScriptImageIframeOrResourceFetch() {
        assertEquals("A & B\n\nwind 😺", WorldOutside.plain("<p>A &amp; B</p><script>bad()</script><style>bad</style><iframe>bad</iframe><img src='https://bad.invalid/a'><p>wind &#x1f63a;</p>"))
        assertEquals("<literal>", WorldOutside.plain("&lt;literal&gt;"))
    }
    @Test fun atomUsesAlternateLinkAndSummaryNotFullContent() {
        val entry = parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Science</title><link rel="self" href="https://bad.invalid"/><link rel="alternate" href="https://www.nasa.gov/story/1"/><summary>wind</summary><content>FULL_BODY_CANARY</content><published>2026-10-20T08:00:00Z</published></entry></feed>""").single()
        assertTrue(entry.summary.endsWith("wind")); assertFalse(entry.summary.contains("FULL_BODY")); assertTrue(entry.url.contains("nasa.gov"))
    }
    @Test fun invalidPrivateCredentialAndUnapprovedLinksAreNotImported() {
        for (url in listOf("http://news.un.org/a", "https://127.0.0.1/a", "https://news.un.org.evil.invalid/a", "https://user:pw@news.un.org/a", "https://news.un.org:444/a", "file:///a", "javascript:alert(1)", "https://evil.invalid/a")) {
            assertFalse(url, WorldOutside.validUrl(url)); assertTrue(url, parse(rss(item(url))).isEmpty())
        }
        assertFalse(WorldOutside.validUrl("https://news.un.org/a#anchor"))
        assertTrue(parse(rss(item("https://news.un.org/a#anchor"))).single().url.endsWith("/a"))
    }
    @Test fun duplicatesEmptyAndMissingSummariesAreSkippedAndCountBounded() {
        assertEquals(1, parse(rss(item() + item())).size)
        assertTrue(parse(rss(item(summary = "") + item(title = ""))).isEmpty())
        assertEquals(20, parse(rss((1..25).joinToString("") { item("https://news.un.org/zh/story/$it") })).size)
    }
    @Test fun truncatedSummaryHasScopeMarkerAndSafeUtf16Boundary() {
        val summary = parse(rss(item(summary = "风".repeat(2699) + "😺" + "风".repeat(50)))).single().summary
        assertTrue(summary.endsWith("〔摘要较长，仅保留开头片段〕")); assertTrue(summary.length < WorldReading.SEGMENT)
        assertFalse(summary.any { it.isSurrogate() })
    }
    @Test fun xmlEntitiesDtdOversizeAndTooDeepDocumentsFail() {
        for (raw in listOf("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///secret'>]>" + rss(item(summary = "&x;")),
            "<!DOCTYPE rss SYSTEM 'https://bad.invalid/dtd'>" + rss(item()),
            "x".repeat(524289), "<html/>", rss("<x>".repeat(10_000) + "</x>".repeat(10_000))))
            try { parse(raw); fail("Rejected feed accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun legacyDefaultsAndBackupKeepScopeAndRefuseForgedSourcesOrSpaceLinks() {
        val legacy = Json.decodeFromString<WorldArticleEntity>("""{"id":1,"companionId":10,"title":"old","source":"user","content":"wind","fingerprint":"${WorldReading.fingerprint("wind")}","createdAt":1}""")
        assertEquals("study", legacy.space); assertTrue(legacy.originUrl.isEmpty())
        val article = parse(rss(item())).single().article(10, source, 100).copy(id = 2)
        assertEquals(article, Json.decodeFromString<WorldArticleEntity>(Json.encodeToString(article)))
        for (bad in listOf(article.copy(space = "wrong"), article.copy(originUrl = "https://evil.invalid"), article.copy(published = "bad\n"), legacy.copy(originUrl = article.originUrl)))
            try { WorldReading.validateBackup(setOf(10), listOf(bad), emptyList()); fail() } catch (_: IllegalArgumentException) { }
        val wish = WorldWishEntity(1, 10, "风", "", "想读", WorldWishes.identity("风"), "run", 1, "added", 2, article.id)
        try { WorldWishes.validateBackup(listOf(wish), listOf(article), setOf(10)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun feedHttpRequestContainsNoModelAuthorizationAndRedirectIsRejected() = runBlocking {
        var captured: Request? = null
        val http = OkHttpClient.Builder().addInterceptor { chain -> captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "http://127.0.0.1/secret").body("".toResponseBody("text/plain".toMediaType())).build()
        }.build()
        try {
            try { WorldOutside(http).fetch("un"); fail() } catch (_: IllegalStateException) { }
            assertEquals(source.url, captured!!.url.toString()); assertNull(captured!!.header("Authorization")); assertNull(captured!!.header("Cookie"))
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }
    @Test fun outsideCapacityIsSeparateFromBooksAndDuplicateSourceUrlsAreRejected() {
        val outside = parse(rss(item())).single().article(10, source, 1)
        val items = (1..60).map { i -> outside.copy(id = i.toLong(), originUrl = "https://news.un.org/zh/story/$i", content = "摘要 $i", fingerprint = WorldReading.fingerprint("摘要 $i")) }
        val books = (61..110).map { i -> WorldArticleEntity(i.toLong(), 10, "书 $i", "合成", "书 $i", WorldReading.fingerprint("书 $i"), 1) }
        WorldReading.validateBackup(setOf(10), items + books, emptyList())
        for (bad in listOf(items + outside.copy(id = 111, originUrl = "https://news.un.org/zh/story/111"),
            listOf(items.first(), items[1].copy(originUrl = items.first().originUrl))))
            try { WorldReading.validateBackup(setOf(10), bad, emptyList()); fail() } catch (_: IllegalArgumentException) { }
    }
}
