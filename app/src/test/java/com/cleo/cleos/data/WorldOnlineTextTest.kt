package com.cleo.cleos.data

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WorldOnlineTextTest {
    private val page = WorldOnlineText.Page(260, 2620894, "桃花源記")
    private fun search(vararg rows: JsonObject) = buildJsonObject {
        put("batchcomplete", true); putJsonObject("query") { put("pages", JsonArray(rows.toList())) }
    }.toString().toByteArray()
    private fun row(id: Long = 260, revision: Long = 2620894, title: String = "桃花源記", ns: Int = 0, index: Int = 1) = buildJsonObject {
        put("pageid",id);put("lastrevid",revision);put("title",title);put("ns",ns);put("index",index)
    }
    private fun body(html: String = "<p>原文第一段。 &amp; 第二句。</p><p>下一段。😀</p>", id: Long = 260, revision: Long = 2620894, title: String = page.title) = buildJsonObject {
        putJsonObject("parse") { put("pageid",id);put("revid",revision);put("title",title);put("text",html) }
    }.toString().toByteArray()
    private fun reject(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} catch (_: java.nio.charset.CharacterCodingException) {} }

    @Test fun queryGoesOnlyToKnownSourceAndNeverToReturnedLinks() {
        val u=WorldOnlineText.searchUrl("  桃花源記 & 作者  ")
        assertEquals("https",u.scheme);assertEquals("zh.wikisource.org",u.host);assertEquals("/w/api.php",u.encodedPath)
        assertEquals("桃花源記 & 作者",u.queryParameter("gsrsearch"));assertEquals("0",u.queryParameter("gsrnamespace"));assertEquals("5",u.queryParameter("gsrlimit"))
        assertEquals("2620894",WorldOnlineText.pageUrl(page).queryParameter("oldid"))
        assertFalse(WorldOnlineText.pageUrl(page).queryParameterNames.contains("page"))
        for (q in listOf("", " ", "x".repeat(101), "a\nb", "x\uD800")) reject { WorldOnlineText.searchUrl(q) }
    }
    @Test fun resultsUseMainNamespacePositiveIdsAndImmutableRevisionsOnly() {
        val pages=WorldOnlineText.parseSearch(search(row(index=2),row(261,5,"另一章",index=1),row(id=0),row(ns=2),row(revision=0)))
        assertEquals(listOf("另一章","桃花源記"),pages.map { it.title })
        assertEquals(1,WorldOnlineText.parseSearch(search(row(),row())).size)
        assertTrue(WorldOnlineText.parseSearch("""{"batchcomplete":true}""".toByteArray()).isEmpty())
        assertTrue(WorldOnlineText.parseSearch(search(buildJsonObject { row().forEach { (k,v)->put(k,v) };put("redirect",true) })).isEmpty())
    }
    @Test fun unknownWarningErrorMissingAndOversizedResponsesAreNotSuccessfulEmptyResults() {
        for (raw in listOf("{}","{\"error\":{}}","{\"warnings\":{},\"batchcomplete\":true}","{\"query\":{}}", "[[]]")) reject { WorldOnlineText.parseSearch(raw.toByteArray()) }
        reject { WorldOnlineText.parseSearch(search(*Array(6){row(id=it+1L)})) }
        reject { WorldOnlineText.parseSearch(ByteArray(WorldOnlineText.SEARCH_BYTES.toInt()+1)) }
        reject { WorldOnlineText.parseSearch(("[".repeat(40)+"]".repeat(40)).toByteArray()) }
        reject { WorldOnlineText.parseSearch(byteArrayOf(0xc3.toByte(),0x28)) }
    }
    @Test fun actualTextIsPreservedWithParagraphsUnicodeAndInPageAttribution() {
        val parsed=WorldOnlineText.parsePage(body(),page)
        assertEquals("原文第一段。 & 第二句。\n\n下一段。😀",parsed.content)
        val text=WorldOnlineText.parsePage(body("<table><tr><td>作者：合成作者</td></tr></table><p>正文</p><p>作品版权声明：测试许可</p><script>LEAK</script><img src='https://bad.invalid/i'>"),page).content
        assertTrue(text.contains("作者：合成作者"));assertTrue(text.contains("作品版权声明：测试许可"));assertFalse(text.contains("LEAK"));assertFalse(text.contains("bad.invalid"))
    }
    @Test fun changedPageIdentityOrRevisionIsRejectedInsteadOfBeingAttributedToOldResult() {
        reject { WorldOnlineText.parsePage(body(id=261),page) };reject { WorldOnlineText.parsePage(body(revision=2620895),page) }
        reject { WorldOnlineText.parsePage(body(title="另一页"),page) };reject { WorldOnlineText.parsePage(body("<img src='a'>"),page) }
        reject { WorldOnlineText.parsePage(body("x".repeat(WorldReading.MAX_TEXT+1)),page) }
        reject { WorldOnlineText.parsePage(ByteArray(WorldOnlineText.PAGE_BYTES.toInt()+1),page) }
    }
    @Test fun attributionSurvivesSaveFormatWithoutArbitrarySourceUrlActions() {
        assertEquals(page.url,WorldOnlineText.reference(page.source));assertTrue(page.source.length<=500)
        assertTrue(page.source.contains(WorldOnlineText.LICENSE))
        assertNull(WorldOnlineText.reference(page.source.replace("zh.wikisource.org","private.invalid")))
        assertNull(WorldOnlineText.reference(page.source+"\nextra"));assertNull(WorldOnlineText.reference(page.source.replace("2620894","0")))
        assertNull(WorldOnlineText.reference(page.source.replace("CC BY-SA 4.0","All rights reserved")))
        reject { WorldOnlineText.Page(1,1,"bad\ntitle") }
    }
    @Test fun publicRequestsHaveNoModelKeysChatOrCookiesAndSearchDoesNotRetrieveText() = runBlocking {
        val seen=mutableListOf<Request>()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val r=chain.request();seen+=r
            assertNull(r.header("Authorization"));assertNull(r.header("Cookie"));assertNull(r.body)
            assertEquals("zh.wikisource.org",r.url.host)
            val data=if(r.url.queryParameter("action")=="query") search(row()) else body()
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(data.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build()
        try {
            val source=WorldOnlineText(http);val result=source.search("桃花源記")
            assertEquals(1,seen.size);assertEquals(listOf(page),result)
            assertEquals("原文第一段。 & 第二句。\n\n下一段。😀",source.fetch(result.single()).content);assertEquals(2,seen.size)
        } finally { http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll() }
    }
    @Test fun httpFailuresRedirectsHtmlAndOversizeAreNotImported() = runBlocking {
        for(mode in listOf("redirect","html","large","error")) {
            val hits=AtomicInteger()
            val http=OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                hits.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if(mode=="redirect")302 else if(mode=="error")503 else 200).message("test")
                    .header("Location","https://bad.invalid/book")
                    .body((if(mode=="large")ByteArray(WorldOnlineText.SEARCH_BYTES.toInt()+1) else search(row()))
                        .toResponseBody((if(mode=="html")"text/html" else "application/json").toMediaType())).build()
            }.build()
            try { try { WorldOnlineText(http).search("测试");fail(mode) } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} ;assertEquals(1,hits.get()) }
            finally { http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll() }
        }
    }
    @Test fun cancellingBlockedFetchCancelsHttpAndDoesNotReturnLatePreview() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);var call:Call?=null;var returned=false
        val http=OkHttpClient.Builder().eventListener(object:EventListener(){override fun callStart(c:Call){call=c}})
            .addInterceptor { chain -> entered.countDown();check(release.await(5,TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body().toResponseBody("application/json".toMediaType())).build() }.build()
        try {
            val task=launch { WorldOnlineText(http).fetch(page);returned=true }
            withContext(Dispatchers.IO){check(entered.await(5,TimeUnit.SECONDS))};task.cancelAndJoin()
            assertTrue(call?.isCanceled()==true);release.countDown();delay(50);assertFalse(returned)
        } finally { release.countDown();http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll() }
    }
    @Test fun networkFailuresHaveDistinctFixedWordingWithoutLeakingExceptionDetails() {
        val secret = "https://private.invalid/?key=SHOULD_NOT_APPEAR"
        val connection = WorldOnlineText.failureMessage(java.net.UnknownHostException(secret), true)
        assertTrue(connection.startsWith("查找 未完成"));assertTrue(connection.contains("未能连接中文维基文库"))
        assertTrue(connection.contains("不表示文库没有这本书"));assertFalse(connection.contains(secret))
        val timeout = WorldOnlineText.failureMessage(java.net.SocketTimeoutException(secret), false)
        assertTrue(timeout.startsWith("取回原文 未完成"));assertTrue(timeout.contains("超时"));assertFalse(timeout.contains(secret))
        assertTrue(WorldOnlineText.failureMessage(javax.net.ssl.SSLException(secret), true).contains("安全连接"))
        assertTrue(WorldOnlineText.failureMessage(WorldOnlineText.Failure(WorldOnlineText.FailureKind.HTTP, 429), true).contains("限制请求"))
        assertTrue(WorldOnlineText.failureMessage(WorldOnlineText.Failure(WorldOnlineText.FailureKind.HTTP, 503), true).contains("服务暂时出错"))
        assertTrue(WorldOnlineText.failureMessage(IllegalStateException(secret), true).contains("核对"))
        assertEquals("https://zh.wikisource.org/", WorldOnlineText.HOME)
    }
    @Test fun responseFailuresKeepHttpFormatAndSizeSeparateFromValidNoMatches() = runBlocking {
        val cases = listOf(
            Triple(429, "application/json", search(row())) to WorldOnlineText.FailureKind.HTTP,
            Triple(200, "text/html", "<html>access denied</html>".toByteArray()) to WorldOnlineText.FailureKind.FORMAT,
            Triple(200, "application/json", "{bad".toByteArray()) to WorldOnlineText.FailureKind.FORMAT,
            Triple(200, "application/json", ByteArray(WorldOnlineText.SEARCH_BYTES.toInt()+1)) to WorldOnlineText.FailureKind.TOO_LARGE,
        )
        for ((response, expected) in cases) {
            val http = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(response.first).message("test")
                .body(response.third.toResponseBody(response.second.toMediaType())).build() }.build()
            try {
                try { WorldOnlineText(http).search("合成无结果查询");fail("Expected classified error") }
                catch (e: WorldOnlineText.Failure) { assertEquals(expected, e.kind);if(expected==WorldOnlineText.FailureKind.HTTP)assertEquals(429,e.status) }
            } finally { http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll() }
        }
        val http = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("""{"batchcomplete":true}""".toResponseBody("application/json".toMediaType())).build() }.build()
        try { assertTrue(WorldOnlineText(http).search("合成无结果查询").isEmpty()) }
        finally { http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll() }
    }
    @Test fun transportFailureIsNotConvertedToEmptySearchResults() = runBlocking {
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { throw java.net.UnknownHostException("synthetic.invalid") }.build()
        try {
            try { WorldOnlineText(http).search("合成查询");fail("Expected connection failure") }
            catch (e: java.net.UnknownHostException) { assertTrue(WorldOnlineText.failureMessage(e, true).contains("未能连接")) }
        } finally { http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll() }
    }
    @Test fun bookTitlePunctuationIsRemovedWithoutAcceptingEmptyOrInjectedTitleLists() {
        assertEquals("窄门",WorldOnlineText.searchTerm("  《窄门》  "))
        assertEquals("桃花源记",WorldOnlineText.searchTerm("“《桃花源记》”"))
        for(q in listOf("《》"," ","a\nb"))reject{WorldOnlineText.searchTerm(q)}
        reject{WorldOnlineText.titleUrl("a|b")}
        val url=WorldOnlineText.titleUrl("桃花源记")
        assertEquals("桃花源记",url.queryParameter("titles"));assertEquals("1",url.queryParameter("converttitles"))
        assertEquals("1",url.queryParameter("redirects"));assertNull(url.queryParameter("generator"))
        assertEquals("intitle:\"a\\\" OR x\"",WorldOnlineText.titleSearchUrl("a\" OR x").queryParameter("gsrsearch"))
        assertTrue(WorldOnlineText.titleMatches("阿Ｑ正傳/第一章","Q正傳"))
        assertFalse(WorldOnlineText.titleMatches("某某装饰合同判决书","窄门"))
    }
    @Test fun exactTitleUsesSourceVariantConversionAndNeverFetchesUnrelatedBodyHits() = runBlocking {
        val requests=mutableListOf<Request>()
        val data=buildJsonObject { put("batchcomplete",true);putJsonObject("query") {
            putJsonArray("converted"){add(buildJsonObject{put("from","桃花源记");put("to","桃花源記")})}
            put("pages",JsonArray(listOf(row())))
        }}.toString()
        val http=OkHttpClient.Builder().addInterceptor { chain -> requests+=chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(data.toResponseBody("application/json".toMediaType())).build() }.build()
        try {
            assertEquals(listOf(page),WorldOnlineText(http).search("《桃花源记》"))
            assertEquals(1,requests.size);assertEquals("桃花源记",requests.single().url.queryParameter("titles"))
            assertNull(requests.single().url.queryParameter("gsrsearch"))
        } finally { http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll() }
    }
    @Test fun missingExactTitleOnlyFallsBackToTitlePhraseAndRejectsIncidentalMatches() = runBlocking {
        val requests=mutableListOf<Request>()
        val http=OkHttpClient.Builder().addInterceptor { chain -> val request=chain.request();requests+=request
            val data=if(request.url.queryParameter("titles")!=null) """{"batchcomplete":true,"query":{"pages":[{"ns":0,"title":"窄门","missing":true}]}}""".toByteArray()
                else search(row(title="某某装饰装修合同纠纷判决书"),row(id=2,title="窄门译后记"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(data.toResponseBody("application/json".toMediaType())).build() }.build()
        try {
            val result=WorldOnlineText(http).search("窄门")
            assertEquals(listOf("窄门译后记"),result.map{it.title});assertEquals(2,requests.size)
            assertEquals("intitle:\"窄门\"",requests.last().url.queryParameter("gsrsearch"))
        } finally { http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll() }
    }
    @Test fun explicitFullTextKeepsIncidentalHitsSeparateAndDoesNotDoExactLookup() = runBlocking {
        val requests=mutableListOf<Request>()
        val http=OkHttpClient.Builder().addInterceptor { chain -> requests+=chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(search(row(title="某某合同判决书")).toResponseBody("application/json".toMediaType())).build() }.build()
        try {
            assertEquals("某某合同判决书",WorldOnlineText(http).search("《窄门》",fullText=true).single().title)
            assertEquals(1,requests.size);assertNull(requests.single().url.queryParameter("titles"))
            assertEquals("窄门",requests.single().url.queryParameter("gsrsearch"))
        } finally { http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll() }
    }
    @Test fun failedExactTitleNeverFallsBackOrAcceptsAnUnexpectedCanonicalTitle() = runBlocking {
        for(invalid in listOf(false,true)) {
            val requests=AtomicInteger()
            val http=OkHttpClient.Builder().addInterceptor { chain -> requests.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if(invalid)200 else 503).message("test")
                    .body(search(row(title="无关判决书")).toResponseBody("application/json".toMediaType())).build() }.build()
            try {
                try { WorldOnlineText(http).search("窄门");fail("Expected source failure") }
                catch(e:WorldOnlineText.Failure){assertEquals(if(invalid)WorldOnlineText.FailureKind.FORMAT else WorldOnlineText.FailureKind.HTTP,e.kind)}
                assertEquals(1,requests.get())
            } finally { http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll() }
        }
    }
}
