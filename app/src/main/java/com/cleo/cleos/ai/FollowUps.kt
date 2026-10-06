package com.cleo.cleos.ai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cleo.cleos.CleosApp
import com.cleo.cleos.Notifier
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Foreground timer and background work share a persisted, single-use opportunity. */
class FollowUps(
    context: Context,
    private val db: AppDatabase,
    private val chat: ChatRepository,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
    private val showing: (Long) -> Boolean,
) {
    private val work = WorkManager.getInstance(context)
    private val timers = ConcurrentHashMap<Long, Job>()
    private val revisions = ConcurrentHashMap<Long, Long>()
    private val clearedRevisions = ConcurrentHashMap<Long, Long>()
    private val mutex = Mutex()
    private fun revision(id: Long) = revisions[id] ?: 0L
    private fun name(id: Long) = "follow-up-$id"

    /** Input cancels the waiting opportunity even if the draft is later discarded. */
    fun cancel(id: Long) {
        val version = revisions.merge(id, 1L, Long::plus)!!
        timers.remove(id)?.cancel()
        work.cancelUniqueWork(name(id))
        scope.launch {
            mutex.withLock {
                if (revision(id) == version && (clearedRevisions[id] ?: -1L) < version) {
                    db.conversations().cancelFollowUp(id)
                    clearedRevisions[id] = version
                }
            }
        }
    }

    suspend fun cancelFor(companionId: Long) {
        for (id in db.conversations().idsFor(companionId)) {
            chat.cancelFollowUp(id)
            cancel(id)
        }
    }

    private suspend fun latest(id: Long) = db.messages().newest(id, 64)
        .firstOrNull { it.role in setOf("user", "assistant", "pat", "call") && it.note == null }

    suspend fun plan(ta: CompanionEntity, id: Long, said: List<MessageEntity>) {
        val anchor = said.lastOrNull { it.role == "assistant" && !it.proactive && it.error == null && it.call == null } ?: return
        val version = revision(id)
        mutex.withLock {
            // Re-read settings: a switch changed during the reply takes effect immediately.
            val current = db.companions().get(ta.id) ?: return
            if (!current.followUpEnabled || chat.isTyping(id) || latest(id)?.id != anchor.id || revision(id) != version) return
            val at = System.currentTimeMillis() + FollowUpRules.seconds(current.followUpDelaySeconds) * 1000L
            db.conversations().planFollowUp(id, anchor.id, at)
            // A delayed cancellation from before this reply must not erase its new opportunity.
            clearedRevisions[id] = version
            if (revision(id) != version) { db.conversations().cancelFollowUp(id); return }
            schedule(id, anchor.id, at)
        }
    }

    private fun schedule(id: Long, anchor: Long, at: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        timers.remove(id)?.cancel()
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            delay((at - System.currentTimeMillis()).coerceAtLeast(0))
            run(id, anchor)
        }
        timers[id] = timer
        timer.invokeOnCompletion { timers.remove(id, timer) }
        val request = OneTimeWorkRequestBuilder<FollowUpWorker>()
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("conversation" to id, "anchor" to anchor))
            .build()
        work.enqueueUniqueWork(name(id), policy, request)
        timer.start()
    }

    fun restore() = scope.launch {
        mutex.withLock {
            for (row in db.conversations().all()) {
                val anchor = row.followUpMessageId ?: continue
                val at = row.followUpAt ?: continue
                if (db.companions().get(row.companionId)?.followUpEnabled == true &&
                    System.currentTimeMillis() - at <= FollowUpRules.GRACE_MS) schedule(row.id, anchor, at, ExistingWorkPolicy.KEEP)
                else db.conversations().cancelFollowUp(row.id)
            }
        }
    }

    suspend fun run(id: Long, anchor: Long) {
        val version = revision(id)
        val ta = mutex.withLock {
            val row = db.conversations().get(id) ?: return
            if (row.followUpMessageId != anchor) return
            val ta = db.companions().get(row.companionId) ?: return
            val now = System.currentTimeMillis()
            if (!FollowUpRules.eligible(ta.followUpEnabled, anchor, latest(id)?.id, row.followUpAt, now,
                    chat.busy(id) || chat.isTyping(id)) || revision(id) != version) {
                db.conversations().cancelFollowUp(id)
                return
            }
            // The timer and worker cannot both win, including after an app restart.
            if (db.conversations().claimFollowUp(id, anchor, now) != 1) return
            ta
        }
        val result = chat.wake(id, FollowUpRules.instruction, followUp = true, allowed = {
            revision(id) == version && !chat.isTyping(id) &&
                db.companions().get(ta.id)?.followUpEnabled == true && latest(id)?.id == anchor
        })
        if (result is ChatRepository.WakeResult.Sent && !showing(id) && db.companions().get(ta.id) != null)
            notifier.messages(ta, id, result.messages)
        // SKIP, cancellation and failures all consume the opportunity. Never schedule from a wake.
    }
}

class FollowUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("conversation", -1)
        val anchor = inputData.getLong("anchor", -1)
        if (id > 0 && anchor > 0) (applicationContext as CleosApp).container.followUps.run(id, anchor)
        return Result.success()
    }
}
