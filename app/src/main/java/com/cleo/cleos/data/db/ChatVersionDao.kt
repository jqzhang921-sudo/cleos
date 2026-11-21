package com.cleo.cleos.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "chat_versions",
    indices = [Index("conversationId"), Index("groupId"), Index("displayId")],
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"],
        childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
)
data class ChatVersionEntity(
    @PrimaryKey val rootId: Long,
    val conversationId: Long,
    val groupId: Long,
    val displayId: Long,
    val kind: String,
    val preview: String,
    val savedAt: Long,
) {
    companion object {
        const val ORIGINAL = "original"
        const val RETRY = "retry"
        const val EDIT = "edit"
    }
}

/** Rows in an unselected timeline keep their original IDs and their own media. */
@Serializable
@Entity(
    tableName = "chat_version_messages",
    primaryKeys = ["id"],
    indices = [Index("versionRoot"), Index("conversationId")],
    foreignKeys = [ForeignKey(entity = ChatVersionEntity::class, parentColumns = ["rootId"],
        childColumns = ["versionRoot"], onDelete = ForeignKey.CASCADE)],
)
data class ChatVersionMessageEntity(
    @Embedded val message: MessageEntity,
    val versionRoot: Long,
)

@Dao
interface ChatVersionDao {
    @Query("SELECT * FROM chat_versions WHERE conversationId = :conversation ORDER BY savedAt, rootId")
    fun observe(conversation: Long): Flow<List<ChatVersionEntity>>

    @Query("SELECT * FROM chat_versions WHERE conversationId = :conversation")
    suspend fun forConversation(conversation: Long): List<ChatVersionEntity>

    @Query("SELECT * FROM chat_versions WHERE rootId = :id")
    suspend fun version(id: Long): ChatVersionEntity?

    @Query("SELECT * FROM chat_version_messages WHERE versionRoot = :root ORDER BY createdAt,id")
    suspend fun messages(root: Long): List<ChatVersionMessageEntity>

    @Query("SELECT * FROM chat_version_messages WHERE conversationId = :conversation")
    suspend fun archivedIn(conversation: Long): List<ChatVersionMessageEntity>

    @Insert suspend fun addVersion(version: ChatVersionEntity)
    @Insert suspend fun addVersions(versions: List<ChatVersionEntity>)
    @Insert suspend fun archive(rows: List<ChatVersionMessageEntity>)

    @Query("DELETE FROM chat_version_messages WHERE versionRoot = :root")
    suspend fun unarchive(root: Long)

    @Query("DELETE FROM chat_versions WHERE groupId = :group")
    suspend fun deleteGroup(group: Long)

    @Query("SELECT * FROM chat_versions")
    suspend fun allVersions(): List<ChatVersionEntity>

    @Query("SELECT * FROM chat_version_messages")
    suspend fun allMessages(): List<ChatVersionMessageEntity>
}
