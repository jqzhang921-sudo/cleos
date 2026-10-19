package com.cleo.cleos.ai

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.cleo.cleos.CleosApp
import com.cleo.cleos.Notifier
import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

data class IncomingCallOffer(val invitation: IncomingCallEntity, val ta: CompanionEntity)

/** Invitation is durable, audio is not started until the foreground UI accepts it once. */
class IncomingCalls(context: Context, private val db: AppDatabase, private val settings: SettingsRepository,
    private val secrets: SecretStore, private val chat: ChatRepository, private val notifier: Notifier,
    private val scope: CoroutineScope, private val visible: () -> Boolean,
    private val start: suspend (IncomingCallEntity) -> Boolean,
    private val stopAccepted: suspend () -> Unit = {},
    private val audioBusy: () -> Boolean = { CallAudioOwnership.slot.occupied() },
    private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.incomingCalls()
    private val work = WorkManager.getInstance(context)
    private val lock = Mutex()
    private val ready = CompletableDeferred<Unit>()
    @Volatile private var paused = false
    private val _pending = MutableStateFlow<IncomingCallOffer?>(null)
    val pending = _pending.asStateFlow()
    val problem = MutableStateFlow<String?>(null)
    val answerRequested = MutableStateFlow<String?>(null)

    init {
        scope.launch {
            try {
                lock.withLock {
                    for (row in dao.open()) {
                        if (row.status == "connecting") finish(row, "failed")
                        else if (!valid(row)) finish(row, "missed")
                    }
                }
                ready.complete(Unit)
                dao.observeOpen().collectLatest { rows ->
                    val row = rows.firstOrNull { it.status == "ringing" }
                    if (row == null) lock.withLock {
                        if(dao.open().none { it.status=="ringing" }) { _pending.value = null; notifier.cancelIncoming() }
                    }
                    else {
                        while (currentCoroutineContext().isActive) {
                            val offer = lock.withLock { db.withTransaction {
                                val current = dao.get(row.id)?.takeIf { it.status == "ringing" }
                                if (current == null) null
                                else if (!valid(current) || audioBusy()) { finish(current, if (clock() >= current.expiresAt) "missed" else "cancelled"); null }
                                else IncomingCallOffer(current, requireNotNull(db.companions().get(current.companionId)))
                            }.also { _pending.value=it } }
                            if (offer == null) break
                            delay(500)
                        }
                    }
                }
            } catch (e: CancellationException) { ready.cancel(e); throw e
            } catch (e: Exception) { ready.completeExceptionally(e); _pending.value = null; notifier.cancelIncoming() }
        }
    }
    suspend fun restored() = ready.await()

    /** Called from opted-in private-chat/free-topic tools with the actual actor and conversation. */
    suspend fun request(conversationId: Long, companionId: Long, reason: String): IncomingCallEntity {
        ready.await()
        val words = IncomingCallRules.reason(reason)
        return lock.withLock {
            check(!paused) { "正在恢复数据，没有发起来电。" }
            check(visible() || notifier.incomingAllowed()) { "来电通知未开启，没有发起来电。" }
            val row = db.withTransaction {
                val ta = db.companions().get(companionId) ?: error("TA 已不存在。")
                val conversation = db.conversations().get(conversationId)?.takeIf { it.companionId == companionId }
                    ?: error("对话已变化，没有发起来电。")
                val now = clock()
                for (old in dao.open().filter { it.expiresAt <= now && it.status == "ringing" }) finish(old,"missed")
                val recent = dao.recent(companionId,now-IncomingCallRules.DAY_MS)
                IncomingCallRules.held(ta.incomingCallsEnabled,ta.freeTopicEnabled,
                    IncomingCallRules.quiet(ta,Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())),
                    audioBusy(),dao.open().isNotEmpty(),recent.firstOrNull()?.createdAt,recent.size,now,ta.incomingCallIntervalMinutes,ta.incomingCallDailyMax)?.let { error(it) }
                check(!db.conversations().idsFor(companionId).any { chat.isTyping(it) || (it != conversationId && chat.busy(it)) }) { "对方正在聊天，先不打扰。" }
                check(db.messages().lastUserFor(companionId) != null) { "还没有聊过，先等第一次聊天。" }
                configured(ta)
                val record = db.messages().insert(MessageEntity(conversationId=conversationId,role="call",
                    content=CallRecords.encode(CallRecord(incoming=true,outcome="ringing")),createdAt=now))
                db.conversations().touch(conversationId,now)
                IncomingCallEntity(UUID.randomUUID().toString(),companionId,ta.createdAt,conversationId,
                    conversation.createdAt,now,now+IncomingCallRules.RING_MS,words,record).also { dao.insert(it) }
            }
            try {
                val ta = requireNotNull(db.companions().get(companionId))
                if (notifier.incomingAllowed()) notifier.incoming(ta,row,settings.current().persistentIncomingAlerts)
                val expiry = OneTimeWorkRequestBuilder<IncomingCallExpiryWorker>()
                    .setInitialDelay((row.expiresAt-clock()).coerceAtLeast(0),TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("invitation" to row.id)).build()
                work.enqueueUniqueWork("incoming-${row.id}",ExistingWorkPolicy.REPLACE,expiry)
            } catch (e: Exception) {
                if (!visible()) { finish(row,"failed"); throw e }
            }
            dao.prune(clock()-7*IncomingCallRules.DAY_MS)
            row
        }
    }

    /** No microphone permission, service or player is touched on this path. */
    suspend fun dismiss(id: String, outcome: String = "declined") {
        ready.await()
        lock.withLock { dao.get(id)?.takeIf { it.status == "ringing" }?.let { finish(it,outcome) } }
    }
    suspend fun expire(id: String) {
        ready.await()
        lock.withLock { dao.get(id)?.takeIf { it.status == "ringing" && clock() >= it.expiresAt }?.let { finish(it,"missed") } }
    }
    fun cancelForConversation(id: Long) { scope.launch {
        ready.await(); lock.withLock { for(row in dao.open().filter { it.conversationId == id && it.status == "ringing" }) finish(row,"cancelled") }
    } }
    fun refresh() { scope.launch {
        ready.await(); lock.withLock { for(row in dao.open().filter { it.status == "ringing" }) if(!valid(row)) finish(row,"cancelled") }
    } }

    /** Only the resumed UI calls this, after the person's answer and microphone permission. */
    suspend fun accept(id: String): Boolean {
        ready.await()
        return lock.withLock {
            val row = dao.get(id) ?: run { problem.value="这通来电已结束。"; return@withLock false }
            if(row.status!="ringing") {
                if(row.status !in setOf("connecting","answered")) problem.value="这通来电已结束。"
                return@withLock false
            }
            if (!valid(row) || audioBusy()) {
                finish(row,if(audioBusy()) "busy" else "missed"); problem.value="这通来电已结束，或现在有另一通电话。"; return@withLock false
            }
            try { configured(requireNotNull(db.companions().get(row.companionId))) }
            catch (e: Exception) { finish(row,"failed"); problem.value=e.message ?: "通话设置还没配好。"; return@withLock false }
            // Claim before starting: duplicate buttons and stale notifications cannot dial twice.
            val claimed=db.withTransaction { if(valid(row) && !audioBusy()) dao.transition(id,"ringing","connecting") else 0 }
            if (claimed != 1) return@withLock false
            notifier.cancelIncoming(); _pending.value=null
            val connected = if(!visible() || paused) false else try { start(row) } catch (_: Exception) { false }
            if (!connected) { finish(row.copy(status="connecting"),"busy"); problem.value="暂时无法接通，请稍后再试。" }
            connected
        }
    }
    fun open(id: String, answer: Boolean) { scope.launch {
        ready.await()
        val row=dao.get(id)
        if(row?.status=="ringing" && valid(row)) { if(answer) answerRequested.value=id }
        else if(row?.status !in setOf("connecting","answered")) problem.value="这通来电已结束。"
    } }
    suspend fun pause() {
        paused=true; ready.await()
        lock.withLock { for(row in dao.open().filter { it.status=="ringing" }) finish(row,"cancelled") }
        stopAccepted()
    }
    fun resume() { paused=false }

    private suspend fun valid(row: IncomingCallEntity): Boolean {
        val now=clock()
        if(paused || now < row.createdAt || now >= row.expiresAt || row.recordId == null || db.messages().get(row.recordId) == null) return false
        val ta=db.companions().get(row.companionId) ?: return false
        val conversation=db.conversations().get(row.conversationId) ?: return false
        return ta.createdAt==row.companionCreatedAt && ta.incomingCallsEnabled && ta.freeTopicEnabled &&
            !IncomingCallRules.quiet(ta,Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())) &&
            conversation.companionId==ta.id && conversation.createdAt==row.conversationCreatedAt &&
            !chat.isTyping(row.conversationId) && (db.messages().lastUserFor(ta.id) ?: Long.MIN_VALUE) <= row.createdAt
    }
    private suspend fun configured(ta: CompanionEntity) {
        val s=settings.current(); val voice=TaVoices.effective(s,ta); val service=Speech.service(voice)
        check(s.voiceBaseUrl.isNotBlank() && s.voiceModel.isNotBlank() && !secrets.key(s.voiceBaseUrl).isNullOrBlank()) { "请先在发语音里配置转写服务与 Key。" }
        check(Speech.ready(voice) && service != null && !secrets.key(Speech.keyAddress(voice,service)).isNullOrBlank()) { "请先为这位 TA 配置声音和平台 Key。" }
        val model=ta.modelFor(heard=true)
        check(model.model.isNotBlank() && !secrets.key(model.baseUrl).isNullOrBlank()) { "请先配置这位 TA 的通话模型与 Key。" }
    }
    private suspend fun finish(row: IncomingCallEntity, status: String) = withContext(NonCancellable) {
        db.withTransaction {
            if(dao.transition(row.id,row.status,status)==1) row.recordId?.let { id ->
                db.messages().get(id)?.let { m -> CallRecords.decode(m.content)?.let { r ->
                    db.messages().setContent(id,CallRecords.encode(r.copy(endedAt=clock(),outcome=status)))
                } }
            }
        }
        if(_pending.value?.invitation?.id==row.id) _pending.value=null
        notifier.cancelIncoming()
        if(answerRequested.value==row.id) answerRequested.value=null
    }
}

class IncomingCallExpiryWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        inputData.getString("invitation")?.let { (applicationContext as CleosApp).container.incomingCalls.expire(it) }
        return Result.success()
    }
}
