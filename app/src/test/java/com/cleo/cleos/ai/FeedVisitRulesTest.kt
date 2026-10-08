package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.FeedPostEntity
import com.cleo.cleos.data.db.WakeActivityEntity as W
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class FeedVisitRulesTest {
    private val now = ZonedDateTime.parse("2026-10-08T12:00:00+08:00[Asia/Shanghai]")
    private val ta = CompanionEntity(id = 7, apiBaseUrl = "https://example.com", apiModel = "test", createdAt = 1, feedVisitEnabled = true)
    private val post = FeedPostEntity(id = 1, authorId = 0, content = "午后的阳光", createdAt = now.toInstant().toEpochMilli())
    private val targets = listOf(FeedVisitTarget(post, null))
    private val sources = listOf(FeedNewsItem("新发现", "https://example.com/news", "来源摘要", 1, "示例来源"))
    private fun rejects(json: String, allowed: Boolean = true) {
        assertTrue(runCatching { FeedVisitRules.decode(json, targets, sources, allowed) }.isFailure)
    }

    @Test fun oldBackupsDoNotOptIntoAutomaticVisitsOrPosting() {
        val old = Json.decodeFromString<CompanionEntity>("""{"apiBaseUrl":"a","apiModel":"b","createdAt":1}""")
        assertFalse(old.feedVisitEnabled)
        assertFalse(old.feedVisitPosts)
        assertFalse(old.feedVisitNews)
        assertTrue(old.feedVisitQuietOn)
        assertEquals(1380, old.feedVisitQuietStart)
        assertEquals(480, old.feedVisitQuietEnd)
        val enabled = ta.copy(feedVisitPosts = true, feedVisitLevel = 2, feedVisitQuietEnd = 540)
        assertEquals(enabled, Json.decodeFromString<CompanionEntity>(Json.encodeToString(enabled)))
    }
    @Test fun eachLevelWaitsWithinItsRangeAndNeverImmediatelyWakes() {
        FeedVisitRules.LEVELS.forEach { level ->
            val selected = ta.copy(feedVisitLevel = level.id, feedVisitQuietOn = false)
            val start = now.toInstant().toEpochMilli()
            assertEquals(level.minMinutes * 60_000L, FeedVisitRules.next(selected, now, -1.0) - start)
            assertEquals(level.maxMinutes * 60_000L, FeedVisitRules.next(selected, now, 2.0) - start)
        }
        assertEquals(0, FeedVisitRules.level(99).id)
    }
    @Test fun nightVisitsWaitUntilQuietEndsAndDayQuotaWaitsUntilTomorrow() {
        val late = now.withHour(22)
        assertEquals(late.plusDays(1).withHour(8).toInstant().toEpochMilli(), FeedVisitRules.next(ta, late, 0.0))
        assertEquals(now.plusDays(1).withHour(8).toInstant().toEpochMilli(), FeedVisitRules.next(ta, now, 0.0, exhausted = true))
        assertTrue(FeedVisitRules.quiet(ta, now.withHour(23)))
        assertTrue(FeedVisitRules.quiet(ta, now.withHour(7)))
        assertFalse(FeedVisitRules.quiet(ta, now.withHour(8)))
    }
    @Test fun equalQuietTimesMeanAllDayAndInvalidMinutesUseDefaults() {
        val fullDay = ta.copy(feedVisitQuietStart = 480, feedVisitQuietEnd = 480)
        assertTrue(FeedVisitRules.quiet(fullDay, now))
        val at = FeedVisitRules.next(fullDay, now, 0.0)
        assertTrue(at > now.toInstant().toEpochMilli())
        assertTrue(FeedVisitRules.quiet(ta.copy(feedVisitQuietStart = -1, feedVisitQuietEnd = 9000), now.withHour(2)))
    }
    @Test fun disabledBusyMissingKeyAndDailyLimitAreApplicationSkips() {
        assertNotNull(FeedVisitRules.held(ta.copy(feedVisitEnabled = false), now, false, 0, true))
        assertNotNull(FeedVisitRules.held(ta, now, true, 0, true))
        assertNotNull(FeedVisitRules.held(ta, now, false, 0, false))
        assertNotNull(FeedVisitRules.held(ta, now, false, 2, true))
        assertNull(FeedVisitRules.held(ta, now, false, 1, true))
    }
    @Test fun ownPostOnlyOffersAnotherPersonsNewComment() {
        val own = post.copy(authorId = 7)
        val self = FeedComment("self", 7, "自己说的话", 2)
        val friend = FeedComment("friend", 0, "想听你说说", 3)
        assertTrue(FeedVisitRules.targets(listOf(own.copy(comments = FeedComments.encode(listOf(self)))), 7, post.createdAt).isEmpty())
        val withFriend = own.copy(comments = FeedComments.encode(listOf(friend, self)))
        assertEquals("friend", FeedVisitRules.targets(listOf(withFriend), 7, post.createdAt).single().replyTo)
        val read = withFriend.copy(interactions = FeedInteractions.record(withFriend, 7, "friend", false))
        assertTrue(FeedVisitRules.targets(listOf(read), 7, post.createdAt).isEmpty())
    }
    @Test fun newCommentIsReadableEvenAfterTheBodyWasRead() {
        val bodyRead = post.copy(interactions = FeedInteractions.record(post, 7, null, false))
        assertTrue(FeedVisitRules.targets(listOf(bodyRead), 7, post.createdAt).isEmpty())
        val new = bodyRead.copy(comments = FeedComments.encode(listOf(FeedComment("new", 8, "再聊聊", 3))))
        assertEquals("new", FeedVisitRules.targets(listOf(new), 7, post.createdAt).single().replyTo)
    }
    @Test fun contextIsBoundedAndExcludesFutureAndOldPosts() {
        val candidates = (1L..10L).map { post.copy(id = it) }
        assertEquals(4, FeedVisitRules.targets(candidates, 7, post.createdAt).size)
        assertTrue(FeedVisitRules.targets(listOf(post.copy(createdAt = post.createdAt + 1), post.copy(createdAt = post.createdAt - 8 * 86400_000L)), 7, post.createdAt).isEmpty())
    }
    @Test fun silenceAndLikeOnlyAreValidButSilenceCannotSmuggleAnAction() {
        assertEquals("skip", FeedVisitRules.decode("""{"action":"skip"}""", targets, sources, false).action)
        assertTrue(FeedVisitRules.decode("""{"action":"reply","postId":1,"like":true}""", targets, sources, false).like)
        rejects("""{"action":"skip","like":true}""")
        rejects("""{"action":"skip","content":"偷偷发一句"}""")
        rejects("""{"action":"reply","postId":999,"content":"你好"}""")
        rejects("""{"action":"reply","postId":1,"replyTo":"invented"}""")
    }
    @Test fun explicitCommentTargetMustMatchPresentedObjectExactly() {
        val commentTargets = listOf(FeedVisitTarget(post, "friend"))
        assertTrue(runCatching { FeedVisitRules.decode("""{"action":"reply","postId":1}""", commentTargets, sources, false) }.isFailure)
        assertEquals("friend", FeedVisitRules.decode("""{"action":"reply","postId":1,"replyTo":"friend","content":"收到"}""", commentTargets, sources, false).replyTo)
    }
    @Test fun postingRequiresOptInAndARealSourceAndLengthLimits() {
        rejects("""{"action":"post","content":"今天很好"}""", allowed = false)
        rejects("""{"action":"post","content":"资讯","sourceIndex":99}""")
        rejects("""{"action":"post","content":"${"好".repeat(81)}"}""")
        rejects("""{"action":"post","content":"${"好".repeat(601)}","sourceIndex":0}""")
        rejects("""{"action":"reply","postId":1,"content":"${"好".repeat(201)}"}""")
        rejects("""{"action":"reply","postId":1,"sourceIndex":0}""")
        assertEquals(0, FeedVisitRules.decode("""{"action":"post","content":"${"好".repeat(200)}","sourceIndex":0}""", targets, sources, true).sourceIndex)
        assertEquals("今天很好", FeedVisitRules.decode("""{"action":"post","content":" 今天很好 "}""", targets, sources, true).content)
    }
    @Test fun feedActivityNeverClaimsAChatMessageWasSent() {
        val record = W(companionId = 7, source = W.FEED, startedAt = 1, status = W.ACTED)
        assertEquals("主动逛朋友圈", WakeActivityText.source(record.source))
        assertEquals("已更新朋友圈", WakeActivityText.status(record))
        assertFalse(WakeActivityText.status(record).contains("消息"))
        assertEquals("未请求模型", WakeActivityText.requests(record))
    }
}
