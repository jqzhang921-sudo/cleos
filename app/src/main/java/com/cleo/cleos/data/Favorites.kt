package com.cleo.cleos.data

import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.FavoriteEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A snapshot, not a live reference: deleting a conversation must not delete what was kept. */
@Serializable
data class FavoritePart(
    val messageId: Long,
    val at: Long,
    val role: String,
    val name: String,
    val text: String,
    val audio: MessageAudio? = null,
    val images: List<MessageImage> = emptyList(),
    val gapBefore: Boolean = false,
    val letter: com.cleo.cleos.data.db.LetterEntity? = null,
)

object FavoriteContent {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(parts: List<FavoritePart>): String = json.encodeToString(parts)
    fun decode(raw: String): List<FavoritePart> = runCatching { json.decodeFromString<List<FavoritePart>>(raw) }.getOrDefault(emptyList())
    fun eligible(m: MessageEntity): Boolean = m.role in setOf("user", "assistant") && m.note == null &&
        (m.letterId != null || m.content.isNotBlank() || m.audio != null || MessageImages.decode(m.images).isNotEmpty())

    /** Gaps count visible messages, not hidden tool calls. Ordering is the database's stable order. */
    fun select(history: List<MessageEntity>, ids: Set<Long>, me: String, ai: String,
               letters: Map<Long, com.cleo.cleos.data.db.LetterEntity> = emptyMap()): List<FavoritePart> {
        val visible = history.filter(::eligible).sortedWith(compareBy({ it.createdAt }, { it.id }))
        var previous = -1
        return visible.mapIndexedNotNull { index, m ->
            if (m.id !in ids) return@mapIndexedNotNull null
            FavoritePart(m.id, m.createdAt, m.role, if (m.role == "user") me else ai,
                m.letterId?.let { letters[it]?.let { l -> InstantLetterData.content(l, InstantLetterData.MAX_BODY) } } ?: m.content,
                MessageAudios.decode(m.audio), MessageImages.decode(m.images),
                gapBefore = previous >= 0 && index != previous + 1, letter = m.letterId?.let { letters[it] }).also { previous = index }
        }
    }

    fun files(parts: List<FavoritePart>): List<String> = parts.flatMap { p -> p.images.map { it.file } + listOfNotNull(p.audio?.file) }
    fun matches(entry: FavoriteEntity, query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || entry.note.contains(q, ignoreCase = true) || decode(entry.parts).any {
            it.text.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true)
        }
    }
}

class Favorites(private val db: AppDatabase, private val settings: SettingsRepository, private val images: ImageStore) {
    private val lock = Mutex()

    suspend fun saveLetter(id: Long): Long = lock.withLock {
        db.withTransaction {
            val letter = db.letters().get(id)?.takeUnless { it.draft } ?: error("这封信还没有寄出或已删除")
            val ta = db.companions().get(letter.companionId) ?: error("这位 TA 已不存在")
            val linked = db.letters().linkedMessage(id)
            val conversation = linked?.conversationId ?: -ta.id
            val key = "letter:$id:${letter.createdAt}"
            db.favorites().find(conversation, key)?.let { return@withTransaction it.id }
            val part = FavoritePart(linked?.id ?: 0, letter.deliverAt ?: letter.createdAt,
                if (letter.author == "me") "user" else "assistant",
                if (letter.author == "me") settings.current().userName.ifBlank { "我" } else ta.name,
                InstantLetterData.content(letter, InstantLetterData.MAX_BODY), letter = letter)
            db.favorites().insert(FavoriteEntity(companionId = ta.id, conversationId = conversation,
                sourceKey = key, parts = FavoriteContent.encode(listOf(part)), createdAt = System.currentTimeMillis()))
        }
    }

    suspend fun save(conversationId: Long, ids: Set<Long>): Long = withContext(Dispatchers.IO) {
        lock.withLock {
            val copied = mutableListOf<String>()
            try {
                db.withTransaction {
                    val conversation = db.conversations().get(conversationId) ?: error("这段对话已经不在了")
                    val ta = db.companions().get(conversation.companionId) ?: error("这个 TA 已经不在了")
                    val s = settings.current()
                    val parts = FavoriteContent.select(db.messages().forFavorite(conversationId), ids,
                        s.userName.ifBlank { "我" }, ta.name.ifBlank { "TA" }, db.letters().allFor(ta.id).filter { !it.draft }.associateBy { it.id })
                    require(parts.isNotEmpty() && parts.size == ids.size) { "有消息已经变了，请重新选择" }
                    val key = parts.joinToString(",") { "${it.messageId}:${it.at}" }
                    db.favorites().find(conversationId, key)?.let { return@withTransaction it.id }
                    fun copy(name: String, voice: Boolean): String {
                        require(File(name).name == name && !name.startsWith(".")) { "附件文件名不正确" }
                        val source = images.file(name)
                        check(source.isFile) { "原消息的附件已丢失，暂时不能收藏" }
                        val target = (if (voice) "voice_favorite_" else "favorite_") + UUID.randomUUID() + "." + source.extension
                        copied += target
                        source.copyTo(images.file(target))
                        return target
                    }
                    val snapshots = parts.map { p -> p.copy(
                        audio = p.audio?.let { it.copy(file = copy(it.file, true)) },
                        images = p.images.map { it.copy(file = copy(it.file, false)) },
                    ) }
                    db.favorites().insert(FavoriteEntity(companionId = ta.id, conversationId = conversationId,
                        sourceKey = key, parts = FavoriteContent.encode(snapshots), createdAt = System.currentTimeMillis()))
                }
            } catch (e: Exception) {
                images.delete(copied)
                throw e
            }
        }
    }

    suspend fun note(id: Long, text: String) = db.favorites().setNote(id, text.trim())
    suspend fun remove(id: Long) = db.favorites().setRemoved(id, System.currentTimeMillis())
    suspend fun undo(id: Long) = db.favorites().setRemoved(id, null)

    /** Kept briefly for undo. Only files owned by removed snapshots are collected. */
    suspend fun prune() = withContext(Dispatchers.IO) {
        lock.withLock {
            val old = db.favorites().removedBefore(System.currentTimeMillis() - 86_400_000)
            for (entry in old) {
                db.favorites().delete(entry.id)
                images.delete(FavoriteContent.files(FavoriteContent.decode(entry.parts)).filter {
                    File(it).name == it && (it.startsWith("favorite_") || it.startsWith("voice_favorite_"))
                })
            }
        }
    }
}
