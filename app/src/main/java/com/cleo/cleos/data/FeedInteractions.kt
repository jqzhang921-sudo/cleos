package com.cleo.cleos.data

import com.cleo.cleos.data.db.FeedPostEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FeedInteraction(val taId: Long, val liked: Boolean = false, val reviewed: List<String> = emptyList())

/** Persist once-per-target invitations, including when a TA just reads without replying. */
object FeedInteractions {
    private val json = Json { ignoreUnknownKeys = true }
    fun decode(raw: String?): List<FeedInteraction> = if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<FeedInteraction>>(raw) }.getOrDefault(emptyList())
    fun encode(states: List<FeedInteraction>): String = json.encodeToString(states)
    fun key(replyTo: String?): String = replyTo?.let { "comment:$it" } ?: "post"
    fun reviewed(post: FeedPostEntity, taId: Long, replyTo: String?): Boolean =
        decode(post.interactions).any { it.taId == taId && key(replyTo) in it.reviewed }
    fun canInvite(post: FeedPostEntity, taId: Long, replyTo: String?): Boolean {
        if (reviewed(post, taId, replyTo)) return false
        if (replyTo == null) return post.authorId != taId
        return FeedComments.decode(post.comments).any { it.id == replyTo && it.authorId != taId }
    }
    fun record(post: FeedPostEntity, taId: Long, replyTo: String?, like: Boolean): String {
        val states = decode(post.interactions)
        val previous = states.firstOrNull { it.taId == taId } ?: FeedInteraction(taId)
        return encode(states.filterNot { it.taId == taId } + previous.copy(
            liked = previous.liked || (like && post.authorId != taId),
            reviewed = (previous.reviewed + key(replyTo)).distinct()))
    }
    fun duplicate(post: FeedPostEntity, taId: Long, content: String): Boolean {
        fun normalized(text: String) = text.filterNot { it.isWhitespace() || it in "，。！？、,.!?；;：:" }
        val words = normalized(content)
        return words.isNotEmpty() && FeedComments.decode(post.comments).any {
            it.authorId == taId && normalized(it.content) == words
        }
    }
}
