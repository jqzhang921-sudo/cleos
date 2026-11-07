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

/** Source-specific device consent, independent of book reading and manual refreshes. */
class WorldOutsideScheduler(context: Context, private val db: AppDatabase, private val library: WorldLibrary,
    private val outside: WorldOutsideLibrary, private val scope: CoroutineScope, private val diagnostic: (String) -> Unit = {},
) {
    private val work = WorkManager.getInstance(context)
    private val schedules = Mutex()
    @Volatile private var paused = false
    private fun known(source: String) = WorldOutside.sources.any { it.id == source }

    suspend fun setEnabled(id: Long, createdAt: Long, enabled: Boolean, sourceId: String) = schedules.withLock {
        check(!paused) { "资料正在恢复，请稍后再设置" }
        require(known(sourceId))
        var changed = false
        val ticket = db.withTransaction {
            check(db.companions().get(id)?.createdAt == createdAt) { "这位 TA 的资料已变化，请重新打开窗外" }
            val old = db.world().outsideSchedule(id)
            if (!enabled) { db.world().deleteOutsideSchedule(id); null }
            else old?.takeIf { it.companionCreatedAt == createdAt && it.sourceId == sourceId } ?: WorldOutsideScheduleEntity(
                id, createdAt, sourceId, UUID.randomUUID().toString(), WorldScheduleRules.first(ZonedDateTime.now()),
            ).also { db.world().put(it); changed = true }
        }
        if (ticket == null || changed) {
            outside.cancelBackground(id); library.cancelBackground(id, "outside")
            withContext(Dispatchers.IO) { work.cancelAllWorkByTag(taTag(id)).result.get() }
        }
        if (ticket != null) try { enqueue(ticket) } catch (e: Exception) {
            withContext(NonCancellable) {
                if (changed && db.world().outsideSchedule(id) == ticket) {
                    db.world().deleteOutsideSchedule(id); work.cancelAllWorkByTag(taTag(id))
                }
            }
            throw e
        }
    }

    suspend fun forget(id: Long) = schedules.withLock {
        db.world().deleteOutsideSchedule(id)
        outside.cancelBackground(id); library.cancelBackground(id, "outside")
        withContext(Dispatchers.IO) { work.cancelAllWorkByTag(taTag(id)).result.get() }
    }

    fun restore() = scope.launch {
        schedules.withLock {
            paused = false; outside.resume()
            for (ticket in db.world().outsideSchedules()) {
                if (!known(ticket.sourceId) || db.companions().get(ticket.companionId)?.createdAt != ticket.companionCreatedAt) {
                    db.world().deleteOutsideSchedule(ticket.companionId); work.cancelAllWorkByTag(taTag(ticket.companionId))
                } else runCatching { enqueue(ticket) }.onFailure {
                    diagnostic("event=world_outside_schedule_failure route=world stage=restore reason=enqueue")
                }
            }
        }
    }

    suspend fun stopAll() {
        schedules.withLock {
            paused = true
            withContext(Dispatchers.IO) { work.cancelAllWorkByTag(TAG).result.get() }
        }
        outside.stopBackground("outside"); library.stopBackground("outside")
    }

    private suspend fun enqueue(ticket: WorldOutsideScheduleEntity) {
        if (paused || db.world().outsideSchedule(ticket.companionId) != ticket) return
        val request = OneTimeWorkRequestBuilder<WorldOutsideWorker>()
            .addTag(TAG).addTag(taTag(ticket.companionId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay((ticket.nextAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInputData(workDataOf("companion" to ticket.companionId, "revision" to ticket.revision, "due" to ticket.nextAt)).build()
        withContext(Dispatchers.IO) {
            work.enqueueUniqueWork("$TAG-${ticket.companionId}-${ticket.revision}-${ticket.nextAt}", ExistingWorkPolicy.KEEP, request).result.get()
        }
    }

    /** Advance before fetching. A stale Worker can reconcile the current ticket, never replay a missed one. */
    suspend fun run(id: Long, revision: String, due: Long): Boolean {
        var early = false
        val next = schedules.withLock {
            if (paused) return true
            var reconcile: WorldOutsideScheduleEntity? = null
            val advanced = db.withTransaction {
                val ticket = db.world().outsideSchedule(id) ?: return@withTransaction null
                if (ticket.revision != revision || !known(ticket.sourceId) || db.companions().get(id)?.createdAt != ticket.companionCreatedAt) return@withTransaction null
                if (ticket.nextAt != due) { reconcile = ticket; return@withTransaction null }
                val now = ZonedDateTime.now()
                if (now.toInstant().toEpochMilli() < due) { early = true; return@withTransaction null }
                val reading = db.world().state(id)
                val used = reading?.let { it.attempts - if (it.token != null) 1 else 0 } ?: 0
                val exhausted = reading?.day == now.toLocalDate().toEpochDay() && used >= WorldReading.DAILY
                ticket.copy(nextAt = WorldScheduleRules.next(now, exhausted)).also { db.world().put(it) }
            }
            if (advanced == null) { reconcile?.let { enqueue(it) }; return !early }
            enqueue(advanced); advanced
        }
        suspend fun allowed(): Boolean = !paused && db.world().outsideSchedule(id) == next &&
            db.companions().get(id)?.createdAt == next.companionCreatedAt
        currentCoroutineContext().ensureActive()
        if (!allowed() || !library.canBrowseOutside(id, next.companionCreatedAt)) return true
        runCatching { diagnostic("event=world_outside_opportunity route=world stage=background") }
        if (outside.refreshInBackground(id, next.companionCreatedAt, next.sourceId, ::allowed) && allowed()) {
            val sourceName = WorldOutside.sources.single { it.id == next.sourceId }.name
            if (db.world().articles(id).any { it.space == "outside" && it.source == sourceName && it.readOffset < it.content.length })
                library.readInBackground(id, "outside", next.sourceId, ::allowed)
        }
        return true
    }

    private fun taTag(id: Long) = "$TAG-ta-$id"
    companion object { private const val TAG = "world-outside" }
}

class WorldOutsideWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("companion", -1)
        val revision = inputData.getString("revision") ?: return Result.success()
        val due = inputData.getLong("due", -1)
        if (id <= 0 || due <= 0) return Result.success()
        return try {
            if ((applicationContext as CleosApp).container.worldOutsideScheduler.run(id, revision, due)) Result.success() else Result.retry()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
