package com.cleo.cleos.data

import com.cleo.cleos.data.db.*

/** Only the current TA's public outside rows may be selected; filters never imply deletion. */
object WorldOutsideSelection {
    fun rows(owner: Long?, articles: List<WorldArticleEntity>, source: String = ""): List<WorldArticleEntity> =
        if (owner == null) emptyList() else articles.filter { it.companionId == owner && it.space == "outside" && (source.isEmpty() || it.source == source) }

    fun selected(rows: List<WorldArticleEntity>, ids: Collection<Long>): List<WorldArticleEntity> = rows.filter { it.id in ids }
    fun readIds(rows: List<WorldArticleEntity>): List<Long> = rows.filter { it.content.isNotBlank() && it.readOffset == it.content.length }.map { it.id }

    data class Removal(val owner: Long, val createdAt: Long, val revision: Long,
        val articles: List<WorldArticleEntity>, val notes: List<WorldNoteEntity>,
        val looks: List<WorldPictureLookEntity>, val pictures: List<WorldPictureEntity>) {
        val ids get() = articles.map { it.id }
        fun matches(current: Removal) = this == current
    }

    fun removal(owner: Long, createdAt: Long, revision: Long, ids: List<Long>,
        articles: List<WorldArticleEntity>, notes: List<WorldNoteEntity>,
        looks: List<WorldPictureLookEntity>, pictures: List<WorldPictureEntity>): Removal {
        require(ids.isNotEmpty() && ids.size <= WorldOutside.MAX_ITEMS && ids.distinct().size == ids.size && ids.all { it > 0 }) { "请选择要移除的见闻" }
        val selected = selected(rows(owner, articles), ids).sortedBy { it.id }
        check(selected.size == ids.size) { "选中的见闻已变化，请重新选择" }
        val selectedIds = ids.toSet()
        return Removal(owner, createdAt, revision, selected,
            notes.filter { it.companionId == owner && it.articleId in selectedIds }.sortedBy { it.id },
            looks.filter { it.companionId == owner && it.articleId in selectedIds }.sortedBy { it.id },
            pictures.filter { it.companionId == owner && it.articleId in selectedIds }.sortedBy { it.articleId })
    }
}
