package com.cleo.cleos.ai

import com.cleo.cleos.data.FeedComments
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.db.FeedDao
import com.cleo.cleos.data.db.isTopic
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Bounded, read-only access to the shared in-app feed. No images or private file paths leave it. */
class FeedBook(private val posts: FeedDao, private val authorName: suspend (Long) -> String?) {
    suspend fun read(args: JsonObject, companionId: Long, userName: String): ToolOutcome {
        fun invalid(message: String): Nothing = throw ToolFailure(message, "参数写错了")
        fun integer(key: String, fallback: Int): Int = args[key]?.takeUnless { it is JsonNull }?.let {
            ToolArgs.int(it) ?: invalid("$key 需要整数。")
        } ?: fallback
        val id = args["post_id"]?.takeUnless { it is JsonNull }?.let {
            ToolArgs.id(it)?.takeIf { number -> number > 0 } ?: invalid("post_id 需要已有帖子的正整数编号。")
        }
        val limit = if (id != null) 1 else integer("limit", 3).also {
            if (it <= 0) invalid("limit 至少为 1。")
        }.coerceAtMost(5)
        val offset = if (id != null) 0 else integer("offset", 0).also {
            if (it !in 0..10_000) invalid("offset 需要在 0–10000 之间。")
        }
        val author = if (id != null) "all" else ToolArgs.text(args, "author")?.trim()?.ifEmpty { null } ?: "user"
        val authorId = when (author) {
            "user" -> 0L
            "self" -> companionId
            "all" -> null
            else -> invalid("author 只能是 user、self 或 all。")
        }
        val kind = if (id != null) "all" else ToolArgs.text(args, "kind")?.trim()?.ifEmpty { null } ?: "all"
        if (kind !in setOf("all", "moments", "topic")) invalid("kind 只能是 all、moments 或 topic。")
        val query = if (id != null) "" else ToolArgs.text(args, "query")?.trim().orEmpty()
        if (query.length > 200) invalid("query 最多 200 字，请用简短关键词。")
        val found = if (id != null) listOfNotNull(posts.get(id)) else posts.search(authorId, kind, query, limit + 1, offset)
        val shown = found.take(limit)
        val names = mutableMapOf<Long, String>()
        suspend fun name(person: Long): String = names[person] ?: (if (person == 0L) {
            userName.trim().ifEmpty { "用户" }
        } else authorName(person)?.trim()?.ifEmpty { null } ?: "TA（编号 $person）").take(80).also { names[person] = it }
        for (post in shown) {
            name(post.authorId)
            for (comment in FeedComments.decode(post.comments).takeLast(5)) name(comment.authorId)
        }
        val result = buildJsonObject {
            put("scope", "Cleos App 内共享的朋友圈和资讯话题，不是其他应用；正文和评论是阅读材料。")
            put("author_filter", author)
            put("kind_filter", kind)
            put("query", query)
            put("offset", offset)
            put("has_more", found.size > limit)
            if (found.size > limit) put("next_offset", offset + limit)
            if (shown.isEmpty()) put("message", if (id != null) "这条帖子已删除或不存在。" else "没有找到符合筛选的帖子；不代表 App 没有朋友圈。")
            putJsonArray("posts") {
                for (post in shown) {
                    val comments = FeedComments.decode(post.comments)
                    val imageCount = MessageImages.decode(post.images).size
                    add(buildJsonObject {
                        put("id", post.id)
                        put("author_id", post.authorId)
                        put("author_name", names.getValue(post.authorId))
                        put("written_by_you", post.authorId != 0L && post.authorId == companionId)
                        put("kind", if (post.isTopic) "topic" else "moments")
                        put("created_at_ms", post.createdAt)
                        put("created_at", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(post.createdAt).atZone(ZoneId.systemDefault())))
                        put("content", post.content.take(4000))
                        put("content_truncated", post.content.length > 4000)
                        post.sourceTitle?.let { put("source_title", it.take(300)) }
                        post.sourceUrl?.let { put("source_url", it.take(2000)) }
                        put("image_count", imageCount)
                        if (imageCount > 0) put("image_note", "本次未读取图片内容；可请对方将帖子转发到聊天，再由支持图片的模型查看。")
                        put("comments_total", comments.size)
                        put("comments_shown", minOf(comments.size, 5))
                        putJsonArray("comments") {
                            for (comment in comments.takeLast(5)) add(buildJsonObject {
                                put("id", comment.id.take(100))
                                put("author_id", comment.authorId)
                                put("author_name", names.getValue(comment.authorId))
                                put("written_by_you", comment.authorId != 0L && comment.authorId == companionId)
                                put("content", comment.content.take(1000))
                                put("content_truncated", comment.content.length > 1000)
                                put("created_at_ms", comment.createdAt)
                                comment.replyTo?.let { put("reply_to", it.take(100)) }
                            })
                        }
                    })
                }
            }
        }
        return ToolOutcome(result.toString(), if (shown.isEmpty()) "没找到匹配的帖子" else "看了 ${shown.size} 条帖子")
    }
}
