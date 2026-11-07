package com.cleo.cleos.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.cleo.cleos.CleosApp
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.WorldScheduleEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/** An opt-in is local to this device and TA incarnation. Each durable ticket can be consumed once. */
class WorldReadingScheduler(context: Context, private val db: AppDatabase, private val library: WorldLibrary,
    private val scope: CoroutineScope, private val diagnostic: (String) -> Unit = {},
) {
    private val work = WorkManager.getInstance(context)
    private val schedules = Mutex()
    @Volatile private var paused = false

    suspend fun setEnabled(id: Long, createdAt: Long, enabled: Boolean) = schedules.withLock {
        check(!paused) { "资料正在恢复，请稍后再设置" }
        var created = false
        val ticket = db.withTransaction {
            check(db.companions().get(id)?.createdAt == createdAt) { "这位 TA 的资料已变化，请重新打开书房" }
            if (!enabled) { db.world().deleteSchedule(id); null }
            else db.world().schedule(id)?.takeIf { it.companionCreatedAt == createdAt } ?: WorldScheduleEntity(
                id, createdAt, UUID.randomUUID().toString(), WorldScheduleRules.first(ZonedDateTime.now()),
            ).also { db.world().put(it); created = true }
        }
        if (ticket == null) {
            library.cancelBackground(id)
            withContext(Dispatchers.IO) { work.cancelAllWorkByTag(taTag(id)).result.get() }
        } else try { enqueue(ticket) } catch (e: Exception) {
            // A partially saved opt-in must not unexpectedly start on a later app launch.
            withContext(NonCancellable) {
                if (created && db.world().schedule(id) == ticket) {
                    db.world().deleteSchedule(id)
                    work.cancelAllWorkByTag(taTag(id))
                }
            }
            throw e
        }
    }

    /** Also cancels manual reading before the TA and its foreign-key rows are removed. */
    suspend fun forget(id: Long) = schedules.withLock {
        db.world().deleteSchedule(id)
        library.cancel(id)
        withContext(Dispatchers.IO) { work.cancelAllWorkByTag(taTag(id)).result.get() }
    }

    fun restore() = scope.launch {
        schedules.withLock {
            paused = false; library.resume()
            for (ticket in db.world().schedules()) {
                if (db.companions().get(ticket.companionId)?.createdAt != ticket.companionCreatedAt) {
                    db.world().deleteSchedule(ticket.companionId)
                    work.cancelAllWorkByTag(taTag(ticket.companionId))
                } else runCatching { enqueue(ticket) }.onFailure {
                    diagnostic("event=world_schedule_failure route=world stage=restore reason=enqueue")
                }
            }
        }
    }

    suspend fun stopAll() {
        schedules.withLock {
            paused = true
            withContext(Dispatchers.IO) { work.cancelAllWorkByTag(TAG).result.get() }
        }
        library.stopBackground("study")
    }

    private suspend fun enqueue(ticket: WorldScheduleEntity) {
        if (paused || db.world().schedule(ticket.companionId) != ticket) return
        val request = OneTimeWorkRequestBuilder<WorldReadingWorker>()
            .addTag(TAG).addTag(taTag(ticket.companionId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay((ticket.nextAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInputData(workDataOf("companion" to ticket.companionId, "revision" to ticket.revision, "due" to ticket.nextAt)).build()
        // A ticket has its own unique name: restore and stale Workers cannot append duplicates.
        withContext(Dispatchers.IO) {
            work.enqueueUniqueWork("$TAG-${ticket.companionId}-${ticket.revision}-${ticket.nextAt}", ExistingWorkPolicy.KEEP, request).result.get()
        }
    }

    /** False means the wall clock moved back; keep this Worker pending without reading early. */
    suspend fun run(id: Long, revision: String, due: Long): Boolean {
        var early = false
        val next = schedules.withLock {
            if (paused) return true
            var restoreTicket: WorldScheduleEntity? = null
            val advanced = db.withTransaction {
                val ticket = db.world().schedule(id) ?: return@withTransaction null
                if (ticket.revision != revision) return@withTransaction null
                if (db.companions().get(id)?.createdAt != ticket.companionCreatedAt) return@withTransaction null
                if (ticket.nextAt != due) { restoreTicket = ticket; return@withTransaction null }
                val now = ZonedDateTime.now()
                if (now.toInstant().toEpochMilli() < due) { early = true; return@withTransaction null }
                val reading = db.world().state(id)
                val used = reading?.let { it.attempts - if (it.token != null) 1 else 0 } ?: 0
                val exhausted = reading?.day == now.toLocalDate().toEpochDay() && used >= WorldReading.DAILY
                ticket.copy(nextAt = WorldScheduleRules.next(now, exhausted)).also { db.world().put(it) }
            }
            if (advanced == null) {
                restoreTicket?.let { enqueue(it) }
                return !early
            }
            // Persist and schedule the next chance before starting a model; process death cannot replay this ticket.
            enqueue(advanced)
            advanced
        }
        runCatching { diagnostic("event=world_reading_opportunity route=world stage=background") }
        library.readInBackground(id) {
            !paused && db.world().schedule(id) == next && db.companions().get(id)?.createdAt == next.companionCreatedAt
        }
        return true
    }

    private fun taTag(id: Long) = "$TAG-ta-$id"
    companion object { private const val TAG = "world-reading" }
}

class WorldReadingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("companion", -1)
        val revision = inputData.getString("revision") ?: return Result.success()
        val due = inputData.getLong("due", -1)
        if (id <= 0 || due <= 0) return Result.success()
        return try {
            if ((applicationContext as CleosApp).container.worldReading.run(id, revision, due)) Result.success() else Result.retry()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
