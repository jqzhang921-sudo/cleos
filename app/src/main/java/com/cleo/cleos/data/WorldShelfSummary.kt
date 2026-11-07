package com.cleo.cleos.data

import com.cleo.cleos.data.db.WorldArticleEntity

/** Shared read-only projection for the world overview and study shelf. */
data class WorldShelfSummary(val books: List<WorldBookVolumes.Book>, val recent: Recent?) {
    data class Recent(val book: WorldBookVolumes.Book, val part: WorldArticleEntity)

    companion object {
        fun from(owner: Long?, articles: List<WorldArticleEntity>): WorldShelfSummary {
            val shelf = if (owner == null) emptyList() else articles.filter { it.companionId == owner && it.space == "study" }
            val books = WorldBookVolumes.books(shelf)
            // Import time and an offset alone do not mean TA actually read this material.
            val part = shelf.filter { it.readAt != null }.maxByOrNull { it.readAt ?: 0L }
            val recent = part?.let { read -> books.firstOrNull { book -> book.parts.any { it.id == read.id } }?.let { Recent(it, read) } }
            return WorldShelfSummary(books, recent)
        }
    }
}
