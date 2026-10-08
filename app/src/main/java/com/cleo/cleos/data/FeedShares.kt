package com.cleo.cleos.data

import com.cleo.cleos.data.db.FeedPostEntity
import com.cleo.cleos.data.db.isTopic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A snapshot for the chat draft; sharing never sends a message by itself. */
object FeedShares {
    fun text(post: FeedPostEntity, authorName: String, recipientId: Long): String = buildString {
        append("【转发").append(if (post.isTopic) "话题" else "朋友圈").append("】\n")
        append("作者：").append(authorName)
        if (post.authorId == recipientId && post.authorId != 0L) append("（你自己）")
        append("\n发布时间：").append(SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA).format(Date(post.createdAt)))
        append("\n正文：\n").append(post.content)
        post.sourceTitle?.takeIf { it.isNotBlank() }?.let { append("\n来源：").append(it) }
        post.sourceUrl?.takeIf { it.isNotBlank() }?.let { append("\n来源链接：").append(it) }
    }
}
