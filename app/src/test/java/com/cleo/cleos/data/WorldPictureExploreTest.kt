package com.cleo.cleos.data

import com.cleo.cleos.ai.WorldReader
import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test

class WorldPictureExploreTest {
    private fun article(id: Long) = WorldArticleEntity(id, 1, "Place $id", "Wikipedia", "Summary", "hash-$id", id, space = "outside", originUrl = "https://zh.wikipedia.org/wiki/Place_$id")
    @Test fun candidatesExcludeOtherOwnersPrivateTextFeedsAndInvalidOrigins() {
        val good = article(1)
        val rows = listOf(good, article(2).copy(companionId = 2), article(3).copy(space = "study"), article(4).copy(originUrl = "https://example.invalid/wiki/a"), article(5).copy(originUrl = "http://zh.wikipedia.org/wiki/a"), article(6).copy(originUrl = "https://zh.wikipedia.org/wiki/a|b"))
        assertEquals(listOf(good), WorldPictureExplore.candidates(1, rows, emptyList()))
    }
    @Test fun candidatesPreferUnseenThenLeastRecentlySeenAndBoundNetworkChoice() {
        val rows = (1L..15L).map(::article)
        val seen = (11L..15L).map { WorldPictureLookEntity(it, 1, it, "0".repeat(64), "run-$it", "", "", 100-it) }
        assertEquals((10L downTo 1L).toList(), WorldPictureExplore.candidates(1, rows, seen).map { it.id })
        assertEquals((15L downTo 11L).toList(), WorldPictureExplore.candidates(1, rows.takeLast(5), seen).map { it.id })
    }
    @Test fun choiceOnlyAcceptsOneStrictPublicCandidateIdOrPause() {
        assertEquals(2L, WorldReader.pictureChoice("""{"article_id":2}""", setOf(1,2)))
        assertNull(WorldReader.pictureChoice("""{"article_id":null}""", setOf(1,2)))
        for (bad in listOf("SKIP", "{\"article_id\":", """{"photo_id":1}""", """{"id":1}""", """{"article_id":"1"}""", """{"article_id":1e0}""", """{"article_id":9}""", """{"article_id":1,"note":"reasoning"}""")) {
            try { WorldReader.pictureChoice(bad, setOf(1,2)); fail(bad) } catch (_: IllegalArgumentException) { }
        }
    }
}
