package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.FeedComment
import com.cleo.cleos.data.FeedComments
import com.cleo.cleos.data.FeedInteraction
import com.cleo.cleos.data.FeedInteractions
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.FeedDao
import com.cleo.cleos.data.db.FeedPostEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FeedActionsTest {
    private class Source : FeedDao {
        val rows = linkedMapOf<Long, FeedPostEntity>()
        val mutex = Mutex()
        var writes = 0
        var transactions = 0
        var failWrites = false
        override fun observe() = flowOf(rows.values.toList())
        override suspend fun all(): List<FeedPostEntity> = error("unbounded reads")
        override suspend fun get(id: Long) = rows[id]
        override suspend fun search(authorId: Long?, kind: String, query: String, limit: Int, offset: Int) =
            rows.values.filter { it.authorId == authorId && it.kind == kind }.sortedByDescending { it.createdAt }.drop(offset).take(limit)
        override suspend fun insert(post: FeedPostEntity): Long {
            check(!failWrites) { "storage failed" }; writes++
            val id = (rows.keys.maxOrNull() ?: 0) + 1; rows[id] = post.copy(id = id); return id
        }
        override suspend fun update(post: FeedPostEntity) { check(!failWrites); writes++; rows[post.id] = post }
        override suspend fun insertAll(posts: List<FeedPostEntity>): Unit = error("unused")
        override suspend fun delete(id: Long): Unit = error("must not delete")
        override suspend fun clear(): Unit = error("must not clear")
        suspend fun transaction(block: suspend () -> Unit) = mutex.withLock {
            transactions++
            val before = rows.toMap()
            try { block() } catch (e: Exception) { rows.clear(); rows.putAll(before); throw e }
        }
    }
    private val source = Source()
    private var taExists = true
    private val actions = FeedActions(source, { taExists }, { source.transaction(it) }, { 1234 })
    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private suspend fun run(name: String, raw: String, ta: Long = 7) = actions.run(name, args(raw), ta)
    private fun stored(id: Long = 1, author: Long = 0, comments: List<FeedComment> = emptyList()): FeedPostEntity {
        val row = FeedPostEntity(id = id, authorId = author, content = "散步", createdAt = 1, liked = true,
            comments = FeedComments.encode(comments), images = "original-images", sourceTitle = "original-source",
            interactions = FeedInteractions.encode(listOf(FeedInteraction(8, true, listOf("post")))))
        source.rows[id] = row; return row
    }
    private suspend fun failure(name: String, raw: String, contains: String, ta: Long = 7) {
        try { run(name, raw, ta); fail("must fail") } catch (e: ToolFailure) { assertTrue(e.result, e.result.contains(contains)) }
    }

    @Test fun publishPersistsAsCallingTaAndRepeatedRequestsDoNotDuplicate() = runBlocking {
        val first = run("publish_feed", """{"content":" 午后想晒晒太阳 ","author_id":0}""")
        assertEquals(7L, source.rows.getValue(1).authorId)
        assertEquals("午后想晒晒太阳", source.rows.getValue(1).content)
        assertEquals(1234L, source.rows.getValue(1).createdAt)
        assertNull(source.rows.getValue(1).images)
        assertEquals("moments", source.rows.getValue(1).kind)
        assertTrue(Json.parseToJsonElement(first.result).jsonObject.getValue("applied").jsonPrimitive.boolean)
        val repeated = run("publish_feed", """{"content":"午后想晒晒太阳"}""")
        assertFalse(Json.parseToJsonElement(repeated.result).jsonObject.getValue("applied").jsonPrimitive.boolean)
        assertEquals(1, source.writes)
        run("publish_feed", """{"content":"午后想晒晒太阳"}""", 8)
        assertEquals(8L, source.rows.getValue(2).authorId)
    }

    @Test fun likesAreExplicitAndPreserveUserOtherTasCommentsAndReviewedTargets() = runBlocking {
        val before = stored().copy(interactions = FeedInteractions.encode(listOf(
            FeedInteraction(8, true, listOf("post")), FeedInteraction(7, false, listOf("comment:c")))))
        source.rows[1] = before
        run("like_feed", """{"post_id":1}""")
        run("like_feed", """{"post_id":1}""")
        assertEquals(1, source.writes)
        assertTrue(FeedInteractions.decode(source.rows.getValue(1).interactions).first { it.taId == 7L }.liked)
        run("like_feed", """{"post_id":1,"liked":false}""")
        val after = source.rows.getValue(1)
        assertEquals(before.copy(interactions = after.interactions), after)
        val states = FeedInteractions.decode(after.interactions)
        assertEquals(FeedInteraction(8, true, listOf("post")), states.first { it.taId == 8L })
        assertEquals(FeedInteraction(7, false, listOf("comment:c")), states.first { it.taId == 7L })
        assertEquals(2, source.writes)
    }

    @Test fun commentsUseCallingIdentityReplyTargetAndPreventRepeatedContent() = runBlocking {
        val before = stored(comments = listOf(FeedComment("friend", 8, "也想散步", 1)))
        val outcome = run("comment_feed", """{"post_id":1,"content":"一起呀","reply_to":"friend","author_id":0}""")
        val after = source.rows.getValue(1)
        val comment = FeedComments.decode(after.comments).last()
        assertEquals(7L, comment.authorId)
        assertEquals("friend", comment.replyTo)
        assertEquals(1234L, comment.createdAt)
        assertTrue(outcome.result.contains(comment.id))
        assertTrue(FeedInteractions.reviewed(after, 7, "friend"))
        assertEquals(before.copy(comments = after.comments, interactions = after.interactions), after)
        run("comment_feed", """{"post_id":1,"content":"一起呀！","reply_to":"friend"}""")
        assertEquals(1, source.writes)
        assertEquals(2, FeedComments.decode(source.rows.getValue(1).comments).size)
    }

    @Test fun ownPostOnlyAcceptsRepliesToFriendsAndOwnCommentsCannotBeRepliedTo() = runBlocking {
        stored(author = 7, comments = listOf(FeedComment("own", 7, "我自己", 1), FeedComment("friend", 0, "我也去", 2)))
        failure("like_feed", """{"post_id":1}""", "不能赞自己")
        failure("comment_feed", """{"post_id":1,"content":"自己反驳自己"}""", "自己的帖子")
        failure("comment_feed", """{"post_id":1,"content":"继续反驳","reply_to":"own"}""", "自己的评论")
        assertEquals(0, source.writes)
        run("comment_feed", """{"post_id":1,"content":"好呀","reply_to":"friend"}""")
        assertEquals(1, source.writes)
    }

    @Test fun missingTargetInvalidInputsAndDeletedTaNeverWrite() = runBlocking {
        stored()
        failure("comment_feed", """{"post_id":1,"content":"你好","reply_to":"gone"}""", "已不存在")
        failure("like_feed", """{"post_id":99}""", "已删除")
        failure("like_feed", """{"post_id":0}""", "post_id")
        failure("like_feed", """{"post_id":1,"liked":"maybe"}""", "true 或 false")
        failure("publish_feed", """{"content":" "}""", "content")
        failure("publish_feed", buildJsonObject { put("content", "字".repeat(1201)) }.toString(), "1200")
        failure("comment_feed", buildJsonObject { put("post_id", 1); put("content", "字".repeat(201)) }.toString(), "200")
        taExists = false
        failure("publish_feed", """{"content":"你好"}""", "TA 已不存在")
        failure("publish_feed", """{"content":"你好"}""", "TA 已不存在", ta = 0)
        assertEquals(0, source.writes)
    }

    @Test fun disabledToolDoesNotReachStorageAndReadOnlyModeOffersNoWrites() = runBlocking {
        val box = ToolBox(unused(), unused(), unused(), feedActions = actions)
        val outcome = box.run(ToolCall("a", "publish_feed", """{"content":"你好"}"""), AppSettings(tools = setOf(ToolGroup.Feed)))
        assertTrue(outcome.result.contains("关掉"))
        assertEquals(0, source.transactions)
        assertEquals(listOf("read_feed"), box.specs(setOf(ToolGroup.Feed)).map { it.name })
        assertEquals(setOf("publish_feed", "like_feed", "comment_feed"), box.specs(setOf(ToolGroup.FeedActions)).map { it.name }.toSet())
        val success = box.run(ToolCall("b", "publish_feed", """{"content":"你好"}"""), AppSettings(tools = setOf(ToolGroup.FeedActions)), companionId = 8)
        assertTrue(success.result.contains("\"ok\":true"))
        assertEquals(8L, source.rows.getValue(1).authorId)
    }

    @Test fun simultaneousLikesAndCommentsKeepBothUpdates() = runBlocking {
        stored()
        listOf(async { run("like_feed", """{"post_id":1}""") },
            async { run("comment_feed", """{"post_id":1,"content":"真好"}""") }).awaitAll()
        val post = source.rows.getValue(1)
        assertEquals(1, FeedComments.decode(post.comments).size)
        assertTrue(FeedInteractions.decode(post.interactions).first { it.taId == 7L }.liked)
        assertTrue(post.liked)
        assertTrue(FeedInteractions.decode(post.interactions).first { it.taId == 8L }.liked)
    }

    @Test fun storageFailureDoesNotReportSuccess() = runBlocking {
        source.failWrites = true
        try { run("publish_feed", """{"content":"你好"}"""); fail("must fail") }
        catch (e: IllegalStateException) { assertEquals("storage failed", e.message) }
        assertTrue(source.rows.isEmpty())
    }

    @Test fun promptSeparatesReadingFromActualPublishingAndDoesNotDescribeDisabledActions() {
        val ta = CompanionEntity(id = 7, apiBaseUrl = "", apiModel = "", createdAt = 0)
        val on = Prompt.system(AppSettings(), ta, setOf(ToolGroup.Feed, ToolGroup.FeedActions))
        assertTrue(on.contains("publish_feed")); assertTrue(on.contains("comment_feed")); assertTrue(on.contains("like_feed"))
        assertTrue(on.contains("只有工具成功返回才说完成"))
        assertFalse(Prompt.system(AppSettings(), ta, setOf(ToolGroup.Feed)).contains("publish_feed"))
    }

    @Test fun defaultChatRequestActuallyIncludesAllFeedSchemasAndAcceptsTheirResults() {
        val specs = ToolSpecs.offered(AppSettings().tools)
        val call = ToolCall("post-call", "publish_feed", """{"content":"午后晒太阳"}""")
        val body = requestBody("deepseek-flash", listOf(ApiMessage("user", "你去发个朋友圈"),
            ApiMessage("assistant", "", toolCalls = listOf(call)),
            ApiMessage("tool", """{"ok":true,"post_id":12}""", toolCallId = call.id)), specs, thinking = false)
        val functions = body.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
        val names = functions.map { it.getValue("name").jsonPrimitive.content }.toSet()
        assertTrue(names.containsAll(listOf("read_feed", "publish_feed", "like_feed", "comment_feed")))
        val publish = functions.first { it.getValue("name").jsonPrimitive.content == "publish_feed" }
        assertEquals(listOf("content"), publish.getValue("parameters").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val history = body.getValue("messages").jsonArray
        assertEquals("publish_feed", history[1].jsonObject.getValue("tool_calls").jsonArray.single().jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("post-call", history[2].jsonObject.getValue("tool_call_id").jsonPrimitive.content)
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
        _, _, _ -> error("unused")
    } as T
}
