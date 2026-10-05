package com.cleo.cleos.data

import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The TAs: who they are, which one is being talked to, adding and removing them.
 *
 * There is always at least one. Before there could be several, the one TA lived in the
 * settings; [ensure] turns those into TA 1 the first time it runs, the id the database
 * migration already gave that TA's conversations and diary entries.
 */
class Companions(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val images: ImageStore,
) {
    private val lock = Mutex()

    val all: Flow<List<CompanionEntity>> = db.companions().observeAll()

    /** The TA being talked to: the one last chosen, or the first while it doesn't exist. */
    val current: Flow<CompanionEntity> = combine(all, settings.currentCompanion) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.filterNotNull()

    suspend fun ensure(): List<CompanionEntity> = lock.withLock {
        db.companions().all().ifEmpty {
            val legacy = settings.legacyTa()
            db.companions().insert(
                CompanionEntity(
                    id = FIRST,
                    name = legacy.name,
                    persona = legacy.persona,
                    apiBaseUrl = legacy.baseUrl,
                    apiModel = legacy.model,
                    avatar = legacy.avatar,
                    avatarEmoji = legacy.avatarEmoji,
                    knownSince = legacy.knownSince,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            secrets.adoptLegacyKey(legacy.baseUrl)
            db.companions().all()
        }
    }

    suspend fun current(): CompanionEntity {
        val list = ensure()
        val id = settings.currentCompanion.first()
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    suspend fun get(id: Long): CompanionEntity? = db.companions().get(id)

    /** Switch to [id]. The chat then opens their latest conversation. */
    suspend fun select(id: Long) {
        settings.setCurrentCompanion(id)
        settings.setCurrentConversation(null)
    }

    /**
     * A new TA, chosen right away. They start on the current TA's service and model (and its model
     * for words heard, if it has one), so the keys are already there; name and persona start empty,
     * to be written by the person.
     */
    suspend fun add(): Long {
        val from = current()
        val id = db.companions().insert(
            CompanionEntity(
                apiBaseUrl = from.apiBaseUrl,
                apiModel = from.apiModel,
                spokenModelOn = from.spokenModelOn,
                spokenApiBaseUrl = from.spokenApiBaseUrl,
                spokenApiModel = from.spokenApiModel,
                createdAt = System.currentTimeMillis(),
            ),
        )
        select(id)
        return id
    }

    suspend fun update(id: Long, transform: (CompanionEntity) -> CompanionEntity) {
        lock.withLock { db.companions().get(id)?.let { db.companions().update(transform(it)) } }
    }

    /** A picture ([file]) or an emoji, or neither for the initial; the replaced picture file is removed. */
    suspend fun setAvatar(id: Long, file: String?, emoji: String?) {
        val old = get(id)?.avatar
        update(id) { it.copy(avatar = file, avatarEmoji = emoji) }
        if (old != null && old != file) images.delete(listOf(old))
    }

    /**
     * Removes a TA with everything that was only theirs: conversations (and the pictures
     * sent in them), the diary entries they wrote, the letters between them, what they
     * remembered, their 设定, their avatar.
     * The last TA stays.
     */
    suspend fun delete(id: Long) {
        val list = ensure()
        val gone = list.firstOrNull { it.id == id } ?: return
        if (list.size <= 1) return
        val conversations = db.conversations().idsFor(id)
        val favorites = db.favorites().forCompanion(id)
        val keptFiles = favorites.flatMap { FavoriteContent.files(FavoriteContent.decode(it.parts)) }
        val pictures = conversations.flatMap { db.messages().imagesIn(it) }.flatMap { MessageImages.decode(it) }.map { it.file }
        db.withTransaction {
            conversations.forEach { db.conversations().delete(it) }
            db.diary().deleteWrittenBy(id)
            db.letters().deleteFor(id)
            db.memories().deleteFor(id)
            db.lore().deleteFor(id)
            db.favorites().deleteFor(id)
            db.companions().delete(id)
        }
        images.delete(pictures + keptFiles + listOfNotNull(gone.avatar))
        val talkingTo = settings.currentCompanion.first() ?: list.first().id
        if (talkingTo == id) select(list.first { it.id != id }.id)
    }

    companion object {
        /** The TA there was before there could be several; the migration gave them this id. */
        const val FIRST = 1L

        /**
         * How long a persona can be. It was 4000, until someone wrote theirs at 20,000 characters
         * and found no room for it. It all goes out with every message, so it stays bounded; but it
         * is near the start of the system prompt and rarely changes (Prompt.system), so a provider
         * that caches a repeated prefix charges the cached rate for it after the first message.
         * 30,000 Chinese characters are about 18k tokens as DeepSeek counts them (0.6 a character),
         * leaving most of a 128k context for the rest.
         */
        const val PERSONA_LIMIT = 30_000
    }
}
