package com.cleo.cleos.data

import com.cleo.cleos.data.db.FeedPostEntity
import com.cleo.cleos.data.db.isTopic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FeedShare(
    val authorName: String,
    val content: String,
    val createdAt: Long,
    val topic: Boolean = false,
    val ownPost: Boolean = false,
    val sourceTitle: String? = null,
    val sourceUrl: String? = null,
    val caption: String = "",
)

/** A snapshot for the chat draft; sharing never sends a message by itself. */
object FeedShares {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(share: FeedShare): String = json.encodeToString(share)
    fun decode(raw: String?): FeedShare? = raw?.let { runCatching { json.decodeFromString<FeedShare>(it) }.getOrNull() }
    fun of(post: FeedPostEntity, authorName: String, recipientId: Long) = FeedShare(
        authorName, post.content, post.createdAt, post.isTopic,
        post.authorId == recipientId && post.authorId != 0L, post.sourceTitle, post.sourceUrl)

    fun text(post: FeedPostEntity, authorName: String, recipientId: Long): String = text(of(post, authorName, recipientId))

    /** Plain history for models, search, quotes and backups; the UI renders the snapshot as a card. */
    fun text(share: FeedShare): String = buildString {
        if (share.caption.isNotBlank()) append(share.caption.trim()).append("\n\n")
        append("【转发").append(if (share.topic) "话题" else "朋友圈").append("】\n")
        append("作者：").append(share.authorName)
        if (share.ownPost) append("（你自己）")
        append("\n发布时间：").append(SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(share.createdAt)))
        append("\n正文：\n").append(share.content)
        share.sourceTitle?.takeIf { it.isNotBlank() }?.let { append("\n来源：").append(it) }
        share.sourceUrl?.takeIf { it.isNotBlank() }?.let { append("\n来源链接：").append(it) }
    }
}
