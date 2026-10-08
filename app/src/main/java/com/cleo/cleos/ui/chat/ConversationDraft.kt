package com.cleo.cleos.ui.chat

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap

internal class ConversationDraftState(private val drafts: SnapshotStateMap<String, String>, private val key: String) : MutableState<String> {
    override var value: String
        get() = drafts[key].orEmpty()
        set(value) { drafts[key] = value }
    override fun component1(): String = value
    override fun component2(): (String) -> Unit = { value = it }
    fun restore(conversationId: Long, text: String) { drafts[conversationId.toString()] = text }
}

/** Drafts stay with their conversation when a feed recipient changes the current TA. */
@Composable
internal fun rememberConversationDraft(conversationId: Long?): ConversationDraftState {
    val drafts = rememberSaveable(saver = mapSaver<SnapshotStateMap<String, String>>(
        save = { it.toMap() },
        restore = { saved -> mutableStateMapOf<String, String>().apply {
            saved.forEach { (key, value) -> this[key] = value as String }
        } },
    )) { mutableStateMapOf<String, String>() }
    val key = conversationId?.toString() ?: "loading"
    return remember(key) { ConversationDraftState(drafts, key) }
}
