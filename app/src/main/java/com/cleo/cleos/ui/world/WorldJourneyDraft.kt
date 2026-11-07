package com.cleo.cleos.ui.world

import androidx.compose.runtime.*
import com.cleo.cleos.data.db.*

/** Unsaved editor state survives font/activity recreation in the VM, never enters a backup. */
internal class WorldJourneyDraft(val owner: Long, val ownerCreatedAt: Long, val revision: Long,
    val expected: WorldJourneyTopicEntity?, val expectedItems: List<WorldJourneyItemEntity>) {
    var title by mutableStateOf(expected?.title.orEmpty())
    var kind by mutableStateOf(expected?.kind ?: "topic")
    var selected by mutableStateOf(expectedItems.sortedBy { it.position }.map { it.articleId })
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}
