package com.cleo.cleos.data

import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.FeedPostEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

object FeedPostRules {
    const val MAX_PHOTOS = 9
    fun content(text: String, pictures: List<MessageImage>): String = text.trim().also {
        require(it.isNotBlank() || pictures.isNotEmpty()) { "写点什么，或选一张照片" }
        require(it.length <= 4000) { "正文最多 4000 字" }
        require(pictures.size <= MAX_PHOTOS) { "最多选择 9 张照片" }
    }
}

@Serializable
data class FeedComment(val id: String, val authorId: Long = 0, val content: String, val createdAt: Long)

object FeedComments {
    private val json = Json { ignoreUnknownKeys = true }
    fun decode(raw: String?): List<FeedComment> = if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<FeedComment>>(raw) }.getOrDefault(emptyList())
    fun encode(comments: List<FeedComment>): String = json.encodeToString(comments)
    fun text(raw: String): String = raw.trim().also { require(it.isNotBlank() && it.length <= 4000) { "请输入 1–4000 字的内容" } }
}

class FeedRepository(private val db: AppDatabase, private val images: ImageStore) {
    val posts = db.feed().observe()
    suspend fun publish(text: String, topic: Boolean = false, pictures: List<MessageImage> = emptyList()): Long = withContext(Dispatchers.IO + NonCancellable) {
        val content = FeedPostRules.content(text, pictures)
        val owned = MessageImageCopies.copy(images.dir, pictures)
        try { db.feed().insert(FeedPostEntity(content = content, createdAt = System.currentTimeMillis(),
            kind = if (topic) "topic" else "moments", images = MessageImages.encode(owned))) }
        catch (e: Exception) { images.delete(owned.map { it.file }); throw e }
    }
    suspend fun like(id: Long) = db.withTransaction {
        db.feed().get(id)?.let { db.feed().update(it.copy(liked = !it.liked)) }
    }
    suspend fun comment(id: Long, text: String, authorId: Long = 0) = db.withTransaction {
        val content = FeedComments.text(text)
        val post = db.feed().get(id) ?: error("这条动态已删除")
        val comments = FeedComments.decode(post.comments) + FeedComment(java.util.UUID.randomUUID().toString(), authorId = authorId, content = content, createdAt = System.currentTimeMillis())
        db.feed().update(post.copy(comments = FeedComments.encode(comments)))
    }
    suspend fun delete(id: Long) = withContext(Dispatchers.IO + NonCancellable) {
        val files = db.withTransaction {
            val post = db.feed().get(id)
            db.feed().delete(id)
            MessageImages.decode(post?.images).map { it.file }
        }
        images.delete(files)
    }
}
