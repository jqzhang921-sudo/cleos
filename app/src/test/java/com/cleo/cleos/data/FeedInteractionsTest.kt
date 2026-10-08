package com.cleo.cleos.data

import com.cleo.cleos.data.db.FeedPostEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FeedInteractionsTest {
    private val friend = FeedComment("friend", 0, "我也喜欢", 2)
    private val own = FeedComment("own", 7, "自己的评论", 3)
    private val post = FeedPostEntity(authorId = 7, content = "自己的动态", createdAt = 1,
        comments = FeedComments.encode(listOf(friend, own)))

    @Test fun authorNeedsAnotherPersonsCommentAndCannotAnswerThemself() {
        assertFalse(FeedInteractions.canInvite(post, 7, null))
        assertFalse(FeedInteractions.canInvite(post, 7, own.id))
        assertFalse(FeedInteractions.canInvite(post, 7, "missing"))
        assertTrue(FeedInteractions.canInvite(post, 7, friend.id))
        assertTrue(FeedInteractions.canInvite(post, 8, null))
    }
    @Test fun evenASilentReadPreventsRepeatingTheSameInvitationButNewRepliesAreEligible() {
        val read = post.copy(interactions = FeedInteractions.record(post, 7, friend.id, false))
        assertFalse(FeedInteractions.canInvite(read, 7, friend.id))
        assertFalse(FeedInteractions.decode(read.interactions).single().liked)
        val newer = FeedComment("new", 8, "新的评论", 4)
        assertTrue(FeedInteractions.canInvite(read.copy(comments = FeedComments.encode(listOf(friend, own, newer))), 7, newer.id))
    }
    @Test fun likesAreSeparatePerTaAndAuthorsDoNotLikeTheirOwnPost() {
        val authorRead = post.copy(interactions = FeedInteractions.record(post, 7, friend.id, true))
        assertFalse(FeedInteractions.decode(authorRead.interactions).single().liked)
        val otherRead = authorRead.copy(interactions = FeedInteractions.record(authorRead, 8, null, true))
        assertTrue(FeedInteractions.decode(otherRead.interactions).first { it.taId == 8L }.liked)
        assertFalse(otherRead.liked)
        assertEquals(2, FeedInteractions.decode(otherRead.interactions).size)
    }
    @Test fun repeatedWordsAreNotPostedAgainButOtherSpeakersCanSayThem() {
        assertTrue(FeedInteractions.duplicate(post, 7, " 自己的评论！ "))
        assertFalse(FeedInteractions.duplicate(post, 8, "自己的评论"))
        assertFalse(FeedInteractions.duplicate(post, 7, "别的想法"))
    }
    @Test fun oldCommentsAndBackupsKeepTheirIdentityAndReplyLinks() {
        val old = FeedComments.decode("""[{"id":"old","authorId":7,"content":"旧回复","createdAt":1}]""").single()
        assertNull(old.replyTo)
        val new = friend.copy(replyTo = own.id)
        val updated = post.copy(comments = FeedComments.encode(listOf(own, new)),
            interactions = FeedInteractions.record(post, 8, null, true))
        val restored = Json.decodeFromString<FeedPostEntity>(Json.encodeToString(updated))
        assertEquals(own.id, FeedComments.decode(restored.comments).last().replyTo)
        assertTrue(FeedInteractions.reviewed(restored, 8, null))
        assertEquals(updated, restored)
    }
}
