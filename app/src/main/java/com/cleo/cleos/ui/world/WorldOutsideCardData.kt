package com.cleo.cleos.ui.world

import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.db.WorldArticleEntity
import com.cleo.cleos.data.db.WorldNoteEntity
import java.time.*
import java.time.format.DateTimeFormatter

/** A view of saved evidence only. Display formatting never changes source text or reading offsets. */
internal data class WorldOutsideCardData(val preview: String, val reading: Reading, val noteCount: Int) {
    enum class Reading { Empty, Unread, Partial, Complete, Unconfirmed }

    companion object {
        fun from(owner: Long?, article: WorldArticleEntity, notes: List<WorldNoteEntity>): WorldOutsideCardData? {
            if (owner == null || article.companionId != owner || article.space != "outside") return null
            val status = when {
                article.content.isBlank() -> Reading.Empty
                article.readOffset !in 0..article.content.length || !WorldReading.boundary(article.content, article.readOffset) -> Reading.Unconfirmed
                article.readOffset == 0 -> Reading.Unread
                article.readOffset == article.content.length -> Reading.Complete
                else -> Reading.Partial
            }
            return WorldOutsideCardData(WorldTextLayout.compact(article.content), status,
                notes.count { it.companionId == owner && it.articleId == article.id &&
                    (it.content.isNotBlank() || it.question.isNotBlank() || it.quote.isNotBlank()) })
        }

        /** A source date stays a source date; unknown formats remain visible rather than being guessed. */
        fun published(raw: String): String {
            val value = raw.trim()
            if (value.isEmpty()) return "未提供"
            val date = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(value) }.getOrNull()
            return date?.let { it.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")) + " UTC" + if (it.offset == ZoneOffset.UTC) "" else it.offset.id }
                ?: runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("yyyy年M月d日")) }.getOrDefault(value)
        }
    }
}
