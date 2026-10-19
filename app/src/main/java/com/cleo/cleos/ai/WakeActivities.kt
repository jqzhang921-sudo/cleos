package com.cleo.cleos.ai

import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.WakeActivityEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Observable actions, not reasoning or tool payloads. Separate from the unanswered-message fuse. */
class WakeActivities(private val db: AppDatabase, private val previousExits: () -> List<WakeProcessExit> = { emptyList() }) {
    private val lock = Mutex()
    private var recovered = false

    suspend fun recover() = lock.withLock {
        if (!recovered) {
            val now = System.currentTimeMillis()
            val exits = previousExits()
            for (open in db.wakeActivities().unfinished()) {
                db.wakeActivities().finish(open.id, WakeActivityEntity.INTERRUPTED,
                    WakeRecovery.detail(open.startedAt, now, exits), open.sent, now)
            }
            recovered = true
        }
    }

    suspend fun begin(companionId: Long, conversationId: Long?, source: String): Long? {
        recover()
        return db.withTransaction {
            if (db.companions().get(companionId) == null) return@withTransaction null
            val conversation = conversationId?.takeIf { db.conversations().get(it)?.companionId == companionId }
            db.wakeActivities().insert(WakeActivityEntity(companionId = companionId, conversationId = conversation,
                source = source, startedAt = System.currentTimeMillis())).also { db.wakeActivities().prune(companionId, 100) }
        }
    }

    suspend fun request(id: Long?) { if (id != null) db.wakeActivities().request(id) }
    suspend fun preparing(id: Long?) { if (id != null) db.wakeActivities().phase(id, "准备聊天上下文") }
    suspend fun retrying(id: Long?, waitMs: Long) {
        if (id != null) db.wakeActivities().phase(id, "连接暂时中断，${waitMs / 1000} 秒后重试")
    }
    suspend fun tools(id: Long?, names: List<String>) {
        if (id != null) {
            val previous = db.wakeActivities().get(id)?.toolSummary.orEmpty().split("、").filter { it.isNotBlank() }
            db.wakeActivities().tools(id, names.size, (previous + names).distinct().joinToString("、").take(160))
        }
    }
    suspend fun finish(id: Long?, status: String, detail: String, sent: Int = 0) = withContext(NonCancellable) {
        if (id != null) db.wakeActivities().finish(id, status, detail.take(200), sent, System.currentTimeMillis())
    }
    suspend fun cancelled(id: Long?) = withContext(NonCancellable) {
        val open = id?.let { db.wakeActivities().get(it) } ?: return@withContext
        if (open.finishedAt == null) finish(open.id, WakeActivityEntity.CANCELLED,
            WakeActivityText.cancelledDetail(open), open.sent)
    }
    suspend fun result(id: Long?, result: ChatRepository.WakeResult) {
        when (result) {
            is ChatRepository.WakeResult.Invited -> finish(id,"call_invited","发起了一次语音来电邀请，可在聊天里的通话记录查看结果")
            is ChatRepository.WakeResult.Sent -> finish(id, WakeActivityEntity.SENT, "发出了 ${result.messages.size} 条消息，可在聊天里查看", result.messages.size)
            is ChatRepository.WakeResult.Skipped -> {
                val requested = id?.let { db.wakeActivities().get(it)?.requests ?: 0 } ?: 0
                finish(id, if (requested > 0) WakeActivityEntity.QUIET else WakeActivityEntity.HELD,
                    if (requested > 0) "TA 选择保持安静，没有发送消息" else "状态改变，这次机会已取消")
            }
            is ChatRepository.WakeResult.Failed -> finish(id, WakeActivityEntity.FAILED, result.why)
            ChatRepository.WakeResult.Busy -> finish(id, WakeActivityEntity.HELD, "正在聊天或处理另一条主动消息，未请求模型")
        }
    }
    suspend fun record(companionId: Long, conversationId: Long?, source: String, status: String, detail: String) = withContext(NonCancellable) {
        finish(begin(companionId, conversationId, source), status, detail)
    }
}
