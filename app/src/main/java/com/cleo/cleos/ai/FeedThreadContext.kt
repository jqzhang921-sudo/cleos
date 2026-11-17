package com.cleo.cleos.ai

import com.cleo.cleos.data.FeedComment
import com.cleo.cleos.data.FeedComments
import com.cleo.cleos.data.db.FeedPostEntity
import kotlinx.serialization.json.*

/** One post and the actual reply ancestry. Never replace missing parents with unrelated recent comments. */
internal object FeedThreadContext {
    private fun clip(text: String, limit: Int) = text.take(if (text.length > limit && text[limit - 1].isHighSurrogate()) limit - 1 else minOf(text.length, limit))
    const val RULE = """post_text 是原帖，target 是本次要回应的评论，reply_chain 是它真实的回复链。每条评论的 author 是说话者，reply_to_author 是它正在回复的人，reply_to 是对应评论编号。A 回复 B 不等于 A 在回复你；不要把 B 的话算到 A 身上，也不要替其他人代答。role=self 才是你说的话，role=user 是用户，role=friend 是其他 TA；名字相同时仍按 ID 区分。其他 TA 的玩笑和比喻是他们的表达，不是你亲眼看到或亲自做过的事。可以自然接话和开玩笑；上下文缺失、看不懂指代时，不编造谁说过什么，可以不评论或只点赞。"""
    fun render(post: FeedPostEntity, ta: Long, replyTo: String?, names: Map<Long, String>, userName: String): String {
        val comments = FeedComments.decode(post.comments)
        val byId = comments.associateBy { it.id }
        val chain = mutableListOf<FeedComment>()
        val seen = mutableSetOf<String>()
        var next = replyTo
        while (next != null && chain.size < 6 && seen.add(next)) {
            val row = byId[next] ?: break
            chain += row; next = row.replyTo
        }
        fun author(id: Long) = buildJsonObject {
            put("id", id); put("name", if (id == 0L) userName else names[id]?.ifBlank { "TA" } ?: "TA")
            put("role", when (id) { 0L -> "user"; ta -> "self"; else -> "friend" })
        }
        fun comment(row: FeedComment) = buildJsonObject {
            put("id", row.id); put("author", author(row.authorId)); put("content", clip(row.content, 1000))
            put("content_truncated", row.content.length > 1000)
            put("reply_to", row.replyTo?.let(::JsonPrimitive) ?: JsonNull)
            put("reply_to_author", if (row.replyTo == null) author(post.authorId)
                else byId[row.replyTo]?.let { parent -> author(parent.authorId) } ?: JsonNull)
            put("reply_to_post", row.replyTo == null)
        }
        return buildJsonObject {
            put("post_id", post.id); put("post_author", author(post.authorId)); put("post_text", clip(post.content, 4000))
            put("post_text_truncated", post.content.length > 4000)
            put("source_label", post.sourceTitle.orEmpty())
            put("requested_reply_to", replyTo?.let(::JsonPrimitive) ?: JsonNull)
            put("target", replyTo?.let { byId[it]?.let(::comment) } ?: JsonNull)
            put("reply_chain", JsonArray(chain.asReversed().map(::comment)))
            put("context_incomplete", next != null)
            put("your_recent_comments", JsonArray(comments.filter { it.authorId == ta && it.id !in seen }.takeLast(2).map(::comment)))
        }.toString()
    }
}
