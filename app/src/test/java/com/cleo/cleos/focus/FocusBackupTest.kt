package com.cleo.cleos.focus

import com.cleo.cleos.data.BackupFile
import com.cleo.cleos.data.BackupSettings
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FocusBackupTest {
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val settings = BackupSettings(apiBaseUrl = "https://synthetic.invalid/v1", apiModel = "fixture", aiName = "TA", userName = "User",
        persona = "", historySize = 10, wallpaper = null, glassMode = "liquid", wallpaperDark = null, wallpaperHue = null, wallpaperChroma = null)
    private val session = FocusSession("focus-test", 999, "My todo", plannedMs = 60_000, remainingMs = 60_000,
        segmentElapsed = 100_000, boot = 3, startedAt = 1_000_000)
    private fun file(rows: List<FocusSession>) = BackupFile(BackupFile.FORMAT, BackupFile.VERSION, 1_025_000, settings,
        emptyList(), emptyList(), emptyList(), emptyList(), focusSessions = rows)

    @Test fun version26BackupWithoutFocusFieldStillDecodes() {
        val raw = codec.encodeToJsonElement(BackupFile.serializer(), file(emptyList())).jsonObject.toMutableMap()
        raw.remove("focusSessions"); raw["version"] = JsonPrimitive(26)
        val restored = codec.decodeFromString(BackupFile.serializer(), JsonObject(raw).toString())
        assertTrue(restored.focusSessions.isEmpty())
        assertEquals(26, restored.version)
    }
    @Test fun newBackupRoundTripsHistoryWithoutTaskForeignKey() {
        val completed = FocusTimer.reconcile(session, 160_000, 3, 1_060_000)
        val restored = codec.decodeFromString(BackupFile.serializer(), codec.encodeToString(BackupFile.serializer(), file(listOf(completed))))
        assertEquals(listOf(completed), restored.focusSessions)
        assertTrue(restored.todos.isEmpty())
        assertEquals(999L, restored.focusSessions.single().taskId)
        assertTrue(restored.version >= 27)
    }
    @Test fun exportOfRunningTimerSavesOnlyTimeActuallySpentAndNeverRearmsIt() {
        val snapshot = FocusTimer.forBackup(session, 125_000, 3, 1_025_000)
        assertEquals("interrupted", snapshot.status)
        assertEquals(25_000L, FocusTimer.spent(snapshot))
        assertEquals(1_025_000L, snapshot.endedAt)
        assertEquals(snapshot, FocusTimer.restored(snapshot))
        assertEquals("running", session.status)
    }
    @Test fun pausedBackupDoesNotCountTimeWhilePaused() {
        val paused = FocusTimer.pause(session, 120_000)
        val snapshot = FocusTimer.forBackup(paused, 9_000_000, 3, 9_000_000)
        assertEquals(20_000L, FocusTimer.spent(snapshot))
        assertFalse(snapshot.active)
    }
    @Test fun overdueExportIsCompletedWithoutOvershootingPlannedTime() {
        val snapshot = FocusTimer.forBackup(session, 9_000_000, 3, 9_900_000)
        assertEquals("completed", snapshot.status)
        assertEquals(60_000L, FocusTimer.spent(snapshot))
    }
    @Test fun corruptDurationsAndUnsupportedStatesAreRejectedBeforeRestore() {
        val invalid = listOf(session.copy(plannedMs = -1), session.copy(remainingMs = 90_000),
            session.copy(status = "unknown"), session.copy(phase = "alarm"), session.copy(id = ""), session.copy(restMinutes = 0))
        for (s in invalid) {
            try { FocusTimer.restored(s); fail("Invalid backup interval accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
