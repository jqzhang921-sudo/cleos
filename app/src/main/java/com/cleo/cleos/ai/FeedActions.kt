package com.cleo.cleos.ai

import com.cleo.cleos.data.FeedComment
import com.cleo.cleos.data.FeedComments
import com.cleo.cleos.data.FeedInteraction
import com.cleo.cleos.data.FeedInteractions
import com.cleo.cleos.data.db.FeedDao
import com.cleo.cleos.data.db.FeedPostEntity
import java.util.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Explicit chat tools write as the calling TA, inside the same Room transaction as their checks. */
class FeedActions(
    private val posts: FeedDao,
    private val exists: suspend (Long) -> Boolean,
    private val transaction: suspend (suspend () -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(action: String, args: JsonObject, taId: Long): ToolOutcome {
        fun fail(message: String): Nothing = throw ToolFailure(message, message)
        fun text(key: String, maximum: Int) = ToolArgs.text(args, key)?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= maximum } ?: fail("$key 需要 1–$maximum 字。")
        val content = when (action) {
            "publish_feed" -> text("content", 1200)
            "comment_feed" -> text("content", 200)
            "like_feed" -> null
            else -> fail("没有这个朋友圈操作。")
        }
        val postId = if (action == "publish_feed") null else ToolArgs.id(args["post_id"])
            ?.takeIf { it > 0 } ?: fail("需要已有帖子的正整数 post_id，请先看朋友圈确认。")
        val replyTo = if (action == "comment_feed") {
            if (args["reply_to"] == null || args["reply_to"] is JsonNull) null
            else ToolArgs.text(args, "reply_to")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 }
                ?: fail("reply_to 需要已有评论的编号。")
        } else null
        val liked = if (action == "like_feed") {
            if (args["liked"] == null || args["liked"] is JsonNull) true
            else ToolArgs.bool(args, "liked") ?: fail("liked 需要 true 或 false。")
        } else false
        var outcome: ToolOutcome? = null
        transaction {
            if (taId <= 0 || !exists(taId)) fail("当前 TA 已不存在，没有修改朋友圈。")
            if (action == "publish_feed") {
                val previous = posts.search(taId, "moments", "", 10, 0).firstOrNull { it.content.trim() == content }
                if (previous != null) {
                    outcome = result(previous.id, "这条动态已经发表过，没有重复发布。", "已有这条动态", applied = false)
                } else {
                    val id = posts.insert(FeedPostEntity(authorId = taId, content = content!!, createdAt = clock()))
                    outcome = result(id, "已以你的 TA 身份发表文字朋友圈。", "发了一条朋友圈")
                }
            } else {
                val post = posts.get(postId!!) ?: fail("这条动态已删除或不存在，没有修改朋友圈。")
                if (action == "like_feed") {
                    if (liked && post.authorId == taId) fail("不能赞自己的帖子。")
                    val states = FeedInteractions.decode(post.interactions)
                    val current = states.firstOrNull { it.taId == taId } ?: FeedInteraction(taId)
                    val changed = current.liked != liked
                    if (changed) posts.update(post.copy(interactions = FeedInteractions.encode(
                        states.filterNot { it.taId == taId } + current.copy(liked = liked))))
                    outcome = result(post.id, if (liked) "这条动态现在有你的赞。" else "这条动态现在没有你的赞。",
                        if (liked) "已点赞" else "已取消赞", applied = changed, liked = liked)
                } else {
                    val comments = FeedComments.decode(post.comments)
                    val target = replyTo?.let { id -> comments.firstOrNull { it.id == id } ?: fail("这条评论已不存在。") }
                    if (target?.authorId == taId) fail("不能回复自己的评论。")
                    if (post.authorId == taId && target == null) fail("这是你自己的帖子；可以回复朋友评论，不要对自己的正文再留言。")
                    if (FeedInteractions.duplicate(post, taId, content!!)) {
                        outcome = result(post.id, "这条评论已经留过，没有重复留言。", "已有这条评论", applied = false)
                    } else {
                        val comment = FeedComment(UUID.randomUUID().toString(), taId, content, clock(), replyTo)
                        posts.update(post.copy(comments = FeedComments.encode(comments + comment),
                            interactions = FeedInteractions.record(post, taId, replyTo, false)))
                        outcome = result(post.id, "已以你的 TA 身份留下评论。", "留了一条评论", commentId = comment.id)
                    }
                }
            }
        }
        return checkNotNull(outcome)
    }

    private fun result(postId: Long, message: String, note: String, applied: Boolean = true,
        liked: Boolean? = null, commentId: String? = null) = ToolOutcome(buildJsonObject {
        put("ok", true)
        put("applied", applied)
        put("post_id", postId)
        put("message", message)
        liked?.let { put("liked_by_you", it) }
        commentId?.let { put("comment_id", it) }
    }.toString(), note)
}
