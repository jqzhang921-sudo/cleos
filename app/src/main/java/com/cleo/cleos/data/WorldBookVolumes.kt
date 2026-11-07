package com.cleo.cleos.data

import com.cleo.cleos.data.db.WorldArticleEntity
import java.util.Locale

/** A local shelf projection. Original rows, offsets, notes and bookmark identities stay intact. */
object WorldBookVolumes {
    data class Book(val title: String, val parts: List<WorldArticleEntity>, val author: String = "") {
        val id get() = parts.first().id
        val grouped get() = parts.size > 1
        val characters get() = parts.sumOf { it.content.length.toLong() }
        val readCharacters get() = parts.sumOf { it.readOffset.toLong() }
        val createdAt get() = parts.maxOf { it.createdAt }
        val nextUnread get() = parts.firstOrNull { it.readOffset < it.content.length }
        val opening get() = nextUnread ?: parts.last()
    }
    private data class Part(val title: String, val number: Int, val total: Int, val author: String, val file: String)
    private data class Key(val ta: Long, val title: String, val author: String, val file: String)
    private val titlePattern = Regex("^(.+) - Part ([1-9][0-9]?) of ([1-9][0-9]?)$", RegexOption.IGNORE_CASE)
    private val filePart = Regex("[ _-]+part[ _-]*[1-9][0-9]?(?:[ _-]+of[ _-]*[1-9][0-9]?)?(?=\\.epub$)", RegexOption.IGNORE_CASE)
    private fun String.identity() = lowercase(Locale.ROOT)
    private fun part(a: WorldArticleEntity): Part? {
        if (a.space != "study" || !a.source.startsWith("EPUB 提取文字")) return null
        val m = titlePattern.matchEntire(a.title) ?: return null
        val number = m.groupValues[2].toInt(); val total = m.groupValues[3].toInt()
        if (total !in 2..WorldReading.MAX_ARTICLES || number > total) return null
        val source = a.source.split(" · ")
        if (source.size < 3) return null
        val file = source.drop(1).dropLast(1).joinToString(" · ")
        val base = file.replace(filePart, "")
        if (base == file || source.last().isBlank()) return null
        return Part(m.groupValues[1], number, total, source.last(), base)
    }

    /** Only unambiguous, complete numbered sets from the same EPUB name and author are grouped. */
    fun books(articles: List<WorldArticleEntity>): List<Book> {
        val parsed = articles.mapNotNull { a -> part(a)?.let { a.id to it } }.toMap()
        val candidates = articles.filter { it.id in parsed }.groupBy { a ->
            val p = parsed.getValue(a.id)
            Key(a.companionId, p.title.identity(), p.author.identity(), p.file.identity())
        }
        val byId = mutableMapOf<Long, Book>()
        for (set in candidates.values) {
            val parts = set.map { parsed.getValue(it.id) }
            val total = parts.first().total
            if (set.size != total || parts.any { it.total != total } || parts.map { it.number }.toSet() != (1..total).toSet()) continue
            val ordered = set.sortedBy { parsed.getValue(it.id).number }
            val first = parsed.getValue(ordered.first().id)
            val book = Book(first.title, ordered, first.author)
            ordered.forEach { byId[it.id] = book }
        }
        val emitted = mutableSetOf<Long>()
        return articles.mapNotNull { a ->
            val book = byId[a.id] ?: Book(a.title, listOf(a))
            book.takeIf { emitted.add(it.id) }
        }
    }

    /** Offer one next unread volume per book, never a later volume before an unfinished earlier one. */
    fun candidates(articles: List<WorldArticleEntity>): List<WorldArticleEntity> = books(articles).mapNotNull { it.nextUnread }
}
