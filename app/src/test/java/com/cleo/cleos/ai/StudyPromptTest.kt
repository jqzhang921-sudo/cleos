package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class StudyPromptTest {
    private val ta = CompanionEntity(id = 10, name = "Reader", createdAt = 1, apiBaseUrl = "", apiModel = "")
    private val history = listOf(MessageEntity(id = 1, conversationId = 1, role = "user", content = "I added the book", createdAt = 1))
    private fun prompt(enabled: Boolean = true, rows: List<MessageEntity> = history, snapshot: String = "STUDY_CANARY") = Prompt.messages(
        AppSettings(tools = if (enabled) setOf(ToolGroup.WorldRecall) else emptySet()), ta, rows, ZonedDateTime.now(), studyMemory = snapshot)
    @Test fun privateChatIncludesBookSnapshotBeforeUserWordsInUncachedTurn() {
        val p = prompt(); assertFalse(p.first().content.contains("STUDY_CANARY"))
        assertTrue(p.last().content.contains("STUDY_CANARY")); assertTrue(p.last().content.contains("已上架不等于已读"))
        assertTrue(p.last().content.indexOf("STUDY_CANARY") < p.last().content.indexOf("I added the book"))
    }
    @Test fun disabledRecallNeverInjectsSnapshotEvenIfCallerSuppliesOne() { assertFalse(prompt(false).joinToString().contains("STUDY_CANARY")) }
    @Test fun groupHistoryNeverReceivesPrivateBooks() { assertFalse(prompt(rows = history.map { it.copy(conversationId = -1) }).joinToString().contains("STUDY_CANARY")) }
    @Test fun historyFreeWakeStillReceivesSnapshotAsMaterial() { assertTrue(prompt(rows = emptyList()).last().content.contains("STUDY_CANARY")) }
    @Test fun noSnapshotLeavesEmptyHistoryUnchanged() { assertEquals(1, prompt(rows = emptyList(), snapshot = "").size) }
}
