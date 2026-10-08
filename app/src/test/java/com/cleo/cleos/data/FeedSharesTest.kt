package com.cleo.cleos.data

import com.cleo.cleos.data.db.FeedPostEntity
import org.junit.Assert.*
import org.junit.Test

class FeedSharesTest {
    private val post = FeedPostEntity(authorId = 7, content = "自己的想法", createdAt = 1,
        sourceTitle = "来源标题", sourceUrl = "https://example.com/story")

    @Test fun ownPostIsIdentifiedAndSourceSnapshotSurvives() {
        val text = FeedShares.text(post, "小颂", 7)
        assertTrue(text.startsWith("【转发话题】")) // Older sourced posts are topics too.
        assertTrue(text.contains("作者：小颂（你自己）"))
        assertTrue(text.contains("发布时间："))
        assertTrue(text.contains("正文：\n自己的想法"))
        assertTrue(text.contains("来源：来源标题"))
        assertTrue(text.contains("来源链接：https://example.com/story"))
    }

    @Test fun matchingNamesDoNotMakeAnotherCompanionsPostYourOwn() {
        assertFalse(FeedShares.text(post, "小颂", 8).contains("（你自己）"))
        val daily = FeedShares.text(post.copy(authorId = 0, sourceTitle = null, sourceUrl = null), "我", 7)
        assertTrue(daily.startsWith("【转发朋友圈】"))
        assertTrue(daily.contains("作者：我\n"))
        assertFalse(daily.contains("来源"))
    }
}
