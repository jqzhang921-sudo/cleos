package com.cleo.cleos.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "world_articles", indices = [Index("companionId"), Index(value = ["companionId", "fingerprint"], unique = true), Index(value = ["id", "companionId"], unique = true)],
    foreignKeys = [ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE)])
data class WorldArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companionId: Long,
    val title: String,
    val source: String,
    val content: String,
    val fingerprint: String,
    val createdAt: Long,
    val readOffset: Int = 0,
    val readAt: Long? = null,
    @ColumnInfo(defaultValue = "'study'") val space: String = "study",
    @ColumnInfo(defaultValue = "''") val originUrl: String = "",
    @ColumnInfo(defaultValue = "''") val published: String = "",
    @ColumnInfo(defaultValue = "''") val searchQuery: String = "",
)

@Serializable
@Entity(tableName = "world_notes", indices = [Index("companionId"), Index("articleId"), Index(value = ["runId"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WorldArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
    ])
data class WorldNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companionId: Long,
    val articleId: Long,
    val runId: String,
    val content: String,
    val question: String,
    val quote: String,
    val quoteStart: Int?,
    val fromOffset: Int,
    val toOffset: Int,
    val createdAt: Long,
)

/** Local execution lease; deliberately not restored as an active task from a backup. */
@Entity(tableName = "world_reading_state", foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
])
data class WorldReadingStateEntity(
    @PrimaryKey val companionId: Long,
    val day: Long,
    val attempts: Int,
    val token: String? = null,
    val status: String = "",
    @ColumnInfo(defaultValue = "''") val failureCode: String = "",
    @ColumnInfo(defaultValue = "'study'") val space: String = "study",
    val pictureArticleId: Long? = null,
    val albumPhotoId: Long? = null,
)

/** Device-local opt-in and one future opportunity; never exported as an active permission. */
@Entity(tableName = "world_reading_schedules", foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
])
data class WorldScheduleEntity(
    @PrimaryKey val companionId: Long,
    val companionCreatedAt: Long,
    val revision: String,
    val nextAt: Long,
)

/** Separate, device-local consent for one outside feed. Never exported as active permission. */
@Entity(tableName = "world_outside_schedules", foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
])
data class WorldOutsideScheduleEntity(
    @PrimaryKey val companionId: Long,
    val companionCreatedAt: Long,
    val sourceId: String,
    val revision: String,
    val nextAt: Long,
)

/** Explicit device consent to choose a public topic and send it to the encyclopedia. */
@Entity(tableName = "world_topic_schedules", foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
])
data class WorldTopicScheduleEntity(
    @PrimaryKey val companionId: Long,
    val companionCreatedAt: Long,
    val revision: String,
    val nextAt: Long,
    val lastQuery: String = "",
    val lastQueryAt: Long = 0,
)

/** Separate device-local consent to fetch and view one public encyclopedia picture. Never exported. */
@Entity(tableName = "world_picture_schedules", foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
])
data class WorldPictureScheduleEntity(@PrimaryKey val companionId: Long, val companionCreatedAt: Long,
    val revision: String, val contextHash: String, val nextAt: Long)

/** A public reading wish, never a record of having read a book. Ignored wishes remain for deduplication. */
@Serializable
@Entity(tableName = "world_wishes", indices = [Index("companionId"), Index("articleId"),
    Index(value = ["companionId", "identity"], unique = true), Index(value = ["runId"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WorldArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.SET_NULL),
    ])
data class WorldWishEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companionId: Long,
    val title: String,
    val author: String,
    val reason: String,
    val identity: String,
    val runId: String,
    val createdAt: Long,
    val status: String = "pending",
    val handledAt: Long? = null,
    val articleId: Long? = null,
    val catalogKey: String = "",
    val catalogTitle: String = "",
    val catalogAuthor: String = "",
    val verifiedAt: Long? = null,
)

@Dao
interface WorldDao {
    @Query("SELECT * FROM world_journey_topics WHERE companionId = :ta ORDER BY updatedAt DESC, id DESC") fun observeJourneyTopics(ta: Long): Flow<List<WorldJourneyTopicEntity>>
    @Query("SELECT * FROM world_journey_topics") suspend fun allJourneyTopics(): List<WorldJourneyTopicEntity>
    @Query("SELECT * FROM world_journey_topics WHERE id = :id") suspend fun journeyTopic(id: Long): WorldJourneyTopicEntity?
    @Query("SELECT * FROM world_journey_items WHERE companionId = :ta ORDER BY topicId, position") fun observeJourneyItems(ta: Long): Flow<List<WorldJourneyItemEntity>>
    @Query("SELECT * FROM world_journey_items ORDER BY topicId, position") suspend fun allJourneyItems(): List<WorldJourneyItemEntity>
    @Query("SELECT * FROM world_journey_items WHERE topicId = :id ORDER BY position") suspend fun journeyItems(id: Long): List<WorldJourneyItemEntity>
    @Insert suspend fun add(topic: WorldJourneyTopicEntity): Long
    @Update suspend fun update(topic: WorldJourneyTopicEntity)
    @Insert suspend fun restoreJourneyTopics(rows: List<WorldJourneyTopicEntity>)
    @Insert suspend fun restoreJourneyItems(rows: List<WorldJourneyItemEntity>)
    @Query("DELETE FROM world_journey_items WHERE topicId = :id AND companionId = :ta") suspend fun clearJourneyItems(id: Long, ta: Long)
    @Query("DELETE FROM world_journey_topics WHERE id = :id AND companionId = :ta") suspend fun deleteJourneyTopic(id: Long, ta: Long)

    @Query("SELECT * FROM world_writings WHERE companionId = :ta ORDER BY createdAt DESC, id DESC") fun observeWritings(ta: Long): Flow<List<WorldWritingEntity>>
    @Query("SELECT * FROM world_writings WHERE companionId = :ta ORDER BY createdAt DESC, id DESC") suspend fun writings(ta: Long): List<WorldWritingEntity>
    @Query("SELECT * FROM world_writings") suspend fun allWritings(): List<WorldWritingEntity>
    @Query("SELECT * FROM world_writings WHERE id = :id") suspend fun writing(id: Long): WorldWritingEntity?
    @Insert suspend fun add(writing: WorldWritingEntity): Long
    @Insert suspend fun restoreWritings(items: List<WorldWritingEntity>)
    @Query("DELETE FROM world_writings WHERE id = :id AND companionId = :ta") suspend fun deleteWriting(id: Long, ta: Long)
    @Query("DELETE FROM world_writings") suspend fun clearWritings()
    @Query("SELECT * FROM world_writing_schedules WHERE companionId = :ta") fun observeWritingSchedule(ta: Long): Flow<WorldWritingScheduleEntity?>
    @Query("SELECT * FROM world_writing_schedules WHERE companionId = :ta") suspend fun writingSchedule(ta: Long): WorldWritingScheduleEntity?
    @Query("SELECT * FROM world_writing_schedules") suspend fun writingSchedules(): List<WorldWritingScheduleEntity>
    @Upsert suspend fun put(schedule: WorldWritingScheduleEntity)
    @Query("DELETE FROM world_writing_schedules WHERE companionId = :ta") suspend fun deleteWritingSchedule(ta: Long)
    @Query("DELETE FROM world_writing_schedules") suspend fun clearWritingSchedules()
    /** Ownership and public scope are enforced before LIMIT, never by a model-supplied TA id. */
    @Query("""SELECT n.* FROM world_notes n JOIN world_articles a ON a.id = n.articleId AND a.companionId = n.companionId
        WHERE n.companionId = :ta AND a.space IN ('study', 'outside') AND (:publicOnly = 0 OR a.space = 'outside')
        AND (:query = '' OR instr(lower(a.title), lower(:query)) > 0 OR instr(lower(n.content), lower(:query)) > 0
            OR instr(lower(n.question), lower(:query)) > 0 OR instr(lower(n.quote), lower(:query)) > 0)
        ORDER BY n.createdAt DESC, n.id DESC LIMIT :limit""")
    suspend fun recallNotes(ta: Long, publicOnly: Boolean, query: String, limit: Int): List<WorldNoteEntity>

    @Query("""SELECT l.* FROM world_picture_looks l
        JOIN world_articles a ON a.id = l.articleId AND a.companionId = l.companionId
        JOIN world_pictures p ON p.articleId = a.id AND p.companionId = l.companionId
            AND p.fingerprint = a.fingerprint AND p.sha256 = l.pictureSha256
        WHERE l.companionId = :ta AND a.space = 'outside'
        AND (:query = '' OR instr(lower(a.title), lower(:query)) > 0 OR instr(lower(l.content), lower(:query)) > 0
            OR instr(lower(l.question), lower(:query)) > 0)
        ORDER BY l.createdAt DESC, l.id DESC LIMIT :limit""")
    suspend fun recallLooks(ta: Long, query: String, limit: Int): List<WorldPictureLookEntity>

    @Query("""SELECT * FROM world_writings WHERE companionId = :ta
        AND (:query = '' OR instr(lower(title), lower(:query)) > 0 OR instr(lower(content), lower(:query)) > 0)
        ORDER BY createdAt DESC, id DESC LIMIT :limit""")
    suspend fun recallWritings(ta: Long, query: String, limit: Int): List<WorldWritingEntity>

    @Query("SELECT * FROM world_picture_schedules WHERE companionId = :ta") fun observePictureSchedule(ta: Long): Flow<WorldPictureScheduleEntity?>
    @Query("SELECT * FROM world_picture_schedules WHERE companionId = :ta") suspend fun pictureSchedule(ta: Long): WorldPictureScheduleEntity?
    @Query("SELECT * FROM world_picture_schedules") suspend fun pictureSchedules(): List<WorldPictureScheduleEntity>
    @Upsert suspend fun put(schedule: WorldPictureScheduleEntity)
    @Query("DELETE FROM world_picture_schedules WHERE companionId = :ta") suspend fun deletePictureSchedule(ta: Long)
    @Query("DELETE FROM world_picture_schedules") suspend fun clearPictureSchedules()

    @Query("SELECT * FROM world_picture_looks WHERE companionId = :ta ORDER BY createdAt DESC, id DESC") fun observePictureLooks(ta: Long): Flow<List<WorldPictureLookEntity>>
    @Query("SELECT * FROM world_picture_looks WHERE companionId = :ta ORDER BY createdAt DESC, id DESC") suspend fun pictureLooks(ta: Long): List<WorldPictureLookEntity>
    @Query("SELECT * FROM world_picture_looks") suspend fun allPictureLooks(): List<WorldPictureLookEntity>
    @Query("SELECT * FROM world_picture_looks WHERE id = :id") suspend fun pictureLook(id: Long): WorldPictureLookEntity?
    @Query("SELECT (SELECT COUNT(*) FROM world_writings WHERE companionId = :ta) + (SELECT COUNT(*) FROM world_notes WHERE companionId = :ta) + (SELECT COUNT(*) FROM world_picture_looks WHERE companionId = :ta) + (SELECT COUNT(*) FROM world_album_looks WHERE companionId = :ta)") suspend fun recordCount(ta: Long): Int
    @Insert suspend fun add(look: WorldPictureLookEntity): Long
    @Insert suspend fun restorePictureLooks(items: List<WorldPictureLookEntity>)
    @Query("DELETE FROM world_picture_looks WHERE id = :id AND companionId = :ta") suspend fun deletePictureLook(id: Long, ta: Long)
    @Query("DELETE FROM world_picture_looks") suspend fun clearPictureLooks()
    @Query("SELECT * FROM world_pictures WHERE companionId = :ta") fun observePictures(ta: Long): Flow<List<WorldPictureEntity>>
    @Query("SELECT * FROM world_pictures WHERE articleId = :article") fun observePicture(article: Long): Flow<WorldPictureEntity?>
    @Query("SELECT * FROM world_pictures WHERE articleId = :article") suspend fun picture(article: Long): WorldPictureEntity?
    @Query("SELECT * FROM world_pictures WHERE companionId = :ta") suspend fun pictures(ta: Long): List<WorldPictureEntity>
    @Query("SELECT * FROM world_pictures") suspend fun allPictures(): List<WorldPictureEntity>
    @Insert suspend fun add(picture: WorldPictureEntity)
    @Insert suspend fun restorePictures(items: List<WorldPictureEntity>)
    @Query("DELETE FROM world_pictures WHERE articleId = :article AND companionId = :ta") suspend fun deletePicture(article: Long, ta: Long)
    @Query("DELETE FROM world_pictures") suspend fun clearPictures()
    @Query("SELECT * FROM world_bookmarks WHERE companionId = :ta ORDER BY createdAt DESC, id DESC") fun observeBookmarks(ta: Long): Flow<List<WorldBookmarkEntity>>
    @Query("SELECT * FROM world_bookmarks WHERE companionId = :ta ORDER BY createdAt DESC, id DESC") suspend fun bookmarks(ta: Long): List<WorldBookmarkEntity>
    @Query("SELECT * FROM world_bookmarks") suspend fun allBookmarks(): List<WorldBookmarkEntity>
    @Insert suspend fun add(bookmark: WorldBookmarkEntity): Long
    @Update suspend fun update(bookmark: WorldBookmarkEntity)
    @Query("SELECT * FROM world_bookmarks WHERE id = :id") suspend fun bookmark(id: Long): WorldBookmarkEntity?
    @Insert suspend fun restoreBookmarks(items: List<WorldBookmarkEntity>)
    @Query("DELETE FROM world_bookmarks WHERE id = :id AND companionId = :ta") suspend fun deleteBookmark(id: Long, ta: Long)
    @Query("DELETE FROM world_bookmarks") suspend fun clearBookmarks()
    @Query("SELECT * FROM world_shelf WHERE companionId = :ta") fun observeShelf(ta: Long): Flow<List<WorldShelfEntity>>
    @Query("SELECT * FROM world_shelf WHERE articleId = :article") suspend fun shelf(article: Long): WorldShelfEntity?
    @Query("SELECT * FROM world_shelf") suspend fun allShelf(): List<WorldShelfEntity>
    @Upsert suspend fun put(entry: WorldShelfEntity)
    @Insert suspend fun restoreShelf(items: List<WorldShelfEntity>)
    @Query("DELETE FROM world_shelf") suspend fun clearShelf()
    @Query("SELECT * FROM world_topic_schedules WHERE companionId = :ta")
    fun observeTopicSchedule(ta: Long): Flow<WorldTopicScheduleEntity?>
    @Query("SELECT * FROM world_topic_schedules WHERE companionId = :ta")
    suspend fun topicSchedule(ta: Long): WorldTopicScheduleEntity?
    @Query("SELECT * FROM world_topic_schedules") suspend fun topicSchedules(): List<WorldTopicScheduleEntity>
    @Upsert suspend fun put(schedule: WorldTopicScheduleEntity)
    @Query("DELETE FROM world_topic_schedules WHERE companionId = :ta") suspend fun deleteTopicSchedule(ta: Long)
    @Query("DELETE FROM world_topic_schedules") suspend fun clearTopicSchedules()
    @Query("SELECT * FROM world_outside_schedules WHERE companionId = :ta")
    fun observeOutsideSchedule(ta: Long): Flow<WorldOutsideScheduleEntity?>
    @Query("SELECT * FROM world_outside_schedules WHERE companionId = :ta")
    suspend fun outsideSchedule(ta: Long): WorldOutsideScheduleEntity?
    @Query("SELECT * FROM world_outside_schedules") suspend fun outsideSchedules(): List<WorldOutsideScheduleEntity>
    @Upsert suspend fun put(schedule: WorldOutsideScheduleEntity)
    @Query("DELETE FROM world_outside_schedules WHERE companionId = :ta") suspend fun deleteOutsideSchedule(ta: Long)
    @Query("DELETE FROM world_outside_schedules") suspend fun clearOutsideSchedules()
    @Query("SELECT * FROM world_wishes WHERE companionId = :ta ORDER BY createdAt DESC, id DESC")
    fun observeWishes(ta: Long): Flow<List<WorldWishEntity>>
    @Query("SELECT * FROM world_wishes WHERE companionId = :ta ORDER BY createdAt DESC, id DESC")
    suspend fun wishes(ta: Long): List<WorldWishEntity>
    @Query("SELECT * FROM world_wishes") suspend fun allWishes(): List<WorldWishEntity>
    @Query("SELECT * FROM world_wishes WHERE id = :id") suspend fun wish(id: Long): WorldWishEntity?
    @Insert suspend fun add(wish: WorldWishEntity): Long
    @Insert suspend fun restoreWishes(items: List<WorldWishEntity>)
    @Update suspend fun update(wish: WorldWishEntity)
    @Query("DELETE FROM world_wishes") suspend fun clearWishes()
    @Query("SELECT * FROM world_reading_schedules WHERE companionId = :ta")
    fun observeSchedule(ta: Long): Flow<WorldScheduleEntity?>
    @Query("SELECT * FROM world_reading_schedules WHERE companionId = :ta")
    suspend fun schedule(ta: Long): WorldScheduleEntity?
    @Query("SELECT * FROM world_reading_schedules") suspend fun schedules(): List<WorldScheduleEntity>
    @Upsert suspend fun put(schedule: WorldScheduleEntity)
    @Query("DELETE FROM world_reading_schedules WHERE companionId = :ta") suspend fun deleteSchedule(ta: Long)
    @Query("DELETE FROM world_reading_schedules") suspend fun clearSchedules()
    @Query("SELECT * FROM world_articles WHERE companionId = :ta ORDER BY createdAt DESC, id DESC")
    fun observeArticles(ta: Long): Flow<List<WorldArticleEntity>>
    @Query("SELECT * FROM world_notes WHERE companionId = :ta ORDER BY createdAt DESC, id DESC")
    fun observeNotes(ta: Long): Flow<List<WorldNoteEntity>>
    @Query("SELECT * FROM world_reading_state WHERE companionId = :ta")
    fun observeState(ta: Long): Flow<WorldReadingStateEntity?>
    @Query("SELECT * FROM world_articles WHERE companionId = :ta ORDER BY createdAt DESC, id DESC")
    suspend fun articles(ta: Long): List<WorldArticleEntity>
    @Query("SELECT * FROM world_articles") suspend fun allArticles(): List<WorldArticleEntity>
    @Query("SELECT * FROM world_notes") suspend fun allNotes(): List<WorldNoteEntity>
    @Query("SELECT * FROM world_notes WHERE companionId = :ta") suspend fun notes(ta: Long): List<WorldNoteEntity>
    @Query("""SELECT n.* FROM world_notes n JOIN world_articles a ON a.id = n.articleId AND a.companionId = n.companionId
        WHERE n.companionId = :ta AND a.space = 'study' AND a.id IN (:articles)
        ORDER BY n.createdAt DESC, n.id DESC LIMIT 12""")
    suspend fun studyNotes(ta: Long, articles: List<Long>): List<WorldNoteEntity>
    @Query("SELECT COUNT(*) FROM world_notes WHERE companionId = :ta") suspend fun noteCount(ta: Long): Int
    @Query("SELECT * FROM world_articles WHERE id = :id") suspend fun article(id: Long): WorldArticleEntity?
    @Query("SELECT * FROM world_notes WHERE companionId = :ta ORDER BY createdAt DESC, id DESC LIMIT 2")
    suspend fun recentNotes(ta: Long): List<WorldNoteEntity>
    @Query("SELECT * FROM world_reading_state WHERE companionId = :ta") suspend fun state(ta: Long): WorldReadingStateEntity?
    @Insert suspend fun add(article: WorldArticleEntity): Long
    @Insert suspend fun add(note: WorldNoteEntity): Long
    @Insert suspend fun restoreArticles(items: List<WorldArticleEntity>)
    @Insert suspend fun restoreNotes(items: List<WorldNoteEntity>)
    @Update suspend fun update(article: WorldArticleEntity)
    @Upsert suspend fun put(state: WorldReadingStateEntity)
    @Query("UPDATE world_reading_state SET attempts = MAX(0, attempts - 1), token = NULL, status = CASE WHEN space = 'writing' THEN '上次写作中断，次数已退回，没有保存未完成内容' ELSE '上次阅读中断，次数已退回，进度未更新' END, failureCode = 'interrupted' WHERE token IS NOT NULL")
    suspend fun recover()
    @Query("DELETE FROM world_articles WHERE id = :id AND companionId = :ta") suspend fun deleteArticle(id: Long, ta: Long)
    @Query("DELETE FROM world_articles WHERE companionId = :ta AND space = 'outside' AND id IN (:ids)")
    suspend fun deleteOutside(ids: List<Long>, ta: Long): Int
    @Query("DELETE FROM world_notes WHERE id = :id AND companionId = :ta") suspend fun deleteNote(id: Long, ta: Long)
    @Query("DELETE FROM world_reading_state") suspend fun clearStates()
    @Query("DELETE FROM world_notes") suspend fun clearNotes()
    @Query("DELETE FROM world_articles") suspend fun clearArticles()
}

@Serializable
@Entity(tableName = "world_bookmarks", indices = [Index("companionId"), Index("articleId"), Index(value = ["articleId", "offset"], unique = true)], foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = WorldArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
])
data class WorldBookmarkEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val companionId: Long, val articleId: Long,
    val fingerprint: String, val offset: Int, val label: String = "", val createdAt: Long)

@Serializable
@Entity(tableName = "world_shelf", indices = [Index("companionId")], foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = WorldArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
])
data class WorldShelfEntity(@PrimaryKey val articleId: Long, val companionId: Long, val kind: String, val series: String = "", val issue: String = "")

/** Public related image for the user to view; this is not proof that the TA saw its pixels. */
@Serializable
@Entity(tableName = "world_pictures", indices = [Index("companionId")], foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = WorldArticleEntity::class, parentColumns = ["id"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
])
data class WorldPictureEntity(@PrimaryKey val articleId: Long, val companionId: Long, val fingerprint: String,
    val file: String, val width: Int, val height: Int, val title: String, val author: String, val credit: String,
    val description: String, val license: String, val licenseUrl: String, val sourceUrl: String, val imageUrl: String, val fetchedAt: Long, val sha256: String)

/** Explicit acknowledgement after the model received these pixels. Empty public notes are allowed. */
@Serializable
@Entity(tableName = "world_picture_looks", indices = [Index("companionId"), Index("articleId"), Index(value = ["runId"], unique = true)], foreignKeys = [
    ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = WorldPictureEntity::class, parentColumns = ["articleId"], childColumns = ["articleId"], onDelete = ForeignKey.CASCADE),
])
data class WorldPictureLookEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val companionId: Long, val articleId: Long,
    val pictureSha256: String, val runId: String, val content: String, val question: String, val createdAt: Long)
