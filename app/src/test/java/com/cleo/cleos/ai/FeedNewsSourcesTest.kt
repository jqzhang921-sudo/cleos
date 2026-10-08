package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FeedNewsSourcesTest {
    private fun item(id: String) = FeedNewsItem(id, "https://example.com/$id", "摘要", 1, "来源")
    private fun group(id: String, vararg urls: String) = FeedNewsGroup(id, id, "", urls.map { FeedNewsSource(it, it) })

    @Test fun missingPreferencePreservesOldNasaOrCustomChoice() {
        assertEquals(setOf("space"), FeedNewsSources.selected("", ""))
        assertEquals(setOf("custom"), FeedNewsSources.selected("", "https://example.com/rss"))
        assertEquals(setOf("cats", "food"), FeedNewsSources.selected("cats,food", "https://example.com/rss"))
        assertTrue(FeedNewsSources.selected("unknown", "").isEmpty())
    }

    @Test fun savedSelectionIsCanonicalAndOnlyContainsChosenGroups() {
        assertEquals("cats,food,custom", FeedNewsSources.encode(setOf("custom", "food", "cats")))
        assertEquals(listOf("travel", "tech"), FeedNewsSources.groups("tech,travel", "https://example.com/rss").map { it.id })
        assertEquals("https://example.com/rss", FeedNewsSources.groups("custom", " https://example.com/rss ").single().feeds.single().url)
    }

    @Test fun invalidCustomUrlsAndEmptySelectionsAreRejected() {
        for (url in listOf("", "http://example.com/rss", "https://user:pass@example.com/rss", "not a url")) {
            assertTrue(runCatching { FeedNewsSources.validate(setOf("custom"), url) }.isFailure)
        }
        assertTrue(runCatching { FeedNewsSources.validate(emptySet(), "") }.isFailure)
        assertTrue(runCatching { FeedNewsSources.validate(setOf("invented"), "") }.isFailure)
        FeedNewsSources.validate(setOf("cats"), "invalid unused custom url")
    }

    @Test fun categoriesRemainBalancedEvenWhenOneHasManyMoreArticles() {
        val result = FeedNewsSources.balanced(listOf((1..12).map { item("tech$it") }, listOf(item("cat")), listOf(item("food"))), maximum = 4)
        assertEquals(listOf("tech1", "cat", "food", "tech2"), result.map { it.title })
        assertEquals(12, FeedNewsSources.balanced(listOf((1..30).map { item("n$it") }), maximum = 100).size)
    }

    @Test fun previousSharesAreExcludedBeforeLimitingAndDuplicatesAreGlobal() {
        val result = FeedNewsSources.balanced(listOf(listOf(item("old"), item("new")), listOf(item("new"), item("other"))),
            excluded = setOf(item("old").url), maximum = 2)
        assertEquals(listOf("new", "other"), result.map { it.title })
    }

    @Test fun failedCategoryDoesNotCancelOthersAndFallbackGetsItsCategory() = runBlocking {
        val requested = mutableListOf<String>()
        val result = FeedNewsSources.collect(listOf(group("cats", "bad"), group("food", "primary", "fallback"))) { source ->
            requested += source.url
            if (source.url != "fallback") error("暂时不可用")
            listOf(item("recipe"))
        }
        assertEquals(listOf(FeedNewsStatus("cats", 0), FeedNewsStatus("food", 1)), result.statuses)
        assertEquals("food", result.items.single().category)
        assertTrue("fallback" in requested)
    }

    @Test fun anEmptyPrimaryAlsoTriesFallbackAndTotalFailureIsReported() = runBlocking {
        val result = FeedNewsSources.collect(listOf(group("food", "empty", "fallback"), group("space", "empty"))) { source ->
            if (source.url == "fallback") listOf(item("recipe")) else emptyList()
        }
        assertEquals(listOf(1, 0), result.statuses.map { it.count })
    }

    @Test fun cancellationDoesNotBecomeAQuietNetworkFailure() {
        assertTrue(runCatching {
            runBlocking { FeedNewsSources.collect(listOf(group("cats", "cancel"))) { throw CancellationException("cancelled") } }
        }.exceptionOrNull() is CancellationException)
    }

    @Test fun modelsCanStayQuietButCannotAttachAnArticleToAnEmptyPost() {
        assertNull(FeedAiRules.sharing("""{"content":"","sourceIndex":null}""", listOf(item("cat"))))
        assertTrue(runCatching { FeedAiRules.sharing("""{"content":"","sourceIndex":0}""", listOf(item("cat"))) }.isFailure)
    }

    @Test fun chosenArticleMustBeRealAndDailyAndNewsLengthsDiffer() {
        val articles = listOf(item("cat"), item("food"))
        assertEquals(articles[1], FeedAiRules.sharing("""{"content":"想试试这个食谱","sourceIndex":1}""", articles)?.second)
        assertTrue(runCatching { FeedAiRules.sharing("""{"content":"观点","sourceIndex":2}""", articles) }.isFailure)
        assertTrue(runCatching { FeedAiRules.sharing("""{"content":"${"字".repeat(81)}","sourceIndex":null}""", emptyList()) }.isFailure)
        assertNotNull(FeedAiRules.sharing("""{"content":"${"字".repeat(600)}","sourceIndex":0}""", articles))
        assertTrue(runCatching { FeedAiRules.sharing("""{"content":"${"字".repeat(601)}","sourceIndex":0}""", articles) }.isFailure)
    }

    @Test fun changingReadingPreferencesInvalidatesAnInFlightResult() {
        val settings = AppSettings(feedNewsSources = "cats,food", feedInterests = "猫")
        assertTrue(FeedAiRules.sameNewsSettings(settings, settings.copy()))
        assertFalse(FeedAiRules.sameNewsSettings(settings, settings.copy(feedNewsSources = "tech")))
        assertFalse(FeedAiRules.sameNewsSettings(settings, settings.copy(feedInterests = "厨房")))
        assertFalse(FeedAiRules.sameNewsSettings(settings, settings.copy(feedRssUrl = "https://example.com/rss")))
    }

    @Test fun actualFeedDateFormatsWithTimezonesAreAccepted() {
        val now = java.time.Instant.parse("2026-10-08T16:00:00Z").toEpochMilli()
        val rss = "<rss><channel><item><title>食谱</title><link>https://example.com/food</link><pubDate>Thu, 08 Oct 2026 10:00:00 -0400</pubDate><description><![CDATA[<p>家常菜</p>]]></description></item></channel></rss>"
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>科技</title><link href="https://example.com/tech"/><updated>2026-10-08T10:16:37-04:00</updated><summary>内容</summary></entry></feed>"""
        assertEquals("家常菜", FeedNewsParser.parse(rss, "Kitchn", now).single().summary)
        assertEquals("科技", FeedNewsParser.parse(atom, "Verge", now).single().title)
    }
}
