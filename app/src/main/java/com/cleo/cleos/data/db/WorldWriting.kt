package com.cleo.cleos.data.db

import androidx.room.*
import kotlinx.serialization.Serializable

/** Displayable creative text. No fabricated article/reading relation, private thought or chat message. */
@Serializable
@Entity(tableName = "world_writings", indices = [Index("companionId"), Index(value = ["runId"], unique = true)],
    foreignKeys = [ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE)])
data class WorldWritingEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val companionId: Long,
    val runId: String, val kind: String, val title: String, val content: String, val createdAt: Long)

/** Device-local, default absent; never exported as an active permission. */
@Entity(tableName = "world_writing_schedules", foreignKeys = [ForeignKey(entity = CompanionEntity::class,
    parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE)])
data class WorldWritingScheduleEntity(@PrimaryKey val companionId: Long, val companionCreatedAt: Long,
    val revision: String, val contextHash: String, val nextAt: Long)
