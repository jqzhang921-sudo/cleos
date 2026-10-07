package com.cleo.cleos.ai

import com.cleo.cleos.data.db.CompanionEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FeedAiTest {
    private val now = Instant.parse("2026-10-07T00:00:00Z").toEpochMilli()
    private val source = FeedNewsItem("新闻", "https://example.com/story", "摘要", now, "来源")

    @Test fun sourceMustBeFromTheProvidedList() {
        assertEquals(source, FeedAiRules.decode("""{"content":"我的想法","sourceIndex":0}""", listOf(source)).second)
        for (index in listOf("null", "-1", "12")) {
            assertTrue(runCatching { FeedAiRules.decode("""{"content":"观点","sourceIndex":$index}""", listOf(source)) }.isFailure)
        }
    }
    @Test fun dailyThoughtsNeedNoSourceAndEmptyAnswersAreNotPosted() {
        assertEquals("灵感", FeedAiRules.decode("```json\n{\"content\":\"灵感\",\"sourceIndex\":null}\n```", emptyList()).first)
        assertNull(FeedAiRules.decode("""{"content":"灵感"}""", emptyList()).second)
        assertTrue(runCatching { FeedAiRules.decode("""{"content":""}""", emptyList()) }.isFailure)
    }
    @Test fun rssHasDateLimitsAndDeduplicatesLinks() {
        val item = "<item><title>一个发现</title><link>https://example.com/story</link><pubDate>Tue, 06 Oct 2026 12:00:00 GMT</pubDate><description><![CDATA[<p>摘要</p>]]></description></item>"
        val old = item.replace("06 Oct", "06 Sep").replace("story", "old")
        val parsed = FeedNewsParser.parse("<rss><channel>$item$item$old</channel></rss>", "NASA", now)
        assertEquals(1, parsed.size)
        assertEquals("摘要", parsed.single().summary)
        assertEquals("NASA", parsed.single().source)
    }
    @Test fun atomAndUntrustedXmlAreHandled() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>想法</title><link href="https://example.com/story"/><updated>2026-10-06T12:00:00Z</updated><summary>内容</summary></entry></feed>"""
        assertEquals(1, FeedNewsParser.parse(atom, "来源", now).size)
        assertTrue(runCatching { FeedNewsParser.parse("<!DOCTYPE x [<!ENTITY evil SYSTEM 'file:///secret'>]><rss/>", "来源", now) }.isFailure)
        assertTrue(FeedNewsParser.parse("<rss><channel><item><title>没有日期</title><link>javascript:alert(1)</link></item></channel></rss>", "来源", now).isEmpty())
    }
    @Test fun personaAndInterestHintsRemainSeparateFromChat() {
        val ta = CompanionEntity(name = "小猫", persona = "喜欢天文", apiBaseUrl = "https://example.com", apiModel = "test", createdAt = 1)
        val prompt = FeedAiRules.system(ta, "编程")
        assertTrue(prompt.contains("喜欢天文"))
        assertTrue(prompt.contains("编程"))
        assertTrue(prompt.contains("不假装已阅读全文"))
    }
}
