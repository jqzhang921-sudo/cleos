package com.cleo.cleos.focus

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FocusTimerTest {
    private fun session() = FocusSession("one", 7, "Read", plannedMs = 60_000, remainingMs = 60_000,
        segmentElapsed = 100_000, boot = 3, startedAt = 1_000_000)

    @Test fun backgroundTimeCountsWithoutScreenTicks() {
        assertEquals(15_000L, FocusTimer.remaining(session(), 145_000))
    }
    @Test fun pauseKeepsRemainingAcrossLongBackgroundStay() {
        val paused = FocusTimer.pause(session(), 120_000)
        assertEquals(40_000L, FocusTimer.remaining(paused, 9_000_000))
        assertEquals(20_000L, FocusTimer.spent(paused))
    }
    @Test fun resumeCountsOnlyNewRunningSegment() {
        val paused = FocusTimer.pause(session(), 120_000)
        val resumed = FocusTimer.resume(paused, 5_000_000)
        assertEquals(30_000L, FocusTimer.remaining(resumed, 5_010_000))
    }
    @Test fun repeatedPauseAndResumeAreIdempotent() {
        val paused = FocusTimer.pause(session(), 120_000)
        assertEquals(paused, FocusTimer.pause(paused, 130_000))
        val running = FocusTimer.resume(paused, 140_000)
        assertEquals(running, FocusTimer.resume(running, 150_000))
    }
    @Test fun repeatedCompletionDoesNotCreateAnotherTransition() {
        val completed = FocusTimer.reconcile(session(), 165_000, 3, 1_065_000)
        assertEquals("completed", completed.status)
        assertEquals(1_060_000L, completed.endedAt)
        assertEquals(completed, FocusTimer.reconcile(completed, 200_000, 3, 1_100_000))
        assertEquals(60_000L, FocusTimer.spent(completed))
    }
    @Test fun pauseAtDeadlineMustBeReconciledAsCompletion() {
        assertEquals("completed", FocusTimer.reconcile(session(), 160_000, 3, 1_060_000).status)
        assertEquals("running", FocusTimer.reconcile(session(), 159_999, 3, 1_059_999).status)
    }
    @Test fun clockAndTimezoneChangesDoNotChangeTimeRemaining() {
        val before = FocusTimer.reconcile(session(), 125_000, 3, 0)
        val after = FocusTimer.reconcile(session(), 125_000, 3, 9_999_999_999)
        assertEquals(35_000L, FocusTimer.remaining(before, 125_000))
        assertEquals(FocusTimer.remaining(before, 125_000), FocusTimer.remaining(after, 125_000))
    }
    @Test fun rebootDoesNotInventACompletedSession() {
        val interrupted = FocusTimer.reconcile(session(), 500, 4, 2_000_000)
        assertEquals("interrupted", interrupted.status)
        assertFalse(interrupted.active)
        assertEquals(0L, FocusTimer.spent(interrupted))
    }
    @Test fun rebootWhilePausedPreservesAlreadySavedTime() {
        val paused = FocusTimer.pause(session(), 125_000)
        val interrupted = FocusTimer.reconcile(paused, 10, 4, 2_000_000)
        assertEquals("interrupted", interrupted.status)
        assertEquals(25_000L, FocusTimer.spent(interrupted))
    }
    @Test fun endingRunningIntervalSavesOnlyActualTime() {
        val ended = FocusTimer.finish(session(), 123_456, 1_023_456)
        assertEquals("ended", ended.status)
        assertEquals(23_456L, FocusTimer.spent(ended))
        assertEquals(ended, FocusTimer.finish(ended, 150_000, 1_050_000))
    }
    @Test fun endingPausedIntervalDoesNotCountPauseTime() {
        val ended = FocusTimer.finish(FocusTimer.pause(session(), 130_000), 8_000_000, 9_000_000)
        assertEquals(30_000L, FocusTimer.spent(ended))
    }
    @Test fun processRecreationRestoresMonotonicDeadline() {
        val restored = Json.decodeFromString<FocusSession>(Json.encodeToString(session()))
        assertEquals(25_000L, FocusTimer.remaining(restored, 135_000))
        assertEquals("running", restored.status)
    }
    @Test fun movingBackupToAnotherPhoneNeverRearmsItsTimer() {
        val restored = FocusTimer.restored(session())
        assertEquals("interrupted", restored.status)
        assertFalse(restored.active)
        assertEquals(FocusTimer.restored(restored), restored)
    }
    @Test fun backupPreservesCompletedHistoryAndTaskSnapshot() {
        val completed = FocusTimer.reconcile(session(), 160_000, 3, 1_060_000)
        assertEquals(completed, FocusTimer.restored(completed))
        assertEquals("Read", completed.title)
        assertEquals(7L, completed.taskId)
    }
    @Test fun countdownNeverGoesBelowZero() {
        assertEquals(0L, FocusTimer.remaining(session(), 10_000_000))
    }
}
