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

/** Device-local, separately granted public topic exploration. One topic and at most three extracts per opportunity. */
class WorldTopicScheduler(context: Context, private val db: AppDatabase, private val library: WorldLibrary,
    private val outside: WorldOutsideLibrary, private val scope: CoroutineScope, private val diagnostic: (String) -> Unit = {},
) {
    private val work = WorkManager.getInstance(context)
    private val schedules = Mutex()
    @Volatile private var paused = false

    suspend fun setEnabled(id: Long, createdAt: Long, enabled: Boolean) = schedules.withLock {
        check(!paused) { "资料正在恢复，请稍后再设置" }
        var changed = false
        val ticket = db.withTransaction {
            check(db.companions().get(id)?.createdAt == createdAt) { "这位 TA 的资料已变化，请重新打开继续查阅" }
            val old = db.world().topicSchedule(id)
            if (!enabled) { db.world().deleteTopicSchedule(id); null }
            else old?.takeIf { it.companionCreatedAt == createdAt } ?: WorldTopicScheduleEntity(
                id, createdAt, UUID.randomUUID().toString(), WorldScheduleRules.first(ZonedDateTime.now()),
            ).also { db.world().put(it); changed = true }
        }
        if (ticket == null || changed) {
            outside.cancelBackground(id, "topic"); library.cancelBackground(id, "topic")
            withContext(Dispatchers.IO) { work.cancelAllWorkByTag(taTag(id)).result.get() }
        }
        if (ticket != null) try { enqueue(ticket) } catch (e: Exception) {
            withContext(NonCancellable) {
                if (changed && db.world().topicSchedule(id) == ticket) {
                    db.world().deleteTopicSchedule(id); work.cancelAllWorkByTag(taTag(id))
                }
            }
            throw e
        }
    }

    suspend fun forget(id: Long) = schedules.withLock {
        db.world().deleteTopicSchedule(id)
        outside.cancelBackground(id, "topic"); library.cancelBackground(id, "topic")
        withContext(Dispatchers.IO) { work.cancelAllWorkByTag(taTag(id)).result.get() }
    }

    fun restore() = scope.launch {
        schedules.withLock {
            paused = false; outside.resume()
            for (ticket in db.world().topicSchedules()) {
                if (db.companions().get(ticket.companionId)?.createdAt != ticket.companionCreatedAt) {
                    db.world().deleteTopicSchedule(ticket.companionId); work.cancelAllWorkByTag(taTag(ticket.companionId))
                } else runCatching { enqueue(ticket) }.onFailure {
                    diagnostic("event=world_topic_schedule_failure route=world stage=restore reason=enqueue")
                }
            }
        }
    }

    suspend fun stopAll() {
        schedules.withLock {
            paused = true
            withContext(Dispatchers.IO) { work.cancelAllWorkByTag(TAG).result.get() }
        }
        outside.stopBackground("topic"); library.stopBackground("topic")
    }

    private suspend fun enqueue(ticket: WorldTopicScheduleEntity) {
        if (paused || db.world().topicSchedule(ticket.companionId) != ticket) return
        val request = OneTimeWorkRequestBuilder<WorldTopicWorker>()
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
            var reconcile: WorldTopicScheduleEntity? = null
            val advanced = db.withTransaction {
                val ticket = db.world().topicSchedule(id) ?: return@withTransaction null
                if (ticket.revision != revision || db.companions().get(id)?.createdAt != ticket.companionCreatedAt) return@withTransaction null
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
        suspend fun allowed(): Boolean {
            val current = db.world().topicSchedule(id)
            return !paused && current?.revision == next.revision && current.nextAt == next.nextAt &&
                db.companions().get(id)?.createdAt == next.companionCreatedAt
        }
        currentCoroutineContext().ensureActive()
        if (!allowed() || outside.busy(id) || !library.canBrowseOutside(id, next.companionCreatedAt)) return true
        val articles = db.world().articles(id)
        if (articles.count { it.space == "outside" } >= WorldOutside.MAX_ITEMS) return true
        val now = System.currentTimeMillis()
        val recent = (articles.filter { it.searchQuery.isNotEmpty() && now - it.createdAt < WorldTopicChoice.COOLDOWN_MS }
            .map { it.searchQuery } + listOfNotNull(next.lastQuery.takeIf { it.isNotEmpty() && now - next.lastQueryAt < WorldTopicChoice.COOLDOWN_MS })).distinct()
        runCatching { diagnostic("event=world_topic_opportunity route=world stage=background") }
        library.exploreInBackground(id, recent, fetch = { query ->
            check(allowed())
            val result = outside.searchInBackground(id, next.companionCreatedAt, query, ::allowed)
            db.withTransaction {
                check(allowed())
                val current = requireNotNull(db.world().topicSchedule(id))
                db.world().put(current.copy(lastQuery = query, lastQueryAt = System.currentTimeMillis()))
            }
            result.articleIds
        }, permission = ::allowed)
        return true
    }

    private fun taTag(id: Long) = "$TAG-ta-$id"
    companion object { private const val TAG = "world-topic" }
}

class WorldTopicWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("companion", -1)
        val revision = inputData.getString("revision") ?: return Result.success()
        val due = inputData.getLong("due", -1)
        if (id <= 0 || due <= 0) return Result.success()
        return try {
            if ((applicationContext as CleosApp).container.worldTopicScheduler.run(id, revision, due)) Result.success() else Result.retry()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
