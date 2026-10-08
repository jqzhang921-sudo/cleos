package com.cleo.cleos.data

import com.cleo.cleos.data.db.FeedPostEntity
import org.junit.Assert.*
import org.junit.Test
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.serialization.json.Json

class FeedSharesTest {
    @Test fun photoOnlyShareKeepsPhotoMetadataAndReportsTheAlbumToTheModel() {
        val photo = MessageImage("owned-photo.jpg", 800, 600)
        val photoPost = post.copy(content = "", images = MessageImages.encode(listOf(photo)))
        val shared = FeedShares.of(photoPost, "小颂", 7)
        val restored = FeedShares.decode(FeedShares.encode(shared))!!
        assertEquals(listOf(photo), restored.images)
        assertTrue(FeedShares.text(restored).contains("图片：1 张"))
        val message = MessageEntity(conversationId = 3, role = "user", createdAt = 1,
            content = FeedShares.text(restored), images = MessageImages.encode(restored.images), feedShare = FeedShares.encode(restored))
        assertEquals(FeedShares.decode(message.feedShare)!!.images, MessageImages.decode(message.images))
    }
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

    @Test fun cardSnapshotAndCaptionSurviveMessageSerialization() {
        val shared = FeedShares.of(post, "小颂", 7).copy(caption = "想和你聊聊这个")
        val raw = FeedShares.encode(shared)
        val message = MessageEntity(conversationId = 3, role = "user", content = FeedShares.text(shared),
            createdAt = 5, feedShare = raw)
        val restored = Json.decodeFromString<MessageEntity>(Json.encodeToString(message))
        assertEquals(shared, FeedShares.decode(restored.feedShare))
        assertTrue(restored.content.startsWith("想和你聊聊这个\n\n【转发话题】"))
        assertTrue(restored.content.contains("自己的想法"))
        assertTrue(restored.content.contains("（你自己）"))
    }

    @Test fun olderMessagesAndMalformedCardsRemainReadable() {
        val old = Json.decodeFromString<MessageEntity>("""{"conversationId":3,"role":"user","content":"旧消息","createdAt":1}""")
        assertNull(old.feedShare)
        assertNull(FeedShares.decode(null))
        assertNull(FeedShares.decode("不是卡片"))
        assertEquals("旧消息", old.content)
    }

    @Test fun editingCaptionPreservesTheOriginalPostAndSource() {
        val original = FeedShares.of(post, "小颂", 7).copy(caption = "第一句话")
        val changed = original.copy(caption = "换一句话")
        assertTrue(FeedShares.text(changed).startsWith("换一句话\n\n"))
        assertFalse(FeedShares.text(changed).contains("第一句话"))
        assertEquals(original.content, changed.content)
        assertEquals(original.sourceUrl, changed.sourceUrl)
        assertEquals(original.ownPost, changed.ownPost)
    }
}
