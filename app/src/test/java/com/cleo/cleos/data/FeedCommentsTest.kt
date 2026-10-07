package com.cleo.cleos.data

import com.cleo.cleos.data.db.FeedPostEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FeedCommentsTest {
    @Test fun commentsAndSourceSurviveSerialization() {
        val comments = listOf(FeedComment("one", 7, "你好 🌷", 123))
        val post = FeedPostEntity(content = "分享", createdAt = 456, liked = true,
            comments = FeedComments.encode(comments), sourceUrl = "https://example.com/news", sourceTitle = "来源")
        val restored = Json.decodeFromString<FeedPostEntity>(Json.encodeToString(post))
        assertEquals(post, restored)
        assertEquals(comments, FeedComments.decode(restored.comments))
    }
    @Test fun emptyAndInvalidCommentsAreSafe() {
        assertTrue(FeedComments.decode(null).isEmpty())
        assertTrue(FeedComments.decode("broken").isEmpty())
    }
    @Test fun textIsTrimmedAndBlankOrOversizedTextIsRejected() {
        assertEquals("你好", FeedComments.text(" 你好 \n"))
        for (text in listOf(" \n", "x".repeat(4001))) {
            assertTrue(runCatching { FeedComments.text(text) }.isFailure)
        }
    }
}
