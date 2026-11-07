package com.cleo.cleos.focus

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class FocusRepository(private val context: Context, private val db: AppDatabase) {
    private val lock = Mutex()
    private val alerts = FocusAlerts(context)
    val active = db.focus().observeActive()
    val recent = db.focus().observeRecent()
    private fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private val prefs = context.getSharedPreferences("focus_defaults", Context.MODE_PRIVATE)
    val focusMinutes get() = prefs.getInt("focus", 25).coerceIn(1, 180)
    val restMinutes get() = prefs.getInt("rest", 5).coerceIn(1, 60)

    suspend fun start(title: String, taskId: Long?, minutes: Int, rest: Int, phase: String = "focus", companionId: Long? = null) = lock.withLock {
        require(minutes in 1..(if (phase == "rest") 60 else 180) && rest in 1..60)
        require(phase == "focus" || phase == "rest")
        val row = db.withTransaction {
            reconcileLocked()
            // Two rapid taps, or another screen, return to the current interval.
            db.focus().active()?.let { return@withTransaction it }
            val ta = companionId?.takeIf { phase == "focus" }?.let { db.companions().get(it) }
            require(companionId == null || ta != null) { "这位 TA 已不存在，请重新选择" }
            FocusSession(UUID.randomUUID().toString(), taskId, title.trim().take(160).ifEmpty { "自由专注" },
                phase = phase, plannedMs = minutes * 60_000L, remainingMs = minutes * 60_000L,
                segmentElapsed = SystemClock.elapsedRealtime(), boot = boot(), startedAt = System.currentTimeMillis(),
                restMinutes = rest, companionId = ta?.id, companionName = ta?.name?.take(80).orEmpty(),
                startReplyStatus = if (ta != null) "pending" else "off",
                endReplyStatus = if (ta != null) "pending" else "off").also { db.focus().save(it) }
        }
        if (phase == "focus") prefs.edit().putInt("focus", minutes).putInt("rest", rest).apply()
        alerts.update(row)
        row
    }

    /** Returns a completed interval only when this transaction actually changes its state. */
    private suspend fun reconcileLocked(): FocusSession? {
        val s = db.focus().active() ?: return null
        val next = FocusTimer.reconcile(s, SystemClock.elapsedRealtime(), boot(), System.currentTimeMillis())
        if (s != next) {
            db.focus().save(next)
            return next
        }
        return null
    }

    suspend fun reconcile() = lock.withLock {
        val ended = db.withTransaction { reconcileLocked() }
        alerts.update(db.focus().active())
        if (ended?.status == "completed") alerts.completed(ended)
    }

    suspend fun action(id: String, action: String) = lock.withLock {
        var completed: FocusSession? = null
        db.withTransaction {
            completed = reconcileLocked()
            val s = db.focus().get(id) ?: return@withTransaction
            if (!s.active) return@withTransaction
            val now = SystemClock.elapsedRealtime()
            val next = when (action) {
                "pause" -> FocusTimer.pause(s, now)
                "resume" -> FocusTimer.resume(s, now)
                "finish" -> FocusTimer.finish(s, now, System.currentTimeMillis())
                else -> s
            }
            if (next != s) db.focus().save(next)
        }
        alerts.update(db.focus().active())
        if (completed?.status == "completed") alerts.completed(requireNotNull(completed))
    }

    suspend fun completeTask(id: Long) = db.withTransaction {
        db.todos().get(id)?.takeIf { !it.done }?.let { db.todos().upsert(it.copy(done = true, doneAt = System.currentTimeMillis())) }
    }

    suspend fun clearHistory() = lock.withLock { db.focus().clearHistory() }
}
