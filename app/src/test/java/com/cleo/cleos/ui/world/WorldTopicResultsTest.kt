package com.cleo.cleos.ui.world

import com.cleo.cleos.data.db.WorldArticleEntity
import org.junit.Assert.*
import org.junit.Test

class WorldTopicResultsTest {
    private fun article(id: Long, term: String = "潮汐", space: String = "outside") =
        WorldArticleEntity(id, 10, "见闻 $id", "维基百科", "摘要", "fixture", 1, space = space, searchQuery = term)
    @Test fun feedResultsDoNotLeakIntoAnEmptyTopicPage() {
        val news = (1L..20L).map { article(it, "") }
        assertTrue(topicResults(news, "", "", news.map { it.id }).isEmpty())
        assertTrue(topicResults(news, "   ", "", news.map { it.id }).isEmpty())
        assertTrue(topicResults(news, "潮汐", "", news.map { it.id }).isEmpty())
    }
    @Test fun reopenedSameQueryNeverPassesMoreThanThreeCacheIdsToReader() {
        val many = (1L..12L).map { article(it) } + article(20, space = "study")
        assertEquals(listOf(1L, 2L, 3L), topicResults(many, " 潮汐 ", null, null).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), topicResults(many, "潮汐", "other query", listOf(10L)).map { it.id })
    }
    @Test fun freshQueryUsesActualReturnedIdsIncludingReusedSourcesButNotOtherEntries() {
        val all = listOf(article(1, "earlier query"), article(2), article(3), article(4, space = "study"))
        assertEquals(listOf(1L, 3L), topicResults(all, "潮汐", "潮汐", listOf(1, 3, 4)).map { it.id })
        assertTrue(topicResults(all, "潮汐", "潮汐", emptyList()).isEmpty())
    }
}
