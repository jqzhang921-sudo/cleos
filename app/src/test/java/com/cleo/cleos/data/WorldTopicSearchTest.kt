package com.cleo.cleos.data

import com.cleo.cleos.data.db.WorldArticleEntity
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WorldTopicSearchTest {
    private fun page(id: Int, text: String = "潮汐是海面的周期性变化。", url: String = "https://zh.wikipedia.org/wiki/Tide_$id", index: Int = id, ns: Int = 0) = buildJsonObject {
        put("pageid", id); put("ns", ns); put("index", index); put("title", "潮汐 $id"); put("fullurl", url); put("extract", text)
    }
    private fun response(vararg pages: JsonObject) = buildJsonObject {
        put("batchcomplete", true); putJsonObject("query") { put("pages", JsonArray(pages.toList())) }
    }.toString().toByteArray()
    private fun bad(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }

    @Test fun queryIsOneEncodedParameterOnFixedPublicHostAndLeadOnly() {
        val q = "潮汐 & action=delete / ? 😺"
        val url = WorldTopicSearch.url(q)
        assertEquals("zh.wikipedia.org", url.host); assertEquals("/w/api.php", url.encodedPath)
        assertEquals(q, url.queryParameter("gsrsearch")); assertEquals(listOf("query"), url.queryParameterValues("action"))
        assertEquals("0", url.queryParameter("gsrnamespace")); assertEquals("3", url.queryParameter("gsrlimit"))
        assertEquals("1", url.queryParameter("exintro")); assertEquals("1", url.queryParameter("explaintext"))
        assertEquals("1200", url.queryParameter("exchars")); assertNull(url.queryParameter("images"))
        assertEquals("潮汐", WorldTopicSearch.query("  潮汐  "))
        listOf("", " ", "x".repeat(161), "a\nb", "a\u0000b", "\uD83D").forEach { bad { WorldTopicSearch.url(it) } }
    }
    @Test fun extractsPreserveRankAndScopeWithoutClaimingToReadFullArticle() {
        val result = WorldTopicSearch.parse(response(page(2), page(1))).first()
        assertEquals("潮汐 1", result.title); assertEquals("", result.published)
        assertEquals("百科导言摘要（未读取完整条目或图片）\n\n潮汐是海面的周期性变化。", result.summary)
        val article = result.article(10, WorldTopicSearch.source, 1).copy(id = 1, searchQuery = "潮汐")
        WorldReading.validateBackup(setOf(10), listOf(article), emptyList())
        assertEquals(article, Json.decodeFromString<WorldArticleEntity>(Json.encodeToString(article)))
    }
    @Test fun dropsPrivateForeignCredentialRedirectOrNonArticleLinksAndEmptyResults() {
        for (url in listOf("http://zh.wikipedia.org/wiki/Tide", "https://en.wikipedia.org/wiki/Tide", "https://127.0.0.1/wiki/Tide",
            "https://zh.wikipedia.org.evil.invalid/wiki/Tide", "https://x:y@zh.wikipedia.org/wiki/Tide", "https://zh.wikipedia.org:444/wiki/Tide",
            "https://zh.wikipedia.org/w/api.php", "https://zh.wikipedia.org/wiki/Tide?a=1", "https://zh.wikipedia.org/wiki/Tide#section")) {
            assertFalse(url, WorldTopicSearch.validUrl(url)); assertTrue(WorldTopicSearch.parse(response(page(1, url = url))).isEmpty())
        }
        assertTrue(WorldTopicSearch.parse(response(page(1, text = ""), page(2, ns = 1))).isEmpty())
        assertTrue(WorldTopicSearch.parse("{\"batchcomplete\":true}".toByteArray()).isEmpty())
        assertEquals(1, WorldTopicSearch.parse(response(page(1), page(1))).size)
        assertEquals(3, WorldTopicSearch.parse(response(*(1..6).map { page(it) }.toTypedArray())).size)
    }
    @Test fun failsMalformedApiErrorsWarningsOversizeDepthAndBadUtf8InsteadOfReturningEmptySuccess() {
        listOf("{}", "{\"error\":{}}", "{\"warnings\":{}}", "{\"query\":{}}", "[1]", "[".repeat(1000) + "]".repeat(1000),
            "x".repeat(131073)).forEach { bad { WorldTopicSearch.parse(it.toByteArray()) } }
        try { WorldTopicSearch.parse(byteArrayOf(0xc3.toByte(), 0x28)); fail() } catch (_: java.nio.charset.CharacterCodingException) { }
    }
    @Test fun capsTextAtSafeUtf16BoundaryAndNeverFollowsEmbeddedResources() {
        val extract = "a".repeat(1499) + "😺" + "a".repeat(900)
        val result = WorldTopicSearch.parse(response(page(1, text = extract))).single()
        assertEquals("a".repeat(1499), result.summary.substringAfter("\n\n"))
        val markup = WorldTopicSearch.parse(response(page(1, text = "<p>潮汐</p><img src='https://bad.invalid'><script>secret</script>"))).single().summary
        assertFalse(markup.contains("bad.invalid")); assertFalse(markup.contains("secret"))
    }
    @Test fun backupRequiresQueryAndWikiAttributionAndKeepsOldBooksUnchanged() {
        val a = WorldTopicSearch.parse(response(page(1))).single().article(10, WorldTopicSearch.source, 1).copy(id = 1, searchQuery = "潮汐")
        for (broken in listOf(a.copy(searchQuery = ""), a.copy(source = "NASA"), a.copy(published = "2026"), a.copy(space = "study"),
            a.copy(originUrl = "https://www.nasa.gov/a"), a.copy(searchQuery = " 潮汐 ")))
            bad { WorldReading.validateBackup(setOf(10), listOf(broken), emptyList()) }
        val old = Json.decodeFromString<WorldArticleEntity>("""{"id":1,"companionId":10,"title":"旧书","source":"用户提供","content":"风","fingerprint":"${WorldReading.fingerprint("风")}","createdAt":1}""")
        assertEquals("", old.searchQuery); WorldReading.validateBackup(setOf(10), listOf(old), emptyList())
    }
    @Test fun feedParserCannotImportWikiLinksWithoutSearchProvenance() {
        val feed = """<rss><channel><item><title>Tide</title><link>https://zh.wikipedia.org/wiki/Tide</link><description>Public summary</description></item></channel></rss>"""
        assertTrue(WorldOutside.parse(feed.toByteArray(), WorldOutside.sources.first()).isEmpty())
        assertTrue(WorldOutside.validUrl("https://zh.wikipedia.org/wiki/Tide"))
    }
    @Test fun requestHasNoModelAuthorizationOrCookieAndRejectsRedirectAndServerErrors() = runBlocking {
        for (code in listOf(200, 302, 503)) {
            var request: Request? = null
            val http = OkHttpClient.Builder().addInterceptor { chain -> request = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                    .header("Location", "https://bad.invalid/").body(String(response(page(1))).toResponseBody("application/json".toMediaType())).build()
            }.build()
            try {
                if (code == 200) assertEquals(1, WorldTopicSearch(http).search("潮汐").size)
                else try { WorldTopicSearch(http).search("潮汐"); fail() } catch (_: IllegalStateException) { }
                assertNull(request!!.header("Authorization")); assertNull(request!!.header("Cookie"))
                assertEquals("潮汐", request!!.url.queryParameter("gsrsearch"))
            } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
        }
    }
}
