package com.cleo.cleos.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Short-lived invitations and a durable rate-limit ledger. Never restored from a backup. */
@Entity(tableName = "incoming_calls", indices = [Index("companionId"), Index("conversationId"), Index("recordId")],
    foreignKeys = [
        ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["recordId"], onDelete = ForeignKey.SET_NULL),
    ])
data class IncomingCallEntity(
    @PrimaryKey val id: String,
    val companionId: Long,
    val companionCreatedAt: Long,
    val conversationId: Long,
    val conversationCreatedAt: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val reason: String,
    val recordId: Long?,
    val status: String = "ringing",
)

@Dao
interface IncomingCallDao {
    @Query("SELECT * FROM incoming_calls WHERE status IN ('ringing','connecting') ORDER BY createdAt DESC")
    fun observeOpen(): Flow<List<IncomingCallEntity>>
    @Query("SELECT * FROM incoming_calls WHERE status IN ('ringing','connecting') ORDER BY createdAt DESC")
    suspend fun open(): List<IncomingCallEntity>
    @Query("SELECT * FROM incoming_calls WHERE id = :id")
    suspend fun get(id: String): IncomingCallEntity?
    @Query("SELECT * FROM incoming_calls WHERE companionId = :ta AND createdAt >= :since ORDER BY createdAt DESC")
    suspend fun recent(ta: Long, since: Long): List<IncomingCallEntity>
    @Query("SELECT COUNT(*) FROM incoming_calls WHERE recordId = :record AND status IN ('ringing','connecting') AND expiresAt > :now")
    suspend fun pendingRecord(record: Long, now: Long): Int
    @Insert suspend fun insert(row: IncomingCallEntity)
    @Query("UPDATE incoming_calls SET status = :status WHERE id = :id AND status = :expected")
    suspend fun transition(id: String, expected: String, status: String): Int
    @Query("UPDATE incoming_calls SET status = 'answered' WHERE recordId = :record AND status = 'connecting'")
    suspend fun answered(record: Long)
    @Query("DELETE FROM incoming_calls WHERE createdAt < :before AND status NOT IN ('ringing','connecting')")
    suspend fun prune(before: Long)
    @Query("DELETE FROM incoming_calls") suspend fun clear()
}
