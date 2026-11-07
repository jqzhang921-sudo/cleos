package com.cleo.cleos.ui.world

import android.content.Context

/** Only identifiers and a character anchor; never prose, API configuration or a model permission. */
internal class NotebookAnchorStore(context: Context, owner: Long, generation: Long) {
    private val prefs = context.applicationContext.getSharedPreferences("notebook_reader_anchors_v1", Context.MODE_PRIVATE)
    private val prefix = "$owner:$generation:"
    fun last(): NotebookAnchor? = NotebookAnchor.decode(prefs.getString(prefix + "last", null))
    fun lastInBook(id: Long): NotebookAnchor? = NotebookAnchor.decode(prefs.getString(prefix + "book:$id:last", null))
    fun read(key: String): NotebookAnchor? = NotebookAnchor.decode(prefs.getString(prefix + key, null))
    fun seenAt(): Long = prefs.getLong(prefix + "seen", 0)
    fun observeNewest(at: Long) {
        if (at > seenAt()) prefs.edit().putLong(prefix + "seen", at).apply()
    }
    fun save(anchor: NotebookAnchor, bookId: Long = 0) {
        val edit = prefs.edit().putString(prefix + "last", anchor.encode()).putString(prefix + anchor.key, anchor.encode())
        if (bookId > 0) edit.putString(prefix + "book:$bookId:last", anchor.encode())
        edit.apply()
    }
    fun forget(key: String) {
        val edit = prefs.edit().remove(prefix + key)
        if (last()?.key == key) edit.remove(prefix + "last")
        edit.apply()
    }
}
