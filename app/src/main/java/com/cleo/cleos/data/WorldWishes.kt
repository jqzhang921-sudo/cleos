package com.cleo.cleos.data

import com.cleo.cleos.data.db.WorldArticleEntity
import com.cleo.cleos.data.db.WorldWishEntity
import java.text.Normalizer
import java.util.Locale

object WorldWishes {
    const val COOLDOWN = 24 * 60 * 60 * 1000L
    const val MAX_PENDING = 10
    const val MAX_HISTORY = 100
    val statuses = setOf("pending", "later", "ignored", "added")

    // A translated/alternate spelling is deliberately not guessed to be the same work.
    fun identity(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun valid(title: String, author: String, reason: String): Boolean =
        title.isNotBlank() && title.length <= 120 && identity(title).isNotBlank() &&
            author.length <= 120 && reason.isNotBlank() && reason.length <= 600 &&
            listOf(title, author, reason).none { text -> text.any { it.isISOControl() && it != '\n' && it != '\t' } }

    fun mayAsk(wishes: List<WorldWishEntity>, now: Long): Boolean = wishes.size < MAX_HISTORY &&
        wishes.count { it.status == "pending" || it.status == "later" } < MAX_PENDING &&
        wishes.none { now < it.createdAt || now - it.createdAt < COOLDOWN }

    fun duplicate(title: String, wishes: List<WorldWishEntity>, articles: List<WorldArticleEntity>): Boolean {
        val key = identity(title)
        return wishes.any { it.identity == key || it.catalogTitle.isNotBlank() && identity(it.catalogTitle) == key } ||
            articles.any { it.space == "study" && identity(it.title) == key } ||
            WorldBookVolumes.books(articles.filter { it.space == "study" }).any { it.grouped && identity(it.title) == key }
    }

    fun validateBackup(wishes: List<WorldWishEntity>, articles: List<WorldArticleEntity>, companions: Set<Long>) {
        require(wishes.map { it.id }.toSet().size == wishes.size && wishes.map { it.runId }.toSet().size == wishes.size)
        require(wishes.map { it.companionId to it.identity }.toSet().size == wishes.size)
        require(wishes.groupingBy { it.companionId }.eachCount().values.all { it <= MAX_HISTORY })
        require(wishes.filter { it.status == "pending" || it.status == "later" }.groupingBy { it.companionId }.eachCount().values.all { it <= MAX_PENDING })
        val verified = wishes.filter { it.verifiedAt != null }
        require(verified.map { it.companionId to it.catalogKey }.toSet().size == verified.size)
        val shelf = articles.associateBy { it.id }
        wishes.forEach { w ->
            require(w.id > 0 && w.companionId in companions && w.createdAt > 0 && w.runId.isNotBlank() && w.runId.length <= 100)
            require(valid(w.title, w.author, w.reason) && w.identity == identity(w.title) && w.status in statuses)
            require(w.handledAt == null || w.handledAt >= w.createdAt)
            require((w.status == "pending") == (w.handledAt == null))
            require(w.articleId == null || w.status == "added" && shelf[w.articleId]?.let { it.companionId == w.companionId && it.space == "study" } == true)
            if (w.verifiedAt == null) require(w.catalogKey.isEmpty() && w.catalogTitle.isEmpty() && w.catalogAuthor.isEmpty())
            else require(w.verifiedAt > 0 && w.catalogKey.matches(Regex("OL[0-9]+W")) &&
                w.catalogTitle.isNotBlank() && w.catalogTitle.length <= 240 && w.catalogAuthor.length <= 240)
        }
    }
}
