package com.cleo.cleos.data

import com.cleo.cleos.data.db.*

internal object WorldPictureExplore {
    /** Only this TA's public encyclopedia entries; never private album captions or chat. */
    fun candidates(ta: Long, articles: List<WorldArticleEntity>, looks: List<WorldPictureLookEntity>): List<WorldArticleEntity> {
        val latest = looks.groupBy { it.articleId }.mapValues { (_, rows) -> rows.maxOf { it.createdAt } }
        return articles.filter { it.companionId == ta && it.space == "outside" && runCatching { WorldPictureSource.pageUrl(it.originUrl) }.isSuccess }
            .sortedWith(compareBy<WorldArticleEntity> { latest[it.id] ?: 0L }.thenByDescending { it.createdAt }.thenBy { it.id }).take(10)
    }
}
