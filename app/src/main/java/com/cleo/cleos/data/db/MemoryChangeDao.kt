package com.cleo.cleos.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Private revision history, never model context. Forgetting purges connected revisions. */
@Serializable
@Entity(tableName="memory_changes",indices=[Index("companionId")],foreignKeys=[ForeignKey(
    entity=CompanionEntity::class,parentColumns=["id"],childColumns=["companionId"],onDelete=ForeignKey.CASCADE)])
data class MemoryChangeEntity(
    @PrimaryKey(autoGenerate=true) val id:Long=0,
    val companionId:Long,
    val action:String,
    val actor:String,
    val state:String,
    val reason:String="",
    val beforeRows:String="[]",
    val afterRows:String="[]",
    val createdAt:Long,
)

/** Only digests are retained after forgetting. These block exact normalized facts, not paraphrases. */
@Serializable
@Entity(tableName="memory_blocks",primaryKeys=["companionId","factHash"],foreignKeys=[ForeignKey(
    entity=CompanionEntity::class,parentColumns=["id"],childColumns=["companionId"],onDelete=ForeignKey.CASCADE)])
data class MemoryBlockEntity(val companionId:Long,val factHash:String,val createdAt:Long)

@Dao
interface MemoryChangeDao {
    @Query("SELECT * FROM memory_changes WHERE companionId=:owner ORDER BY id DESC") fun observe(owner:Long):Flow<List<MemoryChangeEntity>>
    @Query("SELECT * FROM memory_changes WHERE companionId=:owner ORDER BY id DESC") suspend fun forOwner(owner:Long):List<MemoryChangeEntity>
    @Query("SELECT * FROM memory_changes WHERE id=:id AND companionId=:owner") suspend fun get(owner:Long,id:Long):MemoryChangeEntity?
    @Insert suspend fun insert(row:MemoryChangeEntity):Long
    @Update suspend fun update(row:MemoryChangeEntity)
    @Query("DELETE FROM memory_changes WHERE companionId=:owner AND id IN (:ids)") suspend fun remove(owner:Long,ids:List<Long>)
    @Query("SELECT * FROM memory_changes") suspend fun all():List<MemoryChangeEntity>
    @Insert suspend fun restore(rows:List<MemoryChangeEntity>)
    @Query("DELETE FROM memory_changes") suspend fun clear()
    @Query("SELECT * FROM memory_blocks WHERE companionId=:owner") suspend fun blocks(owner:Long):List<MemoryBlockEntity>
    @Query("SELECT * FROM memory_blocks") suspend fun allBlocks():List<MemoryBlockEntity>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun block(rows:List<MemoryBlockEntity>)
    @Query("DELETE FROM memory_blocks WHERE companionId=:owner AND factHash IN (:hashes)") suspend fun unblock(owner:Long,hashes:List<String>)
    @Query("DELETE FROM memory_blocks") suspend fun clearBlocks()
}
