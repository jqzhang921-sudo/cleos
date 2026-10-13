package com.cleo.cleos.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "chat_groups")
data class ChatGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val nextSpeaker: Int = 0,
    val draft: String = "",
    val mentions: String = "[]",
)

/** No TA foreign key: deleting a TA must not erase a shared conversation. Avatars are owned copies. */
@Serializable
@Entity(tableName = "group_members", primaryKeys = ["groupId", "companionId"],
    foreignKeys = [ForeignKey(entity = ChatGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)])
data class GroupMemberEntity(val groupId: Long, val companionId: Long, val position: Int,
    val name: String, val avatar: String? = null, val emoji: String? = null,
    @ColumnInfo(defaultValue = "1") val active: Boolean = true)

@Serializable
@Entity(tableName = "group_turns", indices = [Index("groupId")],
    foreignKeys = [ForeignKey(entity = ChatGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)])
data class GroupTurnEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long, val userMessageId: Long, val targets: String, val createdAt: Long, val status: String = "running")

@Serializable
@Entity(tableName = "group_messages", indices = [Index("groupId"), Index("turnId"), Index(value = ["turnId", "speakerId"], unique = true)],
    foreignKeys = [ForeignKey(entity = ChatGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = GroupTurnEntity::class, parentColumns = ["id"], childColumns = ["turnId"], onDelete = ForeignKey.CASCADE)])
data class GroupMessageEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long, val role: String, val content: String, val createdAt: Long,
    val speakerId: Long? = null, val speakerName: String = "", val speakerAvatar: String? = null,
    val speakerEmoji: String? = null, val turnId: Long? = null, val status: String = "sent", val error: String? = null,
    val mentions: String = "[]",
    @ColumnInfo(defaultValue = "'{}'") val toolTrace: String = "{}",
    @ColumnInfo(defaultValue = "NULL") val images: String? = null)

/** User-curated shared notes. No link to any private memory or conversation. */
@Serializable
@Entity(tableName = "group_memories", indices = [Index("groupId"), Index("sourceMessageId"),
    Index(value = ["groupId", "sourceMessageId"], unique = true)],
    foreignKeys = [ForeignKey(entity = ChatGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = GroupMessageEntity::class, parentColumns = ["id"], childColumns = ["sourceMessageId"], onDelete = ForeignKey.SET_NULL)])
data class GroupMemoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long, val title: String, val content: String, val createdAt: Long, val updatedAt: Long,
    val sourceMessageId: Long? = null, val sourceKind: String = "manual")

@Dao
interface GroupDao {
    @Query("SELECT * FROM group_memories WHERE groupId = :id ORDER BY updatedAt DESC, id DESC") fun observeMemories(id: Long): Flow<List<GroupMemoryEntity>>
    @Query("SELECT * FROM group_memories WHERE groupId = :id ORDER BY updatedAt DESC, id DESC") suspend fun memories(id: Long): List<GroupMemoryEntity>
    @Query("SELECT * FROM group_memories WHERE groupId = :groupId AND id = :id") suspend fun memory(groupId: Long, id: Long): GroupMemoryEntity?
    @Insert suspend fun insertMemory(row: GroupMemoryEntity): Long
    @Query("UPDATE group_memories SET title = :title, content = :content, updatedAt = :at WHERE groupId = :groupId AND id = :id")
    suspend fun editMemory(groupId: Long, id: Long, title: String, content: String, at: Long): Int
    @Query("DELETE FROM group_memories WHERE groupId = :groupId AND id = :id") suspend fun deleteMemory(groupId: Long, id: Long): Int
    @Query("SELECT * FROM group_memories") suspend fun allMemories(): List<GroupMemoryEntity>
    @Insert suspend fun restoreMemories(rows: List<GroupMemoryEntity>)
    @Query("SELECT * FROM chat_groups ORDER BY updatedAt DESC, id DESC") fun observeGroups(): Flow<List<ChatGroupEntity>>
    @Query("SELECT * FROM chat_groups WHERE id = :id") fun observeGroup(id: Long): Flow<ChatGroupEntity?>
    @Query("SELECT * FROM group_members WHERE groupId = :id ORDER BY position") fun observeMembers(id: Long): Flow<List<GroupMemberEntity>>
    @Query("SELECT * FROM group_messages WHERE groupId = :id ORDER BY id") fun observeMessages(id: Long): Flow<List<GroupMessageEntity>>
    @Query("SELECT * FROM chat_groups WHERE id = :id") suspend fun group(id: Long): ChatGroupEntity?
    @Query("SELECT * FROM group_members WHERE groupId = :id ORDER BY position") suspend fun members(id: Long): List<GroupMemberEntity>
    @Query("SELECT * FROM group_messages WHERE groupId = :id AND id < :before AND (content != '' OR images IS NOT NULL) AND (role = 'user' OR status = 'sent' OR images IS NOT NULL) ORDER BY id DESC LIMIT :limit")
    suspend fun history(id: Long, before: Long, limit: Int): List<GroupMessageEntity>
    @Query("SELECT * FROM group_messages WHERE id = :id") suspend fun message(id: Long): GroupMessageEntity?
    @Query("SELECT * FROM group_messages WHERE turnId = :id ORDER BY id") suspend fun replies(id: Long): List<GroupMessageEntity>
    @Query("SELECT * FROM group_turns WHERE id = :id") suspend fun turn(id: Long): GroupTurnEntity?
    @Query("SELECT * FROM group_turns WHERE groupId = :id ORDER BY id DESC LIMIT 1") suspend fun latestTurn(id: Long): GroupTurnEntity?
    @Insert suspend fun insertGroup(group: ChatGroupEntity): Long
    @Insert suspend fun insertMembers(members: List<GroupMemberEntity>)
    @Upsert suspend fun saveMembers(members: List<GroupMemberEntity>)
    @Insert suspend fun insertTurn(turn: GroupTurnEntity): Long
    @Insert suspend fun insertMessage(message: GroupMessageEntity): Long
    @Query("UPDATE chat_groups SET title = :title WHERE id = :id") suspend fun rename(id: Long, title: String)
    @Query("UPDATE chat_groups SET draft = :text, mentions = :mentions WHERE id = :id") suspend fun draft(id: Long, text: String, mentions: String)
    @Query("UPDATE chat_groups SET updatedAt = :at, nextSpeaker = :next, draft = '', mentions = '[]' WHERE id = :id") suspend fun advance(id: Long, at: Long, next: Int)
    @Query("UPDATE chat_groups SET updatedAt = :at WHERE id = :id") suspend fun touch(id: Long, at: Long)
    @Query("UPDATE chat_groups SET updatedAt = :at, nextSpeaker = 0, mentions = :mentions WHERE id = :id")
    suspend fun membershipChanged(id: Long, at: Long, mentions: String)
    @Query("UPDATE group_turns SET status = :status WHERE id = :id") suspend fun turnStatus(id: Long, status: String)
    @Query("UPDATE group_messages SET status = 'running', error = NULL, content = '', toolTrace = '{}', images = NULL WHERE id = :id AND status IN ('pending', 'failed', 'cancelled', 'interrupted')") suspend fun beginReply(id: Long): Int
    @Query("UPDATE group_messages SET images = :images WHERE id = :id AND groupId = :groupId AND speakerId = :speaker AND turnId = :turn AND status = 'running' AND EXISTS(SELECT 1 FROM group_turns WHERE id = :turn AND groupId = :groupId AND status = 'running') AND EXISTS(SELECT 1 FROM group_members WHERE groupId = :groupId AND companionId = :speaker AND active = 1)")
    suspend fun saveImages(id: Long, groupId: Long, speaker: Long, turn: Long, images: String): Int
    @Query("UPDATE group_messages SET toolTrace = :trace WHERE id = :id AND groupId = :groupId AND turnId = :turn AND status = 'running' AND EXISTS(SELECT 1 FROM group_turns WHERE id = :turn AND status = 'running')")
    suspend fun saveToolTrace(id: Long, groupId: Long, turn: Long, trace: String): Int
    @Query("UPDATE group_messages SET content = :content, status = :status, error = :error WHERE id = :id AND status = 'running' AND EXISTS(SELECT 1 FROM group_turns WHERE id = :turn AND status = 'running')")
    suspend fun finishReply(id: Long, turn: Long, content: String, status: String, error: String?): Int
    @Query("UPDATE group_messages SET status = :status, error = :error WHERE groupId = :id AND status IN ('pending', 'running')") suspend fun interruptMessages(id: Long, status: String, error: String)
    @Query("UPDATE group_turns SET status = :status WHERE groupId = :id AND status = 'running'") suspend fun interruptTurns(id: Long, status: String)
    @Query("UPDATE group_messages SET status = 'interrupted', error = '上次回复中断了，可以重试' WHERE status IN ('pending', 'running')") suspend fun recoverMessages()
    @Query("UPDATE group_turns SET status = 'interrupted' WHERE status = 'running'") suspend fun recoverTurns()
    @Query("DELETE FROM chat_groups WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM chat_groups") suspend fun clear()
    @Query("SELECT * FROM chat_groups") suspend fun allGroups(): List<ChatGroupEntity>
    @Query("SELECT * FROM group_members") suspend fun allMembers(): List<GroupMemberEntity>
    @Query("SELECT * FROM group_turns") suspend fun allTurns(): List<GroupTurnEntity>
    @Query("SELECT * FROM group_messages") suspend fun allMessages(): List<GroupMessageEntity>
    @Insert suspend fun restoreGroups(rows: List<ChatGroupEntity>)
    @Insert suspend fun restoreTurns(rows: List<GroupTurnEntity>)
    @Insert suspend fun restoreMessages(rows: List<GroupMessageEntity>)
}
