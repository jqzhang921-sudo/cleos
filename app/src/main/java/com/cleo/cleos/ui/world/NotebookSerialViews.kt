package com.cleo.cleos.ui.world

import com.cleo.cleos.data.db.*

internal data class NotebookBookView(val book: WorldSerialEntity, val entries: List<WorldNotebookEntries.Entry>, val matches: List<WorldNotebookEntries.Entry>)

internal object NotebookSerialViews {
    fun books(owner: Long?, books: List<WorldSerialEntity>, links: List<WorldSerialChapterEntity>,
        entries: List<WorldNotebookEntries.Entry>, query: String = "", filter: String = "all",
    ): List<NotebookBookView> {
        if (owner == null) return emptyList()
        val owned = entries.filter { it.writing?.companionId == owner }.associateBy { it.writing!!.id }
        return books.filter { it.companionId == owner }.sortedWith(compareByDescending<WorldSerialEntity> { it.updatedAt }.thenByDescending { it.id }).mapNotNull { book ->
            val children = links.filter { it.serialId == book.id }.sortedBy { it.position }.mapNotNull { owned[it.writingId] }.distinctBy { it.key }
            val titleMatch = query.isNotBlank() && book.title.contains(query.trim(), true)
            val matches = WorldNotebookEntries.select(children, if (titleMatch) "" else query, filter)
            if (matches.isEmpty() && !(query.isBlank() && filter == "all") && !(titleMatch && filter == "all")) null
            else NotebookBookView(book, children, matches)
        }
    }
}
