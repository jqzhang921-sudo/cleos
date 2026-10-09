package com.cleo.cleos.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "feed_posts")
data class FeedPostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val authorId: Long = 0,
    val content: String,
    val createdAt: Long,
    val liked: Boolean = false,
    val comments: String? = null,
    val sourceUrl: String? = null,
    val sourceTitle: String? = null,
    @ColumnInfo(defaultValue = "'moments'") val kind: String = "moments",
    /** Imported photos owned by this post, encoded as MessageImage list. */
    val images: String? = null,
    /** TA likes and reviewed reply targets; independent of the user's liked flag. */
    val interactions: String? = null,
)

/** Older sourced posts belong to topics even before the explicit category existed. */
val FeedPostEntity.isTopic: Boolean get() = kind == "topic" || sourceUrl != null

@Dao
interface FeedDao {
    @Query("SELECT * FROM feed_posts ORDER BY createdAt DESC, id DESC") fun observe(): Flow<List<FeedPostEntity>>
    @Query("SELECT * FROM feed_posts ORDER BY createdAt DESC, id DESC") suspend fun all(): List<FeedPostEntity>
    @Query("SELECT * FROM feed_posts WHERE id = :id") suspend fun get(id: Long): FeedPostEntity?
    @Query("""SELECT * FROM feed_posts
        WHERE (:authorId IS NULL OR authorId = :authorId)
        AND (:kind = 'all' OR (:kind = 'topic' AND (kind = 'topic' OR sourceUrl IS NOT NULL))
             OR (:kind = 'moments' AND kind != 'topic' AND sourceUrl IS NULL))
        AND (:query = '' OR instr(lower(content), lower(:query)) > 0 OR instr(lower(coalesce(sourceTitle, '')), lower(:query)) > 0)
        ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset""")
    suspend fun search(authorId: Long?, kind: String, query: String, limit: Int, offset: Int): List<FeedPostEntity>
    @Insert suspend fun insert(post: FeedPostEntity): Long
    @Insert suspend fun insertAll(posts: List<FeedPostEntity>)
    @Update suspend fun update(post: FeedPostEntity)
    @Query("DELETE FROM feed_posts WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM feed_posts") suspend fun clear()
}
