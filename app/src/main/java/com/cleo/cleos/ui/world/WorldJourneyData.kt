package com.cleo.cleos.ui.world

import com.cleo.cleos.data.WorldPictureData
import com.cleo.cleos.data.db.*

internal data class WorldJourneyThought(val kind: String, val content: String, val question: String, val at: Long)
internal data class WorldJourneyChapter(val article: WorldArticleEntity, val image: WorldPictureEntity?,
    val paragraphs: List<String>, val thoughts: List<WorldJourneyThought>)

/** A read-only projection of one TA's public records; never assembles an invented itinerary. */
internal object WorldJourneyData {
    const val LIMIT = 24
    fun chapters(ta: Long, articles: List<WorldArticleEntity>, pictures: List<WorldPictureEntity>,
        notes: List<WorldNoteEntity>, looks: List<WorldPictureLookEntity>, ordered: Boolean = false): List<WorldJourneyChapter> =
        articles.filter { it.id > 0 && it.companionId == ta && it.space == "outside" }
            .distinctBy { it.id }.let { rows -> if (ordered) rows else rows.sortedWith(compareByDescending<WorldArticleEntity> { it.createdAt }.thenByDescending { it.id }).take(LIMIT) }.map { article ->
                val image = pictures.firstOrNull { it.articleId == article.id && it.companionId == ta && it.fingerprint == article.fingerprint &&
                    runCatching { WorldPictureData.validate(listOf(article), listOf(it)) }.isSuccess }
                val thoughts = (notes.filter { it.companionId == ta && it.articleId == article.id }
                    .map { WorldJourneyThought("摘要笔记", it.content, it.question, it.createdAt) } +
                    looks.filter { image != null && it.companionId == ta && it.articleId == article.id && it.pictureSha256 == image.sha256 }
                        .map { WorldJourneyThought("曾留的看图感想", it.content, it.question, it.createdAt) })
                    .filter { it.content.isNotBlank() || it.question.isNotBlank() }.sortedByDescending { it.at }.take(2)
                WorldJourneyChapter(article, image, excerpt(article.content), thoughts)
            }
    fun excerpt(content: String): List<String> {
        var end = minOf(content.length, 800)
        if (end in 1 until content.length && content[end - 1].isHighSurrogate() && content[end].isLowSurrogate()) end--
        return content.substring(0, end).trim().split(Regex("\n\\s*\n"), limit = 3).take(2).filter(String::isNotBlank)
    }
}
