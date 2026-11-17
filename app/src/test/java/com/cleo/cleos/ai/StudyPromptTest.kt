package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class StudyPromptTest {
    private val ta = CompanionEntity(id = 10, name = "Reader", createdAt = 1, apiBaseUrl = "", apiModel = "")
    private val history = listOf(MessageEntity(id = 1, conversationId = 1, role = "user", content = "I added the book", createdAt = 1))
    private val now = ZonedDateTime.parse("2026-11-09T14:00:00+08:00")
    private val snapshot = """{"books":[{"title":"STUDY_CANARY","read_characters":24000,"last_read_at":${now.toInstant().toEpochMilli() - 1000}}],"wishes":[],"more":false}"""
    private fun prompt(enabled: Boolean = true, rows: List<MessageEntity> = history, snapshot: String = this.snapshot, background: Boolean = false) = Prompt.messages(
        AppSettings(tools = if (enabled) setOf(ToolGroup.WorldRecall) else emptySet()), ta, rows, now, studyMemory = snapshot, studyBackground = background)
    @Test fun privateChatIncludesBookSnapshotBeforeUserWordsInUncachedTurn() {
        val p = prompt(); assertFalse(p.first().content.contains("STUDY_CANARY"))
        assertTrue(p.last().content.contains("STUDY_CANARY")); assertTrue(p.last().content.contains("已上架不等于已读"))
        assertTrue(p.last().content.indexOf("STUDY_CANARY") < p.last().content.indexOf("I added the book"))
    }
    @Test fun disabledRecallNeverInjectsSnapshotEvenIfCallerSuppliesOne() { assertFalse(prompt(false).joinToString().contains("STUDY_CANARY")) }
    @Test fun groupHistoryNeverReceivesPrivateBooks() { assertFalse(prompt(rows = history.map { it.copy(conversationId = -1) }).joinToString().contains("STUDY_CANARY")) }
    @Test fun historyFreeWakeReceivesOnlyFreshActualReading() { assertTrue(prompt(rows = emptyList(), background = true).last().content.contains("STUDY_CANARY")) }
    @Test fun noSnapshotLeavesEmptyHistoryUnchanged() { assertEquals(1, prompt(rows = emptyList(), snapshot = "").size) }
    @Test fun unrelatedChatDoesNotReceiveOldShelfFactsButExplicitTitleDoes() {
        for (text in listOf("午饭吃了吗", "考试终于结束啦", "今天好热", "你好"))
            assertFalse(prompt(rows = history.map { it.copy(content = text) }).last().content.contains("STUDY_CANARY"))
        assertTrue(prompt(rows = history.map { it.copy(content = "STUDY_CANARY 怎么样") }).last().content.contains("STUDY_CANARY"))
        assertFalse(prompt(snapshot = "not json").joinToString().contains("not json"))
    }
    @Test fun shortFollowupWorksWithoutTurningEveryGreetingIntoBookTalk() {
        val rows = history + history.single().copy(id = 2, content = "到哪里了呢", createdAt = 10)
        assertTrue(prompt(rows = rows).last().content.contains("STUDY_CANARY"))
        assertFalse(prompt(rows = rows.dropLast(1) + rows.last().copy(content = "晚上好")).last().content.contains("STUDY_CANARY"))
    }
    @Test fun wakeDoesNotReuseStaleProgressFutureDatesOrAlreadyDiscussedActivity() {
        for (at in listOf(1L, now.toInstant().toEpochMilli() + 1000)) {
            val data = snapshot.replace((now.toInstant().toEpochMilli() - 1000).toString(), at.toString())
            assertEquals(1, prompt(rows = emptyList(), snapshot = data, background = true).size)
        }
        assertFalse(prompt(rows = history.map { it.copy(createdAt = now.toInstant().toEpochMilli()) }, background = true).joinToString().contains("STUDY_CANARY"))
        assertEquals(1, prompt(rows = emptyList(), snapshot = snapshot.replace("24000", "0"), background = true).size)
    }
}
