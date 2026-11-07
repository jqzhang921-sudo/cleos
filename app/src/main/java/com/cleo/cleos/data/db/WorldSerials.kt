package com.cleo.cleos.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** A user-arranged table of contents. Creative prose stays in world_writings. */
@Serializable
@Entity(tableName = "world_serials", indices = [Index("companionId")], foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
])
data class WorldSerialEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val companionId: Long,
    val title: String, val createdAt: Long, val updatedAt: Long)

@Serializable
@Entity(tableName = "world_serial_chapters", indices = [Index("serialId"), Index(value = ["serialId", "position"], unique = true)], foreignKeys = [
    ForeignKey(entity = WorldSerialEntity::class, parentColumns = ["id"], childColumns = ["serialId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = WorldWritingEntity::class, parentColumns = ["id"], childColumns = ["writingId"], onDelete = ForeignKey.CASCADE),
])
data class WorldSerialChapterEntity(@PrimaryKey val writingId: Long, val serialId: Long, val position: Int)

@Dao
interface WorldSerialDao {
    @Query("SELECT * FROM world_serials WHERE companionId = :owner ORDER BY updatedAt DESC, id DESC")
    fun observe(owner: Long): Flow<List<WorldSerialEntity>>
    @Query("SELECT c.* FROM world_serial_chapters c JOIN world_serials s ON s.id=c.serialId WHERE s.companionId=:owner ORDER BY c.serialId,c.position")
    fun observeChapters(owner: Long): Flow<List<WorldSerialChapterEntity>>
    @Query("SELECT * FROM world_serials") suspend fun all(): List<WorldSerialEntity>
    @Query("SELECT * FROM world_serial_chapters") suspend fun allChapters(): List<WorldSerialChapterEntity>
    @Query("SELECT * FROM world_serials WHERE id=:id") suspend fun get(id: Long): WorldSerialEntity?
    @Query("SELECT * FROM world_serial_chapters WHERE serialId=:id ORDER BY position") suspend fun chapters(id: Long): List<WorldSerialChapterEntity>
    @Query("SELECT * FROM world_serial_chapters WHERE writingId=:id") suspend fun membership(id: Long): WorldSerialChapterEntity?
    @Insert suspend fun add(row: WorldSerialEntity): Long
    @Update suspend fun update(row: WorldSerialEntity)
    @Insert suspend fun insertAll(rows: List<WorldSerialEntity>)
    @Insert suspend fun insertChapters(rows: List<WorldSerialChapterEntity>)
    @Query("DELETE FROM world_serial_chapters WHERE serialId=:id") suspend fun clearChapters(id: Long)
    @Query("DELETE FROM world_serials WHERE id=:id AND companionId=:owner") suspend fun delete(id: Long, owner: Long)
    @Query("DELETE FROM world_serial_chapters") suspend fun clearAllChapters()
    @Query("DELETE FROM world_serials") suspend fun clear()
}
