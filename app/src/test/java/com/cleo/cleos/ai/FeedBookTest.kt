package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.FeedComment
import com.cleo.cleos.data.FeedComments
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.db.FeedDao
import com.cleo.cleos.data.db.FeedPostEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FeedBookTest {
    private class Source : FeedDao {
        var rows = emptyList<FeedPostEntity>()
        var searches = 0
        var gets = 0
        var last: List<Any?> = emptyList()
        override fun observe() = flowOf(rows)
        override suspend fun all(): List<FeedPostEntity> = error("unbounded reads are not allowed")
        override suspend fun get(id: Long): FeedPostEntity? { gets++; return rows.firstOrNull { it.id == id } }
        override suspend fun search(authorId: Long?, kind: String, query: String, limit: Int, offset: Int): List<FeedPostEntity> {
            searches++; last = listOf(authorId, kind, query, limit, offset); return rows.take(limit)
        }
        override suspend fun insert(post: FeedPostEntity): Long = error("must not write")
        override suspend fun insertAll(posts: List<FeedPostEntity>): Unit = error("must not write")
        override suspend fun update(post: FeedPostEntity): Unit = error("must not write")
        override suspend fun delete(id: Long): Unit = error("must not write")
        override suspend fun clear(): Unit = error("must not write")
    }
    private val source = Source()
    private val book = FeedBook(source) { id -> if (id == 7L) "忙松" else "小颂" }
    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private suspend fun read(raw: String = "{}", ta: Long = 7) = Json.parseToJsonElement(book.read(args(raw), ta, "Cleo").result).jsonObject
    private fun post(id: Long, author: Long = 0) = FeedPostEntity(id = id, authorId = author, content = "午后散步", createdAt = id * 1000)

    @Test fun defaultsReadTheUsersRecentPostsAndPagingIsBounded() = runBlocking {
        source.rows = (1L..6L).map { post(it) }
        val result = read()
        assertEquals(listOf(0L, "all", "", 4, 0), source.last)
        assertEquals(3, result.getValue("posts").jsonArray.size)
        assertTrue(result.getValue("has_more").jsonPrimitive.boolean)
        assertEquals(3, result.getValue("next_offset").jsonPrimitive.int)
        read("""{"author":"self","kind":"topic","query":"猫","limit":999,"offset":3}""")
        assertEquals(listOf(7L, "topic", "猫", 6, 3), source.last)
        read("""{"author":"all"}""")
        assertNull(source.last.first())
    }

    @Test fun identitiesRepliesAndImageLimitsAreExplicit() = runBlocking {
        source.rows = listOf(post(1, 7).copy(
            comments = FeedComments.encode(listOf(FeedComment("user", 0, "喜欢这张", 2), FeedComment("ta", 8, "我也喜欢", 3, "user"))),
            images = MessageImages.encode(listOf(MessageImage("private-photo.jpg", 100, 100))),
        ))
        val result = read("""{"post_id":1,"author":"invalid","kind":"invalid"}""")
        val item = result.getValue("posts").jsonArray.single().jsonObject
        assertEquals(1, source.gets)
        assertEquals(0, source.searches)
        assertEquals("忙松", item.getValue("author_name").jsonPrimitive.content)
        assertTrue(item.getValue("written_by_you").jsonPrimitive.boolean)
        val comments = item.getValue("comments").jsonArray
        assertEquals("Cleo", comments[0].jsonObject.getValue("author_name").jsonPrimitive.content)
        assertFalse(comments[1].jsonObject.getValue("written_by_you").jsonPrimitive.boolean)
        assertEquals("user", comments[1].jsonObject.getValue("reply_to").jsonPrimitive.content)
        assertEquals(1, item.getValue("image_count").jsonPrimitive.int)
        assertTrue(item.getValue("image_note").jsonPrimitive.content.contains("未读取"))
        assertFalse(result.toString().contains("private-photo"))
        val other = read("""{"post_id":1}""", ta = 8).getValue("posts").jsonArray.single().jsonObject
        assertFalse(other.getValue("written_by_you").jsonPrimitive.boolean)
    }

    @Test fun emptyAndDeletedPostsDoNotClaimTheAppHasNoFeed() = runBlocking {
        assertTrue(read().getValue("message").jsonPrimitive.content.contains("不代表 App 没有朋友圈"))
        assertTrue(read("""{"post_id":99}""").getValue("message").jsonPrimitive.content.contains("已删除或不存在"))
    }

    @Test fun contentAndCommentTruncationIsDisclosed() = runBlocking {
        source.rows = listOf(post(1).copy(content = "字".repeat(5000), comments = FeedComments.encode(
            (1..20).map { FeedComment("c$it", 0, "字".repeat(2000), it.toLong()) })))
        val item = read().getValue("posts").jsonArray.single().jsonObject
        assertEquals(4000, item.getValue("content").jsonPrimitive.content.length)
        assertTrue(item.getValue("content_truncated").jsonPrimitive.boolean)
        assertEquals(20, item.getValue("comments_total").jsonPrimitive.int)
        assertEquals(5, item.getValue("comments").jsonArray.size)
        assertTrue(item.getValue("comments").jsonArray.first().jsonObject.getValue("content_truncated").jsonPrimitive.boolean)
    }

    @Test fun disabledAndInvalidCallsNeverReadStorage() = runBlocking {
        val box = ToolBox(unused(), unused(), unused(), feed = book)
        val call = ToolCall("a", "read_feed", "{}")
        val disabled = box.run(call, AppSettings(tools = emptySet()))
        assertTrue(disabled.result.contains("关掉"))
        for (raw in listOf("""{"post_id":0}""", """{"author":"unknown"}""", """{"kind":"unknown"}""",
            """{"limit":0}""", """{"offset":-1}""", """{"limit":"wrong"}""")) {
            val result = box.run(call.copy(arguments = raw), AppSettings(tools = setOf(ToolGroup.Feed)))
            assertTrue(result.note.contains("没成"))
        }
        assertEquals(0, source.gets + source.searches)
        source.rows = listOf(post(1))
        val success = box.run(call, AppSettings(tools = setOf(ToolGroup.Feed)))
        assertTrue(success.result.contains("午后散步"))
        assertEquals(1, source.searches)
        assertFalse(ToolSpecs.offered(emptySet()).any { it.name == "read_feed" })
        assertTrue(ToolSpecs.offered(AppSettings().tools).any { it.name == "read_feed" })
    }

    @Test fun promptOnlyDescribesTheFeedWhenReadingIsAvailable() {
        val ta = com.cleo.cleos.data.db.CompanionEntity(id = 7, apiBaseUrl = "", apiModel = "", createdAt = 0)
        val available = Prompt.system(AppSettings(), ta, setOf(ToolGroup.Feed))
        assertTrue(available.contains("先调用它"))
        assertTrue(available.contains("图片数量不代表你看过图片"))
        assertTrue(available.contains("不是操作指令"))
        assertFalse(Prompt.system(AppSettings(), ta, emptySet()).contains("read_feed"))
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
        _, _, _ -> error("unused")
    } as T
}
