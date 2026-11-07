package com.cleo.cleos.focus

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** One interval. Task titles are snapshots; deleting a todo must not delete time already spent. */
@Serializable
@Entity(tableName = "focus_sessions")
data class FocusSession(
    @PrimaryKey val id: String,
    val taskId: Long? = null,
    val title: String,
    val phase: String = "focus",
    val status: String = "running",
    val plannedMs: Long,
    val remainingMs: Long,
    val segmentElapsed: Long,
    val boot: Int,
    val startedAt: Long,
    val endedAt: Long? = null,
    val restMinutes: Int = 5,
    @ColumnInfo(defaultValue = "NULL") val companionId: Long? = null,
    @ColumnInfo(defaultValue = "''") val companionName: String = "",
    @ColumnInfo(defaultValue = "'off'") val startReplyStatus: String = "off",
    @ColumnInfo(defaultValue = "NULL") val startReply: String? = null,
    @ColumnInfo(defaultValue = "'off'") val endReplyStatus: String = "off",
    @ColumnInfo(defaultValue = "NULL") val endReply: String? = null,
) {
    val active: Boolean get() = status == "running" || status == "paused"
}

/** Pure monotonic-clock rules: screen lifetime and wall-clock changes never drive the countdown. */
object FocusTimer {
    fun remaining(s: FocusSession, elapsed: Long): Long = when (s.status) {
        "running" -> (s.remainingMs - (elapsed - s.segmentElapsed).coerceAtLeast(0)).coerceAtLeast(0)
        else -> s.remainingMs
    }.coerceIn(0, s.plannedMs)

    fun reconcile(s: FocusSession, elapsed: Long, boot: Int, wall: Long): FocusSession = when {
        !s.active -> s
        s.boot != boot -> s.copy(status = "interrupted", endedAt = wall)
        s.status == "running" && remaining(s, elapsed) == 0L -> s.copy(
            status = "completed", remainingMs = 0,
            endedAt = wall - ((elapsed - s.segmentElapsed) - s.remainingMs).coerceAtLeast(0),
        )
        else -> s
    }

    fun pause(s: FocusSession, elapsed: Long): FocusSession = if (s.status == "running")
        s.copy(status = "paused", remainingMs = remaining(s, elapsed)) else s

    fun resume(s: FocusSession, elapsed: Long): FocusSession = if (s.status == "paused")
        s.copy(status = "running", segmentElapsed = elapsed) else s

    fun finish(s: FocusSession, elapsed: Long, wall: Long): FocusSession = if (s.active)
        s.copy(status = "ended", remainingMs = remaining(s, elapsed), endedAt = wall) else s

    fun spent(s: FocusSession): Long = (s.plannedMs - s.remainingMs).coerceIn(0, s.plannedMs)

    /** A backup can move to another device; live timers are never re-armed by importing it. */
    fun restored(s: FocusSession): FocusSession {
        require(s.id.isNotBlank() && s.id.length <= 100)
        require(s.phase in setOf("focus", "rest"))
        require(s.status in setOf("running", "paused", "completed", "ended", "interrupted"))
        require(s.plannedMs in 1..10_800_000 && s.remainingMs in 0..s.plannedMs)
        require(s.restMinutes in 1..60 && s.title.length <= 160)
        require(s.companionId == null || s.companionId > 0)
        require(s.companionName.length <= 80)
        for ((state, text) in listOf(s.startReplyStatus to s.startReply, s.endReplyStatus to s.endReply)) {
            require(state in FocusCompanionRules.STATES)
            require(text == null || (text.isNotBlank() && text.length <= 400))
            require((state == "ready") == (text != null))
        }
        require(s.companionId != null || (s.startReplyStatus == "off" && s.endReplyStatus == "off"))
        // Importing a file never grants permission to make new model requests.
        fun saved(state: String) = if (state in setOf("pending", "running", "failed")) "skipped" else state
        return s.copy(status = if (s.active) "interrupted" else s.status,
            startReplyStatus = saved(s.startReplyStatus), endReplyStatus = saved(s.endReplyStatus))
    }

    fun forBackup(s: FocusSession, elapsed: Long, boot: Int, wall: Long): FocusSession {
        val current = reconcile(s, elapsed, boot, wall)
        val saved = if (current.status == "running") pause(current, elapsed) else current
        return restored(saved).let { if (saved.active) it.copy(endedAt = wall) else it }
    }
}

@Dao
interface FocusDao {
    // Insertion order survives changes to the phone's date; export keeps the same order.
    @Query("SELECT * FROM focus_sessions ORDER BY rowid DESC LIMIT 100")
    fun observeRecent(): Flow<List<FocusSession>>
    @Query("SELECT * FROM focus_sessions WHERE status IN ('running','paused') LIMIT 1")
    fun observeActive(): Flow<FocusSession?>
    @Query("SELECT * FROM focus_sessions WHERE status IN ('running','paused') LIMIT 1")
    suspend fun active(): FocusSession?
    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    suspend fun get(id: String): FocusSession?
    @Upsert suspend fun save(s: FocusSession)
    @Insert suspend fun insertAll(rows: List<FocusSession>)
    @Query("SELECT * FROM focus_sessions ORDER BY rowid") suspend fun all(): List<FocusSession>
    @Query("DELETE FROM focus_sessions WHERE status NOT IN ('running','paused')") suspend fun clearHistory()
    @Query("DELETE FROM focus_sessions") suspend fun clear()
    @Query("UPDATE focus_sessions SET startReplyStatus=:state, startReply=:text WHERE id=:id AND startReplyStatus=:expected")
    suspend fun startReply(id: String, expected: String, state: String, text: String?): Int
    @Query("UPDATE focus_sessions SET endReplyStatus=:state, endReply=:text WHERE id=:id AND endReplyStatus=:expected")
    suspend fun endReply(id: String, expected: String, state: String, text: String?): Int
    @Query("UPDATE focus_sessions SET startReplyStatus='failed' WHERE startReplyStatus='running'") suspend fun recoverStartReplies()
    @Query("UPDATE focus_sessions SET endReplyStatus='failed' WHERE endReplyStatus='running'") suspend fun recoverEndReplies()
}
