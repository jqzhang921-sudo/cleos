package com.cleo.cleos.ai

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.cleo.cleos.CleosApp
import com.cleo.cleos.data.SecretStore
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.FeedVisitStateEntity
import com.cleo.cleos.data.db.WakeActivityEntity as W
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Independent opt-in feed opportunities; claims survive process death and settings changes. */
class FeedVisits(context: Context, private val db: AppDatabase, private val ai: FeedAi,
    private val chat: ChatRepository, private val secrets: SecretStore,
    private val scope: CoroutineScope, private val activities: WakeActivities) {
    private val work = WorkManager.getInstance(context)
    private val schedules = Mutex()
    @Volatile private var paused = false
    private data class Active(val job: Job, val conversations: Set<Long>)
    private val active = ConcurrentHashMap<Long, Active>()

    fun interrupt(conversationId: Long) {
        active.entries.filter { conversationId in it.value.conversations }.forEach { (id, task) ->
            task.job.cancel()
            scope.launch { if (!paused) configure(id) }
        }
    }

    suspend fun configure(id: Long) = schedules.withLock {
        active[id]?.job?.cancel()
        val at = db.withTransaction {
            val ta = db.companions().get(id)?.takeIf { it.feedVisitEnabled } ?: return@withTransaction null
            val state = db.feedVisits().get(id)
            val now = ZonedDateTime.now()
            val exhausted = state?.attemptDay == now.toLocalDate().toEpochDay() && state.attempts >= FeedVisitRules.level(ta.feedVisitLevel).dailyMax
            val next = FeedVisitRules.next(ta, now, Random.nextDouble(), exhausted)
            db.feedVisits().put(state?.copy(nextAt = next) ?: FeedVisitStateEntity(id, next))
            next
        }
        if (at == null) work.cancelUniqueWork(name(id)) else enqueue(id, at, ExistingWorkPolicy.REPLACE)
    }

    fun restore() = scope.launch {
        paused = false
        for (ta in db.companions().all()) {
            if (!ta.feedVisitEnabled) work.cancelUniqueWork(name(ta.id))
            else {
                val state = db.feedVisits().get(ta.id)
                if (state == null) configure(ta.id)
                else schedules.withLock {
                    if (db.companions().get(ta.id)?.feedVisitEnabled == true)
                        enqueue(ta.id, state.nextAt, ExistingWorkPolicy.KEEP)
                }
            }
        }
    }

    /** Backup restore replaces TA identities: finish cancellations before replacing the database. */
    suspend fun stopAll() {
        paused = true
        val jobs = active.values.map { it.job }
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
        schedules.withLock {
            withContext(Dispatchers.IO) { work.cancelAllWorkByTag(TAG).result.get() }
        }
    }

    private fun enqueue(id: Long, at: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<FeedVisitWorker>()
            .addTag(TAG).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("companion" to id)).build()
        work.enqueueUniqueWork(name(id), policy, request)
    }

    suspend fun enqueueNext(id: Long, at: Long) = schedules.withLock {
        if (db.companions().get(id)?.feedVisitEnabled == true && db.feedVisits().get(id)?.nextAt == at)
            enqueue(id, at, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    suspend fun run(id: Long): Long? {
        if (paused) return null
        val ta = db.companions().get(id)?.takeIf { it.feedVisitEnabled } ?: return null
        val state = db.feedVisits().get(id) ?: return null
        val now = ZonedDateTime.now()
        if (now.toInstant().toEpochMilli() < state.nextAt) return state.nextAt
        val day = now.toLocalDate().toEpochDay()
        val attempts = if (state.attemptDay == day) state.attempts else 0
        val conversations = db.conversations().idsFor(id).toSet()
        fun occupied() = ai.busy || conversations.any { chat.busy(it) || chat.isTyping(it) }
        val reason = FeedVisitRules.held(ta, now, occupied(), attempts, !secrets.key(ta.apiBaseUrl).isNullOrBlank())
        val next = FeedVisitRules.next(ta, now, Random.nextDouble(), attempts >= FeedVisitRules.level(ta.feedVisitLevel).dailyMax)
        if (reason != null) {
            if (db.feedVisits().move(id, state.nextAt, next) == 1)
                activities.record(id, null, W.FEED, W.HELD, reason)
            return next.takeIf { db.companions().get(id)?.feedVisitEnabled == true && db.feedVisits().get(id)?.nextAt == it }
        }
        val mine = Active(currentCoroutineContext().job, conversations)
        if (active.putIfAbsent(id, mine) != null) return null
        var activity: Long? = null
        var requested = false
        try {
            if (paused) return null
            if (db.feedVisits().claim(id, state.nextAt, next, day, FeedVisitRules.level(ta.feedVisitLevel).dailyMax) != 1) return null
            activity = activities.begin(id, null, W.FEED)
            val result = ai.visit(id, ta.feedVisitPosts && (state.attemptDay != day || state.posts < 1), ta.feedVisitNews,
                allowed = {
                    val current = db.companions().get(id)
                    // Profile/model changes, input, a replacement schedule, or quiet hours invalidate this output.
                    !paused && current == ta && current.feedVisitEnabled && db.feedVisits().get(id)?.nextAt == next &&
                        ZonedDateTime.now().toLocalDate().toEpochDay() == day && !FeedVisitRules.quiet(current, ZonedDateTime.now()) &&
                        !conversations.any { chat.busy(it) || chat.isTyping(it) }
                },
                onRequest = { requested = true; activities.request(activity) },
                claimPost = { db.feedVisits().posted(id, next, day) == 1 })
            activities.finish(activity, when {
                result.cancelled -> W.CANCELLED
                result.changed -> W.ACTED
                requested -> W.QUIET
                else -> W.HELD
            }, result.detail)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { activities.finish(activity, W.CANCELLED, "设置改变或开始聊天，本次逛逛已取消") }
            throw e
        } catch (e: Exception) {
            activities.finish(activity, W.FAILED, e.message ?: "本次逛逛没有完成，下次机会再试")
        } finally { active.remove(id, mine) }
        return next.takeIf { db.companions().get(id)?.feedVisitEnabled == true && db.feedVisits().get(id)?.nextAt == it }
    }

    private fun name(id: Long) = "feed-visit-$id"
    companion object { private const val TAG = "feed-visits" }
}

class FeedVisitWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("companion", -1)
        if (id <= 0) return Result.success()
        val visits = (applicationContext as CleosApp).container.feedVisits
        return try {
            visits.run(id)?.let { visits.enqueueNext(id, it) }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
