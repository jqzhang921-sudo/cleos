package com.cleo.cleos.data

/** Selecting a book/search result changes only that group; selections elsewhere stay put. */
object LoreSelection {
    fun toggle(selected: Set<Long>, ids: Collection<Long>): Set<Long> =
        if (ids.isNotEmpty() && ids.all { it in selected }) selected - ids.toSet()
        else selected + ids
}
