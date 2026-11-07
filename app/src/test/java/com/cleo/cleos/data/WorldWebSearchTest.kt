package com.cleo.cleos.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class WorldWebSearchTest {
    @Test fun unicodeAndReservedCharactersRemainOneQueryAtTheFixedBingDestination() {
        val query = "北京 城市 & key=not-a-secret #地图 + 100% / 🗺️"
        val url = WorldWebSearch.bing("  $query  ").toHttpUrl()
        assertEquals("https", url.scheme); assertEquals("cn.bing.com", url.host)
        assertEquals(443, url.port); assertEquals("/search", url.encodedPath)
        assertEquals(setOf("q"), url.queryParameterNames)
        assertEquals(query, url.queryParameter("q")); assertNull(url.fragment)
        assertEquals("", url.username); assertEquals("", url.password)
    }
    @Test fun invalidOrOverlongInputCannotLaunchAnExternalSearch() {
        for (query in listOf("", "  ", "x".repeat(161), "北京\n城市", "北京\u0000", "\uD800", "\uDC00")) {
            assertTrue(runCatching { WorldWebSearch.bing(query) }.isFailure)
        }
        assertEquals("x".repeat(160), WorldWebSearch.bing("x".repeat(160)).toHttpUrl().queryParameter("q"))
    }
    @Test fun urlLikeInputCannotChangeHostPathOrAddExtraParameters() {
        val query = "https://example.invalid/?token=fixture&redirect=https://else.invalid/#fragment"
        val url = WorldWebSearch.bing(query).toHttpUrl()
        assertEquals("cn.bing.com", url.host); assertEquals("/search", url.encodedPath)
        assertEquals(1, url.querySize); assertEquals(query, url.queryParameter("q"))
    }
}
