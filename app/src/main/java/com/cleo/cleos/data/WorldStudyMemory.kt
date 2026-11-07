package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.*

/** A live, owner-scoped projection. No extra stored summaries that outlive deletion or regrouping. */
object WorldStudyMemory {
    fun clip(text: String, size: Int): String {
        if (text.length <= size) return text
        val end = if (text[size - 1].isHighSurrogate()) size - 1 else size
        return text.take(end) + "…"
    }
    private fun books(owner: Long, articles: List<WorldArticleEntity>) = WorldBookVolumes.books(articles.filter {
        it.companionId == owner && it.space == "study" && it.id > 0 && it.content.isNotEmpty() && WorldReading.boundary(it.content, it.readOffset)
    })
    private fun bookRow(book: WorldBookVolumes.Book) = buildJsonObject {
        put("book_id", book.id); put("title", clip(book.title, 120))
        putJsonArray("part_ids") { book.parts.forEach { add(it.id) } }
        put("parts", book.parts.size); put("read_characters", book.readCharacters); put("characters", book.characters)
        put("state", when { book.readCharacters == 0L -> "on_shelf_unread"; book.nextUnread == null -> "finished_imported_text"; else -> "reading" })
        put("added_at", book.parts.minOf { it.createdAt }); put("last_read_at", book.parts.mapNotNull { it.readAt }.maxOrNull()?.let(::JsonPrimitive) ?: JsonNull)
        put("next_part", book.nextUnread?.let { book.parts.indexOf(it) + 1 }?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun wishRow(w: WorldWishEntity, books: List<WorldBookVolumes.Book>) = buildJsonObject {
        put("wish_id", w.id); put("title", clip(w.title, 120)); put("author", clip(w.author, 100)); put("reason", clip(w.reason, 160))
        put("status", w.status); put("wanted_at", w.createdAt)
        val linked = books.firstOrNull { b -> b.parts.any { it.id == w.articleId } }
        put("linked_book_id", linked?.id?.let(::JsonPrimitive) ?: JsonNull)
        put("linked_source_missing", w.status == "added" && linked == null)
        // Equal titles are candidates only. A user-confirmed link is the sole explicit association.
        putJsonArray("matching_title_book_ids") { books.filter { b -> listOf(w.title, w.catalogTitle).filter(String::isNotBlank)
            .any { WorldWishes.identity(it) == WorldWishes.identity(b.title) } }.take(3).forEach { add(it.id) } }
    }
    private fun bounded(rows: List<JsonObject>, budget: Int): JsonArray {
        val result = mutableListOf<JsonObject>()
        for (row in rows) {
            if (JsonArray(result + row).toString().length > budget) continue
            result += row
        }
        return JsonArray(result)
    }
    fun snapshot(owner: Long, articles: List<WorldArticleEntity>, wishes: List<WorldWishEntity>, query: String = "", limit: Int = 3): JsonObject {
        require(owner > 0 && limit in 1..5)
        val all = books(owner, articles)
        val own = wishes.filter { it.companionId == owner && WorldWishes.valid(it.title, it.author, it.reason) }
        val term = query.trim()
        fun String.matches() = term.isEmpty() || contains(term, ignoreCase = true)
        val relevantWishes = own.filter { (it.title + " " + it.catalogTitle + " " + it.author + " " + it.reason).matches() }
        val selected = all.filter { b -> b.title.matches() || b.parts.any { it.title.matches() } || relevantWishes.any { w -> w.articleId in b.parts.map { it.id } } }
            .sortedWith(compareByDescending<WorldBookVolumes.Book> { b -> b.parts.maxOf { maxOf(it.createdAt, it.readAt ?: 0L) } }.thenBy { it.id })
        return buildJsonObject {
            put("books", bounded(selected.take(limit).map(::bookRow), 1400))
            put("wishes", bounded(relevantWishes.sortedByDescending { it.handledAt ?: it.createdAt }.take(limit).map { wishRow(it, all) }, 1400))
            put("more", selected.size > limit || relevantWishes.size > limit)
        }
    }
    suspend fun load(dao: WorldDao, owner: Long, query: String = "", limit: Int = 3) = snapshot(owner, dao.articles(owner), dao.wishes(owner), query, limit)

    /** Only current-book notes are carried between segments; unread source text is never included. */
    fun reading(owner: Long, article: WorldArticleEntity?, articles: List<WorldArticleEntity>, wishes: List<WorldWishEntity>,
        notes: List<WorldNoteEntity>, memories: List<MemoryEntity> = emptyList()): JsonObject {
        val all = books(owner, articles)
        val book = article?.takeIf { it.companionId == owner && it.space == "study" }?.let { a -> all.firstOrNull { b -> b.parts.any { it.id == a.id } } }
        val parts = book?.parts.orEmpty().associateBy { it.id }
        val previous = notes.filter { n -> val a = parts[n.articleId]
            n.companionId == owner && a != null && WorldReading.boundary(a.content, n.fromOffset) && WorldReading.boundary(a.content, n.toOffset) &&
                n.fromOffset < n.toOffset && n.toOffset <= a.readOffset
        }.sortedByDescending { it.createdAt }.take(3)
        val previousPart = book?.parts?.takeWhile { it.id != article?.id }.orEmpty().plus(listOfNotNull(article?.takeIf { it.id in parts }))
            .lastOrNull { it.readOffset > 0 }
        val previousExcerpt = previousPart?.let { a ->
            var start = (a.readOffset - 600).coerceAtLeast(0)
            if (!WorldReading.boundary(a.content, start)) start++
            buildJsonObject { put("article_id", a.id); put("from", start); put("to", a.readOffset); put("text", a.content.substring(start, a.readOffset)) }
        }
        val related = wishes.filter { w -> w.companionId == owner && WorldWishes.valid(w.title, w.author, w.reason) &&
            (book == null || w.articleId in parts || listOf(w.title, w.catalogTitle).any { it.isNotBlank() && WorldWishes.identity(it) == WorldWishes.identity(book.title) }) }
            .sortedByDescending { it.handledAt ?: it.createdAt }.take(3)
        val keywords = Regex("[\\p{L}\\p{N}]{4,}").findAll((book?.title.orEmpty() + " " + related.joinToString(" ") { it.title + " " + it.author })).map { it.value }.toList()
        val readingWords = Regex("读书|阅读|书籍|文学|小说|作家|reading|books?|literature|novel", RegexOption.IGNORE_CASE)
        val relevantMemory = memories.filter { m -> m.companionId == owner &&
            (readingWords.containsMatchIn(m.name + " " + m.summary) || keywords.any { (m.name + " " + m.summary).contains(it, true) }) }
            .sortedWith(compareByDescending<MemoryEntity> { it.pinned }.thenByDescending { it.updatedAt }).take(4)
        return buildJsonObject {
            put("book", book?.let(::bookRow) ?: JsonNull)
            put("previous_read_excerpt", previousExcerpt ?: JsonNull)
            put("wishes", bounded(related.map { wishRow(it, all) }, 1400))
            put("previous_notes", bounded(previous.map { n -> buildJsonObject {
                put("article_id", n.articleId); put("at", n.createdAt); put("from", n.fromOffset); put("to", n.toOffset)
                put("note", clip(n.content, 420)); put("question", clip(n.question, 180))
            } }, 1800))
            put("reading_preferences", bounded(relevantMemory.map { m -> buildJsonObject { put("name", clip(m.name, 80)); put("summary", clip(m.summary, 220)) } }, 1200))
        }
    }
}
