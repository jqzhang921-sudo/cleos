package com.cleo.cleos.ai

import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.WakeActivityEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Observable actions, not reasoning or tool payloads. Separate from the unanswered-message fuse. */
class WakeActivities(private val db: AppDatabase) {
    private val lock = Mutex()
    private var recovered = false

    suspend fun recover() = lock.withLock {
        if (!recovered) {
            db.wakeActivities().interruptOpen(System.currentTimeMillis())
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
    suspend fun tools(id: Long?, names: List<String>) {
        if (id != null) {
            val previous = db.wakeActivities().get(id)?.toolSummary.orEmpty().split("、").filter { it.isNotBlank() }
            db.wakeActivities().tools(id, names.size, (previous + names).distinct().joinToString("、").take(160))
        }
    }
    suspend fun finish(id: Long?, status: String, detail: String, sent: Int = 0) {
        if (id != null) db.wakeActivities().finish(id, status, detail.take(200), sent, System.currentTimeMillis())
    }
    suspend fun result(id: Long?, result: ChatRepository.WakeResult) {
        when (result) {
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
    suspend fun record(companionId: Long, conversationId: Long?, source: String, status: String, detail: String) {
        finish(begin(companionId, conversationId, source), status, detail)
    }
}
