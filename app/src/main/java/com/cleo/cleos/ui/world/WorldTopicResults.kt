package com.cleo.cleos.ui.world

import com.cleo.cleos.data.db.WorldArticleEntity

/** Restored caches are bounded just like a fresh query; a feed refresh has no topic identity. */
internal fun topicResults(articles: List<WorldArticleEntity>, query: String, fetchedQuery: String?, fetchedIds: List<Long>?): List<WorldArticleEntity> {
    val term = query.trim()
    if (term.isEmpty()) return emptyList()
    return articles.filter { it.space == "outside" &&
        if (!fetchedQuery.isNullOrBlank() && fetchedQuery == term) it.id in fetchedIds.orEmpty() else it.searchQuery == term
    }.take(3)
}
