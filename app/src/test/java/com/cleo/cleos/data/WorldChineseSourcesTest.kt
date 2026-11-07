package com.cleo.cleos.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WorldChineseSourcesTest {
    private fun feed(url: String, text: String = "一段公开说明") = """<rss version="2.0"><channel><item><title>来源标题</title><link>$url</link><description>$text</description><pubDate>Sat, 31 Oct 2026 08:00:00 +0800</pubDate></item></channel></rss>""".toByteArray()
    private val links = mapOf("sspai" to "https://sspai.com/post/123", "gcores" to "https://www.gcores.com/radios/123")
    @Test fun chineseSummariesKeepRealScopeDatesAndBackupCompatibility() {
        links.forEach { (id, url) ->
            val source = WorldOutside.sources.single { it.id == id }
            val entry = WorldOutside.parse(feed(url), source).single()
            assertEquals(url, entry.url)
            assertTrue(entry.published.endsWith("+0800"))
            assertEquals("订阅摘要（未读取网页全文）\n\n一段公开说明", entry.summary)
            WorldReading.validateBackup(setOf(7), listOf(entry.article(7, source, 1).copy(id = 8)), emptyList())
        }
    }
    @Test fun feedsCannotAttributeAnotherChinesePublisherOrUnapprovedHost() {
        links.forEach { (id, own) ->
            val source = WorldOutside.sources.single { it.id == id }
            (links.values.filter { it != own } + listOf("https://sspai.com.evil.invalid/post/1", "http://sspai.com/post/1", "https://user:password@sspai.com/post/1", "https://127.0.0.1/a")).forEach {
                assertTrue(WorldOutside.parse(feed(it), source).isEmpty())
            }
        }
        assertTrue(WorldOutside.parse(feed(links.getValue("sspai")), WorldOutside.sources.first()).isEmpty())
    }
    @Test fun embeddedImagesAndFullContentDoNotBecomeDownloadedOrReadPages() {
        val source = WorldOutside.sources.single { it.id == "gcores" }
        val entry = WorldOutside.parse(feed(links.getValue("gcores"), "&lt;p&gt;节目简介&lt;/p&gt;&lt;img src='https://bad.invalid/pixel'&gt;"), source).single()
        assertTrue(entry.summary.endsWith("节目简介")); assertFalse(entry.summary.contains("pixel"))
    }
    @Test fun registeredSourcesKeepOldIdsAndDoNotRequireCredentialConfiguration() {
        assertEquals(listOf("un", "nasa", "sspai", "gcores"), WorldOutside.sources.map { it.id })
        assertEquals(WorldOutside.sources.size, WorldOutside.sources.map { it.url }.distinct().size)
    }
    @Test fun articleNavigationIsNotAReadableSummary() {
        val source = WorldOutside.sources.single { it.id == "sspai" }
        for (text in listOf("点击查看原文&gt;", "Read more →", "Continue reading", "阅读全文"))
            assertTrue(WorldOutside.parse(feed(links.getValue("sspai"), text), source).isEmpty())
        assertEquals("真实的简短说明。", WorldOutside.summary("<p>真实的简短说明。</p><a href='https://sspai.com'>点击查看原文&gt;</a>"))
        assertEquals("雨停了。", WorldOutside.summary("雨停了。"))
        assertFalse(WorldOutside.sources.any { it.id == "infoq" })
    }
    @Test fun backupCannotRelabelANewPublisherAsAnotherSource() {
        links.forEach { (id, url) ->
            val source = WorldOutside.sources.single { it.id == id }
            val row = WorldOutside.parse(feed(url), source).single().article(7, source, 1).copy(id = 8, source = "NASA")
            try { WorldReading.validateBackup(setOf(7), listOf(row), emptyList()); fail("Forged publisher accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun capturedPublisherResponsesParseWithProductionRules() {
        val path = System.getenv("CLEOS_FEED_QA") ?: return
        links.keys.forEach { id ->
            val source = WorldOutside.sources.single { it.id == id }
            val rows = WorldOutside.parse(File(path, "$id.xml").readBytes(), source)
            assertTrue("$id has no accepted publisher summaries", rows.isNotEmpty())
            assertTrue(rows.size <= 20)
            assertEquals(rows.size, rows.map { it.url }.distinct().size)
            // Publishers can repeat the same blurb across different links. The library deduplicates
            // by body fingerprint before insertion; validate the actual persisted shape here too.
            val stored = rows.distinctBy { WorldReading.fingerprint(it.summary) }
            WorldReading.validateBackup(setOf(7), stored.mapIndexed { i, row -> row.article(7, source, 1).copy(id = i + 1L) }, emptyList())
        }
    }
}
