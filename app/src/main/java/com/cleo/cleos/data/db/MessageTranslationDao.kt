package com.cleo.cleos.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Local display cache only: never part of MessageEntity, model history, memories or exported backups. */
@Entity(tableName = "message_translations", indices = [
    Index(value = ["privateMessageId", "language"], unique = true),
    Index(value = ["groupMessageId", "language"], unique = true),
], foreignKeys = [
    ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["privateMessageId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = GroupMessageEntity::class, parentColumns = ["id"], childColumns = ["groupMessageId"], onDelete = ForeignKey.CASCADE),
])
data class MessageTranslationEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val privateMessageId: Long? = null, val groupMessageId: Long? = null, val language: String,
    val sourceStamp: String, val text: String, val createdAt: Long)

@Dao
interface MessageTranslationDao {
    @Query("SELECT * FROM message_translations WHERE privateMessageId = :id AND groupMessageId IS NULL ORDER BY createdAt DESC")
    fun observePrivate(id: Long): Flow<List<MessageTranslationEntity>>
    @Query("SELECT * FROM message_translations WHERE groupMessageId = :id AND privateMessageId IS NULL ORDER BY createdAt DESC")
    fun observeGroup(id: Long): Flow<List<MessageTranslationEntity>>
    @Query("SELECT * FROM message_translations WHERE privateMessageId = :id AND groupMessageId IS NULL AND language = :language")
    suspend fun privateResult(id: Long, language: String): MessageTranslationEntity?
    @Query("SELECT * FROM message_translations WHERE groupMessageId = :id AND privateMessageId IS NULL AND language = :language")
    suspend fun groupResult(id: Long, language: String): MessageTranslationEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: MessageTranslationEntity)
    @Query("DELETE FROM message_translations WHERE id NOT IN (SELECT id FROM message_translations ORDER BY createdAt DESC, id DESC LIMIT 500)")
    suspend fun prune()
    @Query("DELETE FROM message_translations") suspend fun clear()
}
