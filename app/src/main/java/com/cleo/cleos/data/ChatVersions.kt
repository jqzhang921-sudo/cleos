package com.cleo.cleos.data

import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.ChatVersionEntity
import com.cleo.cleos.data.db.ChatVersionMessageEntity
import com.cleo.cleos.data.db.MessageEntity

internal data class ChatRevision(
    val prefix: List<MessageEntity>,
    val tail: List<MessageEntity>,
    val display: MessageEntity,
) {
    val root get() = tail.first()
}

internal class ChatVersions(private val db: AppDatabase) {
    private val messages get() = db.messages()
    private val versions get() = db.chatVersions()

    suspend fun prepare(conversation: Long, display: MessageEntity): ChatRevision? = db.withTransaction {
        val history = messages.forFavorite(conversation)
        val index = history.indexOf(display)
        if (index < 0) return@withTransaction null
        val existing = versions.forConversation(conversation).firstOrNull { it.displayId == display.id }
        val pivot = if (existing != null) history.indexOfFirst { it.id == existing.rootId }
        else replyStart(history, index)
        if (pivot < 0) return@withTransaction null
        ChatRevision(history.take(pivot), history.drop(pivot), display)
    }

    /** The old timeline is replaced only after the complete new reply was obtained. */
    suspend fun commit(plan: ChatRevision, replacement: List<MessageEntity>, kind: String, now: Long): Boolean =
        db.withTransaction {
            val old = plan.root
            val current = messages.tail(old.conversationId, old.createdAt, old.id)
            if (current != plan.tail) return@withTransaction false
            val original = versions.version(old.id) ?: ChatVersionEntity(old.id, old.conversationId, old.id,
                plan.display.id, ChatVersionEntity.ORIGINAL, preview(plan.display), old.createdAt).also { versions.addVersion(it) }
            hide(current, old)
            val ids = ArrayList<Long>(replacement.size)
            for (row in replacement) {
                val quote = MessageQuotes.decode(row.quote)
                val editedQuote = quote?.takeIf { kind == ChatVersionEntity.EDIT && ids.isNotEmpty() && it.id == old.id }
                    ?.copy(id = ids.first())
                ids += messages.insert(if (editedQuote == null) row else row.copy(quote = MessageQuotes.encode(editedQuote)))
            }
            val displayIndex = if (kind == ChatVersionEntity.EDIT) 0 else replacement.lastIndex
            versions.addVersion(ChatVersionEntity(ids.first(), old.conversationId, original.groupId, ids[displayIndex],
                kind, preview(replacement[displayIndex]), now))
            changed(old.conversationId, now)
            true
        }

    suspend fun switch(conversation: Long, from: Long, to: Long, now: Long): Long? = db.withTransaction {
        if (from == to) return@withTransaction null
        val current = versions.version(from)?.takeIf { it.conversationId == conversation } ?: return@withTransaction null
        val target = versions.version(to)?.takeIf {
            it.conversationId == conversation && it.groupId == current.groupId
        } ?: return@withTransaction null
        val old = messages.get(current.rootId)?.takeIf { it.conversationId == conversation } ?: return@withTransaction null
        val back = versions.messages(target.rootId).map { it.message }
        if (back.firstOrNull()?.id != target.rootId || back.none { it.id == target.displayId }) return@withTransaction null
        hide(messages.tail(conversation, old.createdAt, old.id), old)
        versions.unarchive(target.rootId)
        messages.insertAll(back)
        changed(conversation, now)
        target.displayId
    }

    private suspend fun hide(rows: List<MessageEntity>, root: MessageEntity) {
        versions.archive(rows.map { ChatVersionMessageEntity(it, root.id) })
        messages.deleteTail(root.conversationId, root.createdAt, root.id)
    }

    private suspend fun changed(conversation: Long, now: Long) {
        db.conversations().invalidateDeletedHistory(conversation)
        db.conversations().touch(conversation, now)
    }

    /** Called within the message deletion transaction, including nested versions of discarded tails. */
    suspend fun removeForDeleted(conversation: Long, ids: Set<Long>): List<MessageEntity> {
        val options = versions.forConversation(conversation)
        val removed = ArrayList<MessageEntity>()
        val gone = ids.toMutableSet()
        val groups = options.groupBy { it.groupId }.toMutableMap()
        while (true) {
            val group = groups.entries.firstOrNull { (_, variants) ->
                variants.any { it.rootId in gone || it.displayId in gone }
            } ?: break
            val rows = group.value.flatMap { option -> versions.messages(option.rootId).map { it.message } }
            removed += rows
            gone += rows.map { it.id }
            versions.deleteGroup(group.key)
            groups.remove(group.key)
        }
        return removed
    }

    companion object {
        /** Consecutive bubbles written for one user turn share a version picker. */
        fun replyStart(history: List<MessageEntity>, index: Int): Int {
            val reply = history[index]
            if (reply.role != "assistant" || reply.proactive) return index
            var first = index
            while (first > 0) {
                val previous = history[first - 1]
                if (previous.role !in setOf("assistant", "tool") || previous.proactive ||
                    previous.call != null || previous.letterId != null) break
                first--
            }
            return first
        }

        private fun preview(row: MessageEntity): String = FeedShares.decode(row.feedShare)?.caption
            ?.takeIf { it.isNotBlank() }?.take(160) ?: StickerText.plain(row.content).trim().take(160)
            .ifEmpty { if (row.images != null) "图片" else if (row.error != null) "未完成的回复" else "消息" }

        fun media(rows: List<MessageEntity>): List<String> = rows.flatMap { row ->
            MessageImages.decode(row.images).map { it.file } + listOfNotNull(MessageAudios.decode(row.audio)?.file)
        }

        fun validateBackup(
            conversationIds: Set<Long>,
            active: List<MessageEntity>,
            versions: List<ChatVersionEntity>,
            archived: List<ChatVersionMessageEntity>,
        ) {
            if (versions.isEmpty() && archived.isEmpty()) return
            val byRoot = versions.associateBy { it.rootId }
            val hidden = archived.associateBy { it.message.id }
            val live = active.associateBy { it.id }
            val error = "备份里的聊天版本数据不完整"
            require(byRoot.size == versions.size && hidden.size == archived.size && hidden.keys.none { it in live }) { error }
            for (option in versions) {
                val root = live[option.rootId] ?: hidden[option.rootId]?.message
                val display = live[option.displayId] ?: hidden[option.displayId]?.message
                require(option.conversationId in conversationIds && root?.conversationId == option.conversationId &&
                    display?.conversationId == option.conversationId &&
                    byRoot[option.groupId]?.conversationId == option.conversationId &&
                    hidden[option.rootId]?.versionRoot == hidden[option.displayId]?.versionRoot) { error }
            }
            require(archived.all { row ->
                val owner = byRoot[row.versionRoot]
                owner?.conversationId == row.message.conversationId && hidden[row.versionRoot]?.versionRoot == row.versionRoot
            }) { error }
        }
    }
}
