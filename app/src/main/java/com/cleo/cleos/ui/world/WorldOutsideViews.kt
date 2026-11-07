package com.cleo.cleos.ui.world

import com.cleo.cleos.data.WorldPictureData
import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.db.*

/** Read-only views of one TA's public records, not a new activity log or a second notebook. */
internal object WorldOutsideViews {
    const val TRAIL_LIMIT = 100
    data class Note(val key: String, val article: WorldArticleEntity, val at: Long, val kind: String,
        val content: String, val question: String, val quote: String = "", val historicalImage: Boolean = false)
    data class Step(val key: String, val article: WorldArticleEntity, val at: Long, val label: String, val detail: String)

    fun articles(owner: Long?, rows: List<WorldArticleEntity>, source: String = ""): List<WorldArticleEntity> =
        if (owner == null) emptyList() else rows.filter { it.id > 0 && it.companionId == owner && it.space == "outside" && (source.isEmpty() || it.source == source) }
            .distinctBy { it.id }.sortedWith(compareByDescending<WorldArticleEntity> { it.createdAt }.thenByDescending { it.id })

    private fun image(article: WorldArticleEntity, pictures: List<WorldPictureEntity>): WorldPictureEntity? =
        pictures.firstOrNull { it.articleId == article.id && it.companionId == article.companionId &&
            runCatching { WorldPictureData.validate(listOf(article), listOf(it)) }.isSuccess }

    private fun quote(article: WorldArticleEntity, note: WorldNoteEntity): String {
        val start = note.quoteStart ?: return ""
        return note.quote.takeIf { it.isNotBlank() && start >= 0 && start <= article.content.length - it.length &&
            WorldReading.boundary(article.content, start) && WorldReading.boundary(article.content, start + it.length) &&
            article.content.regionMatches(start, it, 0, it.length) }.orEmpty()
    }

    fun notes(owner: Long?, rows: List<WorldArticleEntity>, notes: List<WorldNoteEntity>, looks: List<WorldPictureLookEntity>,
        pictures: List<WorldPictureEntity>): List<Note> {
        val sources = articles(owner, rows).associateBy { it.id }
        return (notes.filter { it.companionId == owner }.mapNotNull { note ->
            sources[note.articleId]?.let { article -> Note("note:${note.id}", article, note.createdAt, "摘要笔记", note.content, note.question, quote(article, note)) }
        } + looks.filter { it.companionId == owner && Regex("[0-9a-f]{64}").matches(it.pictureSha256) }.mapNotNull { look ->
            sources[look.articleId]?.let { article -> Note("look:${look.id}", article, look.createdAt, "曾留的看图感想", look.content, look.question,
                historicalImage = image(article, pictures)?.sha256 != look.pictureSha256) }
        }).filter { it.content.isNotBlank() || it.question.isNotBlank() || it.quote.isNotBlank() }
            .distinctBy { it.key }.sortedWith(compareByDescending<Note> { it.at }.thenBy { it.key })
    }

    fun trail(owner: Long?, rows: List<WorldArticleEntity>, notes: List<WorldNoteEntity>, looks: List<WorldPictureLookEntity>,
        pictures: List<WorldPictureEntity>): List<Step> {
        val sources = articles(owner, rows)
        val byId = sources.associateBy { it.id }
        return buildList {
            sources.forEach { article ->
                if (article.createdAt > 0) add(Step("saved:${article.id}", article, article.createdAt, "保存摘要", "获取资料不代表 TA 已经读过。"))
                val state = WorldOutsideCardData.from(owner, article, emptyList())!!.reading
                if (article.readAt != null && article.readAt > 0 && state in setOf(WorldOutsideCardData.Reading.Partial, WorldOutsideCardData.Reading.Complete))
                    add(Step("read:${article.id}", article, article.readAt, if (state == WorldOutsideCardData.Reading.Complete) "读完这份摘要" else "读过部分摘要",
                        "根据最近保存的阅读进度显示，不能表示读过网页全文。"))
                image(article, pictures)?.takeIf { it.fetchedAt > 0 }?.let {
                    add(Step("picture:${article.id}", article, it.fetchedAt, "保存公开配图", "取得配图不代表 TA 看过图片。"))
                }
            }
            WorldOutsideViews.notes(owner, rows, notes, emptyList(), pictures).filter { it.at > 0 }.forEach {
                add(Step(it.key, it.article, it.at, "留下摘要笔记", if (it.question.isNotBlank()) "笔记中还有一个想继续了解的问题。" else "已保存在这位 TA 的随手本。"))
            }
            looks.filter { it.companionId == owner && it.createdAt > 0 && Regex("[0-9a-f]{64}").matches(it.pictureSha256) }.distinctBy { it.id }.forEach { look ->
                byId[look.articleId]?.let { article ->
                    add(Step("look:${look.id}", article, look.createdAt, "看过当时的配图",
                        if (image(article, pictures)?.sha256 != look.pictureSha256) "历史看图记录；不能表示看过当前配图。"
                        else "有实际看图记录；不代表现实到访或拍摄。"))
                }
            }
        }.sortedWith(compareByDescending<Step> { it.at }.thenBy { it.key }).take(TRAIL_LIMIT)
    }
}
