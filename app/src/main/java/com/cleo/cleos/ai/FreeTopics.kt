package com.cleo.cleos.ai

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cleo.cleos.CleosApp
import com.cleo.cleos.Notifier
import com.cleo.cleos.data.StickerText
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.FreeTopicStateEntity
import com.cleo.cleos.data.db.WakeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Low-frequency, opt-in opportunities. Persisted claims bound considerations after a restart. */
class FreeTopics(
    context: Context,
    private val db: AppDatabase,
    private val chat: ChatRepository,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
    private val showing: (Long) -> Boolean,
    private val onEnabled: () -> Unit,
) {
    private val work = WorkManager.getInstance(context)
    private data class Active(val target: Long, val conversations: Set<Long>)
    private val active = ConcurrentHashMap<Long, Active>()
    private fun now() = ZonedDateTime.now()
    private fun start(ta: CompanionEntity) = FreeTopicRules.minute(ta.freeTopicQuietStart, 1380)
    private fun end(ta: CompanionEntity) = FreeTopicRules.minute(ta.freeTopicQuietEnd, 480)
    private fun next(ta: CompanionEntity, now: ZonedDateTime) = FreeTopicRules.next(now,
        FreeTopicRules.level(ta.freeTopicLevel), ta.freeTopicQuietOn, start(ta), end(ta), Random.nextDouble())

    /** Input in any of this TA's conversations cancels its current free-topic turn. */
    fun interrupt(conversationId: Long) {
        active.values.filter { conversationId in it.conversations }.forEach { chat.cancelFollowUp(it.target) }
    }

    suspend fun configure(id: Long) {
        active[id]?.let { chat.cancelFollowUp(it.target) }
        val at = db.withTransaction {
            val ta = db.companions().get(id) ?: return@withTransaction null
            if (!ta.freeTopicEnabled) return@withTransaction null
            val state = db.freeTopics().get(id)
            val at = next(ta, now())
            // Daily counts survive changing the level, switching off/on, and restarting the app.
            db.freeTopics().put(state?.copy(nextAt = at) ?: FreeTopicStateEntity(id, at))
            at
        }
        if (at == null) work.cancelUniqueWork("free-topic-$id")
        else { onEnabled(); enqueue(id, at, ExistingWorkPolicy.REPLACE) }
    }

    fun restore() = scope.launch {
        for (ta in db.companions().all().filter { it.freeTopicEnabled }) {
            onEnabled()
            val state = db.freeTopics().get(ta.id)
            if (state == null) configure(ta.id)
            else {
                val current = now()
                val level = FreeTopicRules.level(ta.freeTopicLevel)
                val available = state.attemptDay != current.toLocalDate().toEpochDay() || state.attempts < level.dailyMax
                val earlier = next(ta, current)
                // Upgrade old hour-long waits without resetting the day's count or waking immediately.
                val shorten = available && !active.containsKey(ta.id) &&
                    state.nextAt - current.toInstant().toEpochMilli() > level.maxMinutes * 60_000L && earlier < state.nextAt
                if (shorten && db.freeTopics().move(ta.id, state.nextAt, earlier) == 1)
                    enqueue(ta.id, earlier, ExistingWorkPolicy.REPLACE)
                else enqueue(ta.id, state.nextAt, ExistingWorkPolicy.KEEP)
            }
        }
    }

    fun enqueue(id: Long, at: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<FreeTopicWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("companion" to id))
            .build()
        work.enqueueUniqueWork("free-topic-$id", policy, request)
    }

    suspend fun run(id: Long): Long? {
        val ta = db.companions().get(id)?.takeIf { it.freeTopicEnabled } ?: return null
        val state = db.freeTopics().get(id) ?: return null
        val now = now()
        val stamp = now.toInstant().toEpochMilli()
        if (stamp < state.nextAt) return state.nextAt
        val level = FreeTopicRules.level(ta.freeTopicLevel)
        val day = now.toLocalDate().toEpochDay()
        val attempts = if (state.attemptDay == day) state.attempts else 0
        val conversations = db.conversations().idsFor(id).toSet()
        val target = db.conversations().latestFor(id)?.id
        val lastUser = db.messages().lastUserFor(id)
        val reason = FreeTopicRules.held(true, FreeTopicRules.quiet(now, ta.freeTopicQuietOn, start(ta), end(ta)),
            conversations.any { chat.busy(it) || chat.isTyping(it) },
            if (lastUser == null) null else db.messages().lastActivityFor(id), stamp,
            db.wakes().sentSince(id, lastUser ?: 0L), attempts, level.dailyMax)
        val next = if (attempts >= level.dailyMax) {
            FreeTopicRules.outsideQuiet(now.plusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0),
                ta.freeTopicQuietOn, start(ta), end(ta)).toInstant().toEpochMilli()
        } else next(ta, now)
        if (reason != null || target == null) {
            if (db.freeTopics().move(id, state.nextAt, next) == 1) log(id, WakeEntity.HELD, reason ?: "还没有聊天")
            return db.freeTopics().get(id)?.nextAt
        }
        // Reserve the next slot and charge this consideration before starting a request.
        if (db.freeTopics().claim(id, state.nextAt, next, day, level.dailyMax) != 1)
            return db.freeTopics().get(id)?.nextAt
        val mine = Active(target, conversations)
        if (active.putIfAbsent(id, mine) != null) return next
        try {
            val result = chat.wake(target, FreeTopicRules.instruction, followUp = true, allowed = {
                val current = db.companions().get(id)
                current != null && current.freeTopicEnabled && db.freeTopics().get(id)?.nextAt == next &&
                    !FreeTopicRules.quiet(now(), current.freeTopicQuietOn, start(current), end(current)) &&
                    !conversations.any { chat.isTyping(it) || (it != target && chat.busy(it)) } &&
                    db.messages().lastActivityFor(id)?.let { System.currentTimeMillis() - it >= FreeTopicRules.IDLE_MS } == true &&
                    db.wakes().sentSince(id, db.messages().lastUserFor(id) ?: 0L) < LaterRules.UNANSWERED_MAX
            })
            when (result) {
                is ChatRepository.WakeResult.Sent -> {
                    log(id, WakeEntity.SENT, result.messages.joinToString(" / ") { StickerText.plain(it.content) })
                    if (!showing(target) && db.companions().get(id) != null) notifier.messages(ta, target, result.messages)
                }
                is ChatRepository.WakeResult.Skipped -> log(id, WakeEntity.SKIPPED, result.why)
                is ChatRepository.WakeResult.Failed -> log(id, WakeEntity.FAILED, result.why)
                ChatRepository.WakeResult.Busy -> log(id, WakeEntity.HELD, "正在聊天，留到下一次机会")
            }
        } catch (e: CancellationException) {
            chat.cancelFollowUp(target)
            throw e
        } finally {
            active.remove(id, mine)
        }
        return db.companions().get(id)?.takeIf { it.freeTopicEnabled }?.let { db.freeTopics().get(id)?.nextAt }
    }

    private suspend fun log(id: Long, outcome: String, detail: String) {
        db.withTransaction {
            if (db.companions().get(id) == null) return@withTransaction
            db.wakes().insert(WakeEntity(companionId = id, at = System.currentTimeMillis(), outcome = outcome,
                detail = ("自由找话题：" + detail).take(200)))
            db.wakes().prune(id, 50)
        }
    }
}

class FreeTopicWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("companion", -1)
        if (id <= 0) return Result.success()
        val freeTopics = (applicationContext as CleosApp).container.freeTopics
        return try {
            freeTopics.run(id)?.let { freeTopics.enqueue(id, it, ExistingWorkPolicy.APPEND_OR_REPLACE) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A storage/process failure retries slowly. Model failures consume their slot in run().
            Result.retry()
        }
    }
}
