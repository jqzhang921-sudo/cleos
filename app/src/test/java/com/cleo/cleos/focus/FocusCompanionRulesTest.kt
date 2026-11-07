package com.cleo.cleos.focus

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FocusCompanionRulesTest {
    private val base = FocusSession("focus", title = "Task", plannedMs = 60_000, remainingMs = 60_000,
        segmentElapsed = 100, boot = 1, startedAt = 1000)
    private val selected = base.copy(companionId = 7, companionName = "Selected", startReplyStatus = "pending", endReplyStatus = "pending")
    @Test fun oldRecordsAndRestNeverRequestCompanion() {
        assertFalse(FocusCompanionRules.canRequest(base, false, true))
        assertFalse(FocusCompanionRules.canRequest(selected.copy(phase = "rest"), false, true))
        val old = Json.decodeFromString<FocusSession>("""{"id":"old","title":"Task","plannedMs":60000,"remainingMs":0,"segmentElapsed":10,"boot":1,"startedAt":1000,"status":"completed"}""")
        assertNull(old.companionId); assertEquals("off", old.endReplyStatus)
    }
    @Test fun onlyTheAppropriateBoundaryCanRequestAndPauseDoesNotCreateAnotherTurn() {
        assertTrue(FocusCompanionRules.canRequest(selected, false, false))
        assertFalse(FocusCompanionRules.canRequest(selected, true, false))
        val paused = FocusTimer.pause(selected, 1000).copy(startReplyStatus = "ready", startReply = "Here")
        assertFalse(FocusCompanionRules.canRequest(paused, false, false))
        val ended = FocusTimer.finish(paused, 1000, 2000)
        assertTrue(FocusCompanionRules.canRequest(ended, true, false))
        assertFalse(FocusCompanionRules.canRequest(ended, false, true))
    }
    @Test fun failuresOnlyRetryExplicitlyAndSilenceAndStoppedRequestsNeverAutoRetry() {
        assertFalse(FocusCompanionRules.canRequest(selected.copy(startReplyStatus = "failed"), false, false))
        assertTrue(FocusCompanionRules.canRequest(selected.copy(startReplyStatus = "failed"), false, true))
        for (state in listOf("ready", "silent", "skipped", "running", "off"))
            assertFalse(FocusCompanionRules.canRequest(selected.copy(startReplyStatus = state), false, true))
    }
    @Test fun rebootAndImportCannotTurnAnInterruptedTimerIntoAModelRequest() {
        val booted = FocusTimer.reconcile(selected, 0, 2, 9000)
        assertFalse(FocusCompanionRules.canRequest(booted, false, true))
        assertFalse(FocusCompanionRules.canRequest(booted, true, true))
        val restored = FocusTimer.restored(selected.copy(startReplyStatus = "running"))
        assertEquals("skipped", restored.startReplyStatus); assertEquals("skipped", restored.endReplyStatus)
        assertFalse(FocusCompanionRules.canRequest(restored, true, true))
    }
    @Test fun backupKeepsActualWordsAndCancelsUnfinishedRequestsWithoutMutatingSource() {
        val ready = selected.copy(startReplyStatus = "ready", startReply = "Take your time.", endReplyStatus = "running")
        val saved = FocusTimer.forBackup(ready, 30_100, 1, 31_000)
        assertEquals("Take your time.", saved.startReply); assertEquals("ready", saved.startReplyStatus)
        assertEquals("skipped", saved.endReplyStatus); assertNull(saved.endReply)
        assertEquals(30_000, FocusTimer.spent(saved)); assertEquals("running", ready.endReplyStatus)
    }
    @Test fun invalidCompanionStatesAndOversizedOrMislabelledWritingFailRestoreValidation() {
        val invalid = listOf(selected.copy(companionId = -1), selected.copy(startReplyStatus = "unknown"),
            selected.copy(startReplyStatus = "ready"), selected.copy(startReply = "unlabelled"),
            selected.copy(companionName = "x".repeat(81)), selected.copy(startReplyStatus = "ready", startReply = "x".repeat(401)))
        invalid.forEach { try { FocusTimer.restored(it); fail("Invalid companion record accepted") } catch (_: IllegalArgumentException) { } }
    }
}
