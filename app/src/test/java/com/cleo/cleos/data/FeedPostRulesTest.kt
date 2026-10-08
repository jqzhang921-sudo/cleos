package com.cleo.cleos.data

import com.cleo.cleos.data.db.FeedPostEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FeedPostRulesTest {
    private val photo = MessageImage("photo.jpg", 800, 600)
    @Test fun aPostCanHaveOnlyPhotosOrOnlyWordsButCannotBeEmpty() {
        assertEquals("", FeedPostRules.content("  ", listOf(photo)))
        assertEquals("日常", FeedPostRules.content(" 日常 ", emptyList()))
        assertTrue(runCatching { FeedPostRules.content(" ", emptyList()) }.isFailure)
    }
    @Test fun albumsAndTextAreBounded() {
        assertEquals("", FeedPostRules.content("", List(9) { photo }))
        assertTrue(runCatching { FeedPostRules.content("日常", List(10) { photo }) }.isFailure)
        assertTrue(runCatching { FeedPostRules.content("字".repeat(4001), listOf(photo)) }.isFailure)
    }
    @Test fun oldTextPostsAndNewPhotoPostsRoundTripWithoutLosingMetadata() {
        val old = Json.decodeFromString<FeedPostEntity>("""{"content":"旧动态","createdAt":1}""")
        assertTrue(MessageImages.decode(old.images).isEmpty())
        val withPhotos = old.copy(images = MessageImages.encode(listOf(photo)))
        val restored = Json.decodeFromString<FeedPostEntity>(Json.encodeToString(withPhotos))
        assertEquals(listOf(photo), MessageImages.decode(restored.images))
        assertEquals(old.content, restored.content)
    }
}
