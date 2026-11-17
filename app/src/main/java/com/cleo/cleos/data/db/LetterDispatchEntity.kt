package com.cleo.cleos.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/** Body-free delivery receipts survive letter/chat deletion, preserving deduplication and quotas. */
@Serializable
@Entity(tableName = "letter_dispatches", indices = [Index("companionId")], foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
])
data class LetterDispatchEntity(
    @PrimaryKey val origin: String,
    val companionId: Long,
    val letterId: Long,
    val createdAt: Long,
    val requestMessageId: Long? = null,
)

@Dao
interface LetterDispatchDao {
    @Query("SELECT * FROM letter_dispatches WHERE origin = :origin") suspend fun get(origin: String): LetterDispatchEntity?
    @Query("SELECT * FROM letter_dispatches WHERE companionId = :owner") suspend fun forOwner(owner: Long): List<LetterDispatchEntity>
    @Query("SELECT * FROM letter_dispatches") suspend fun all(): List<LetterDispatchEntity>
    @Insert suspend fun insert(item: LetterDispatchEntity)
    @Insert suspend fun insertAll(items: List<LetterDispatchEntity>)
    @Query("DELETE FROM letter_dispatches") suspend fun clear()
}
