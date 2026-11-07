package com.cleo.cleos.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Explicitly selected private copies. No URI, location metadata or background permission. */
@Serializable
@Entity(tableName = "world_album_photos", indices = [Index("companionId"), Index(value = ["file"], unique = true)], foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
])
data class WorldAlbumPhotoEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val companionId: Long,
    val file: String, val width: Int, val height: Int, val sha256: String, val caption: String = "", val createdAt: Long)

@Serializable
@Entity(tableName = "world_album_looks", indices = [Index("companionId"), Index("photoId"), Index(value = ["runId"], unique = true)], foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = WorldAlbumPhotoEntity::class, parentColumns = ["id"], childColumns = ["photoId"], onDelete = ForeignKey.CASCADE),
])
data class WorldAlbumLookEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val companionId: Long, val photoId: Long,
    val photoSha256: String, val runId: String, val content: String, val question: String, val createdAt: Long)

/** Device-local opt-in, bound to the exact role/model/key revision. Never exported. */
@Entity(tableName = "world_album_schedules", foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
])
data class WorldAlbumScheduleEntity(@PrimaryKey val companionId: Long, val companionCreatedAt: Long,
    val revision: String, val contextHash: String, val nextAt: Long)

@Dao
interface WorldAlbumDao {
    @Query("SELECT * FROM world_album_schedules WHERE companionId = :ta") fun observeSchedule(ta: Long): Flow<WorldAlbumScheduleEntity?>
    @Query("SELECT * FROM world_album_schedules WHERE companionId = :ta") suspend fun schedule(ta: Long): WorldAlbumScheduleEntity?
    @Query("SELECT * FROM world_album_schedules") suspend fun schedules(): List<WorldAlbumScheduleEntity>
    @Upsert suspend fun put(schedule: WorldAlbumScheduleEntity)
    @Query("DELETE FROM world_album_schedules WHERE companionId = :ta") suspend fun deleteSchedule(ta: Long)
    @Query("DELETE FROM world_album_schedules") suspend fun clearSchedules()
    @Query("SELECT * FROM world_album_photos WHERE companionId = :ta ORDER BY createdAt DESC, id DESC") fun observePhotos(ta: Long): Flow<List<WorldAlbumPhotoEntity>>
    @Query("SELECT * FROM world_album_photos WHERE companionId = :ta") suspend fun photos(ta: Long): List<WorldAlbumPhotoEntity>
    @Query("SELECT * FROM world_album_photos") suspend fun allPhotos(): List<WorldAlbumPhotoEntity>
    @Query("SELECT * FROM world_album_photos WHERE id = :id") suspend fun photo(id: Long): WorldAlbumPhotoEntity?
    @Insert suspend fun add(photo: WorldAlbumPhotoEntity): Long
    @Update suspend fun update(photo: WorldAlbumPhotoEntity)
    @Query("DELETE FROM world_album_photos WHERE id = :id AND companionId = :ta") suspend fun deletePhoto(id: Long, ta: Long)
    @Insert suspend fun restorePhotos(items: List<WorldAlbumPhotoEntity>)
    @Query("DELETE FROM world_album_photos") suspend fun clearPhotos()
    @Query("SELECT * FROM world_album_looks WHERE companionId = :ta ORDER BY createdAt DESC, id DESC") fun observeLooks(ta: Long): Flow<List<WorldAlbumLookEntity>>
    @Query("SELECT * FROM world_album_looks") suspend fun allLooks(): List<WorldAlbumLookEntity>
    @Query("SELECT * FROM world_album_looks WHERE companionId = :ta ORDER BY createdAt DESC, id DESC") suspend fun looks(ta: Long): List<WorldAlbumLookEntity>
    @Query("SELECT * FROM world_album_looks WHERE id = :id") suspend fun look(id: Long): WorldAlbumLookEntity?
    @Insert suspend fun add(look: WorldAlbumLookEntity): Long
    @Query("DELETE FROM world_album_looks WHERE id = :id AND companionId = :ta") suspend fun deleteLook(id: Long, ta: Long)
    @Insert suspend fun restoreLooks(items: List<WorldAlbumLookEntity>)
    @Query("DELETE FROM world_album_looks") suspend fun clearLooks()
}
