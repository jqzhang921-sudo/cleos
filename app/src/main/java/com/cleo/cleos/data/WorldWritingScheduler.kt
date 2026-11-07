package com.cleo.cleos.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.cleo.cleos.CleosApp
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Local free-writing consent. Consumed tickets never replay after process death or a stale Worker. */
class WorldWritingScheduler(context: Context, private val db: AppDatabase, private val secrets: SecretStore,
    private val library: WorldLibrary, private val scope: CoroutineScope,
    private val diagnostic: (String) -> Unit = {}, private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) {
    private val work = WorkManager.getInstance(context)
    private val schedules = Mutex()
    @Volatile private var paused = false

    private suspend fun fingerprint(id: Long): String? {
        val ta = db.companions().get(id) ?: return null
        if (ta.apiBaseUrl.isBlank() || ta.apiModel.isBlank()) return null
        val key = secrets.key(ta.apiBaseUrl)?.takeIf(String::isNotBlank) ?: return null
        return WorldAlbumConsent.fingerprint(ta, key, secrets.revision(ta.apiBaseUrl))
    }
    private suspend fun matches(t: WorldWritingScheduleEntity): Boolean =
        db.companions().get(t.companionId)?.createdAt == t.companionCreatedAt && fingerprint(t.companionId) == t.contextHash

    suspend fun setEnabled(expected: CompanionEntity, enabled: Boolean) = schedules.withLock {
        check(!paused) { "资料正在恢复，请稍后再设置" }
        val id = expected.id
        var changed = false
        val ticket = db.withTransaction {
            check(WorldReading.sameContext(expected, db.companions().get(id))) { "角色或模型设置已变化，请重新打开随手本" }
            if (!enabled) { db.world().deleteWritingSchedule(id); null }
            else {
                
                val hash = checkNotNull(fingerprint(id)) { "请先配置这位 TA 的模型和密钥" }
                db.world().writingSchedule(id)?.takeIf { it.companionCreatedAt == expected.createdAt && it.contextHash == hash }
                    ?: WorldWritingScheduleEntity(id, expected.createdAt, UUID.randomUUID().toString(), hash, WorldScheduleRules.first(now()))
                        .also { db.world().put(it); changed = true }
            }
        }
        if (ticket == null || changed) {
            library.stopWritingBackground(id)
            cancel(id)
        }
        if (ticket != null) try { enqueue(ticket) } catch (e: Exception) {
            withContext(NonCancellable) {
                if (changed && db.world().writingSchedule(id) == ticket) { db.world().deleteWritingSchedule(id); cancel(id) }
            }; throw e
        }
    }
    /** A key change must revoke even when an older key was subsequently put back. */
    suspend fun reconcile(id: Long) = schedules.withLock {
        if (paused) return@withLock
        val ticket = db.world().writingSchedule(id)
        if (ticket != null && !matches(ticket)) db.world().deleteWritingSchedule(id)
        if (db.world().writingSchedule(id) == null) { library.stopWritingBackground(id); cancel(id) }
    }
    suspend fun forget(id: Long) = schedules.withLock {
        db.world().deleteWritingSchedule(id); library.stopWritingBackground(id); cancel(id)
    }
    fun restore() = scope.launch {
        schedules.withLock {
            paused = false; library.resume()
            for (ticket in db.world().writingSchedules()) {
                if (!matches(ticket)) { db.world().deleteWritingSchedule(ticket.companionId); cancel(ticket.companionId) }
                else runCatching { enqueue(ticket) }.onFailure { diagnostic("event=world_writing_schedule_failure route=world stage=restore reason=enqueue") }
            }
        }
    }
    suspend fun stopAll() {
        schedules.withLock { paused = true; cancel(null) }
        library.stopBackground("writing")
    }
    private suspend fun cancel(id: Long?) = withContext(Dispatchers.IO) {
        work.cancelAllWorkByTag(if (id == null) TAG else taTag(id)).result.get(); Unit
    }
    private suspend fun enqueue(ticket: WorldWritingScheduleEntity) {
        if (paused || db.world().writingSchedule(ticket.companionId) != ticket) return
        val request = OneTimeWorkRequestBuilder<WorldWritingWorker>().addTag(TAG).addTag(taTag(ticket.companionId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay((ticket.nextAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInputData(workDataOf("companion" to ticket.companionId, "revision" to ticket.revision, "due" to ticket.nextAt)).build()
        withContext(Dispatchers.IO) { work.enqueueUniqueWork("$TAG-${ticket.companionId}-${ticket.revision}-${ticket.nextAt}", ExistingWorkPolicy.KEEP, request).result.get() }
    }
    suspend fun run(id: Long, revision: String, due: Long): Boolean {
        var early = false
        val next = schedules.withLock {
            if (paused) return true
            var reconcile: WorldWritingScheduleEntity? = null
            val advanced = db.withTransaction {
                val ticket = db.world().writingSchedule(id) ?: return@withTransaction null
                if (ticket.revision != revision) return@withTransaction null
                if (!matches(ticket)) { db.world().deleteWritingSchedule(id); return@withTransaction null }
                if (ticket.nextAt != due) { reconcile = ticket; return@withTransaction null }
                val time = now()
                if (time.toInstant().toEpochMilli() < due) { early = true; return@withTransaction null }
                val state = db.world().state(id)
                val exhausted = state?.day == time.toLocalDate().toEpochDay() && state.attempts - (if (state.token != null) 1 else 0) >= WorldReading.DAILY
                ticket.copy(nextAt = WorldScheduleRules.next(time, exhausted)).also { db.world().put(it) }
            }
            if (advanced == null) { reconcile?.let { enqueue(it) }; return !early }
            enqueue(advanced); advanced
        }
        suspend fun allowed(): Boolean = !paused && db.world().writingSchedule(id) == next && matches(next)
        currentCoroutineContext().ensureActive()
        if (!allowed() || !library.canBrowseOutside(id, next.companionCreatedAt)) return true
        runCatching { diagnostic("event=world_writing_opportunity route=world stage=background") }
        library.writeInBackground(id, ::allowed)
        return true
    }
    private fun taTag(id: Long) = "$TAG-ta-$id"
    companion object { private const val TAG = "world-writing" }
}

class WorldWritingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("companion", -1)
        val revision = inputData.getString("revision") ?: return Result.success()
        val due = inputData.getLong("due", -1)
        if (id <= 0 || due <= 0) return Result.success()
        return try { if ((applicationContext as CleosApp).container.worldWritingScheduler.run(id, revision, due)) Result.success() else Result.retry() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }
}
