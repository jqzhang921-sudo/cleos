package com.cleo.cleos.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Source IDs are intentionally not foreign keys: snapshots survive source deletion. */
@Serializable
@Entity(tableName = "favorites", indices = [Index("companionId")])
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companionId: Long,
    val conversationId: Long,
    val sourceKey: String,
    val parts: String,
    val createdAt: Long,
    val note: String = "",
    val removedAt: Long? = null,
)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE companionId = :companionId AND removedAt IS NULL ORDER BY createdAt DESC, id DESC")
    fun observeFor(companionId: Long): Flow<List<FavoriteEntity>>
    @Query("SELECT * FROM favorites WHERE id = :id AND removedAt IS NULL")
    fun observe(id: Long): Flow<FavoriteEntity?>
    @Query("SELECT * FROM favorites WHERE removedAt IS NULL")
    suspend fun all(): List<FavoriteEntity>
    @Query("SELECT * FROM favorites WHERE conversationId = :conversationId AND sourceKey = :key AND removedAt IS NULL LIMIT 1")
    suspend fun find(conversationId: Long, key: String): FavoriteEntity?
    @Insert
    suspend fun insert(entry: FavoriteEntity): Long
    @Insert
    suspend fun insertAll(entries: List<FavoriteEntity>)
    @Query("UPDATE favorites SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String)
    @Query("UPDATE favorites SET removedAt = :at WHERE id = :id")
    suspend fun setRemoved(id: Long, at: Long?)
    @Query("SELECT * FROM favorites WHERE removedAt < :before")
    suspend fun removedBefore(before: Long): List<FavoriteEntity>
    @Query("SELECT * FROM favorites WHERE companionId = :id")
    suspend fun forCompanion(id: Long): List<FavoriteEntity>
    @Query("DELETE FROM favorites WHERE companionId = :id")
    suspend fun deleteFor(id: Long)
    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun delete(id: Long)
    @Query("DELETE FROM favorites")
    suspend fun clear()
}
