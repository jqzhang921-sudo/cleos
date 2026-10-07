package com.cleo.cleos.data

import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.FeedPostEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FeedComment(val id: String, val authorId: Long = 0, val content: String, val createdAt: Long)

object FeedComments {
    private val json = Json { ignoreUnknownKeys = true }
    fun decode(raw: String?): List<FeedComment> = if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<FeedComment>>(raw) }.getOrDefault(emptyList())
    fun encode(comments: List<FeedComment>): String = json.encodeToString(comments)
    fun text(raw: String): String = raw.trim().also { require(it.isNotBlank() && it.length <= 4000) { "请输入 1–4000 字的内容" } }
}

class FeedRepository(private val db: AppDatabase) {
    val posts = db.feed().observe()
    suspend fun publish(text: String): Long = db.feed().insert(FeedPostEntity(content = FeedComments.text(text), createdAt = System.currentTimeMillis()))
    suspend fun like(id: Long) = db.withTransaction {
        db.feed().get(id)?.let { db.feed().update(it.copy(liked = !it.liked)) }
    }
    suspend fun comment(id: Long, text: String) = db.withTransaction {
        val content = FeedComments.text(text)
        val post = db.feed().get(id) ?: error("这条动态已删除")
        val comments = FeedComments.decode(post.comments) + FeedComment(java.util.UUID.randomUUID().toString(), content = content, createdAt = System.currentTimeMillis())
        db.feed().update(post.copy(comments = FeedComments.encode(comments)))
    }
    suspend fun delete(id: Long) = db.feed().delete(id)
}
