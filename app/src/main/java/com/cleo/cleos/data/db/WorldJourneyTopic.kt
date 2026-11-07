package com.cleo.cleos.data.db

import androidx.room.*
import kotlinx.serialization.Serializable

/** A saved public collection. It never copies articles or grants model/network permissions. */
@Serializable
@Entity(tableName = "world_journey_topics", indices = [Index("companionId"), Index(value = ["id", "companionId"], unique = true)],
    foreignKeys = [ForeignKey(entity = CompanionEntity::class, parentColumns = ["id"], childColumns = ["companionId"], onDelete = ForeignKey.CASCADE)])
data class WorldJourneyTopicEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val companionId: Long,
    val title: String, val kind: String, val createdAt: Long, val updatedAt: Long)

@Serializable
@Entity(tableName = "world_journey_items", primaryKeys = ["topicId", "articleId"],
    indices = [Index(value = ["articleId", "companionId"]), Index(value = ["topicId", "companionId"]), Index("companionId"), Index(value = ["topicId", "position"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = WorldJourneyTopicEntity::class, parentColumns = ["id", "companionId"], childColumns = ["topicId", "companionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WorldArticleEntity::class, parentColumns = ["id", "companionId"], childColumns = ["articleId", "companionId"], onDelete = ForeignKey.CASCADE),
    ])
data class WorldJourneyItemEntity(val topicId: Long, val articleId: Long, val companionId: Long, val position: Int)
