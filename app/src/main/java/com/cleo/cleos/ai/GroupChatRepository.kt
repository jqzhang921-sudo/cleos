package com.cleo.cleos.ai

import androidx.room.withTransaction
import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

class GroupReplyTimeoutException : Exception("这位 TA 的回复超过 3 分钟了，可以单独重试；后面的成员会继续接话")

/** No tool loop or additional routing model. The caller keeps partial output if the stream fails. */
object GroupReplyEngine {
    const val REPLY_TIMEOUT_MS = 180_000L
    suspend fun round(slots: List<GroupMessageEntity>, only: Long?, begin: suspend (Long) -> Boolean,
        respond: suspend (GroupMessageEntity) -> Unit) {
        require(slots.size <= GroupChatRules.MAX_MEMBERS)
        for (slot in slots) {
            currentCoroutineContext().ensureActive()
            if (only != null && only != slot.id || slot.status == "sent") continue
            if (begin(slot.id)) respond(slot)
        }
    }
    suspend fun reply(events: Flow<ChatEvent>, timeoutMillis: Long = REPLY_TIMEOUT_MS,
        onStage: (GroupReplyStage) -> Unit = {}, onText: (String) -> Unit): String {
        require(timeoutMillis > 0)
        val text = StringBuilder()
        var stage = GroupReplyStage.WAITING
        fun progress(next: GroupReplyStage) {
            if (next != stage) { stage = next; onStage(next) }
        }
        // A relay may send heartbeats forever, keeping OkHttp's read timeout alive.
        // This deadline covers the whole member reply, including hidden reasoning and partial text.
        return withTimeoutOrNull(timeoutMillis) {
            events.collect { event ->
                currentCoroutineContext().ensureActive()
                when (event) {
                    is ChatEvent.Delta -> {
                        check(text.length + event.text.length <= GroupChatRules.MAX_REPLY) { "这段回复太长了，请重试" }
                        text.append(event.text)
                        if (text.isNotBlank()) progress(GroupReplyStage.ANSWERING)
                        onText(text.toString())
                    }
                    is ChatEvent.ToolCalls -> check(event.calls.isEmpty()) { "本版群聊暂未接入工具，这次没有执行操作" }
                    is ChatEvent.Reasoning -> if (stage != GroupReplyStage.ANSWERING && event.text.isNotBlank()) progress(GroupReplyStage.THINKING)
                }
            }
            currentCoroutineContext().ensureActive()
            text.toString().trim().also { check(it.isNotEmpty()) { "TA 没有返回文字，请重试" } }
        } ?: throw GroupReplyTimeoutException()
    }
}

/** A separate destination, with durable reply slots. Retrying a slot never resends a successful member. */
class GroupChatRepository(private val db: AppDatabase, private val settings: SettingsRepository,
    private val secrets: SecretStore, private val client: ChatClient, private val images: ImageStore,
    private val scope: CoroutineScope, private val groupTools: GroupTools? = null, private val diagnostic: (String) -> Unit = {},
    private val privateBusy: suspend (Long) -> Boolean = { false }) {
    private val dao = db.groups()
    private val gate = Mutex()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val membersInUse = ConcurrentHashMap<Long, Long>()
    private val _working = MutableStateFlow<Set<Long>>(emptySet())
    val working = _working.asStateFlow()
    private val _streaming = MutableStateFlow<Map<Long, String>>(emptyMap())
    val streaming = _streaming.asStateFlow()
    private val _progress = MutableStateFlow<Map<Long, GroupReplyProgress>>(emptyMap())
    val progress = _progress.asStateFlow()
    private val _generation = MutableStateFlow(0L)
    val generation = _generation.asStateFlow()
    @Volatile private var paused = false
    private val ready = scope.async { db.withTransaction { dao.recoverMessages(); dao.recoverTurns() } }
    fun busyMember(id: Long) = membersInUse.containsKey(id)

    suspend fun create(name: String, ids: List<Long>): Long = withContext(Dispatchers.IO) {
        ready.await()
        gate.withLock {
            check(!paused) { "正在恢复备份，请稍后再试" }
            val title = GroupChatRules.title(name)
            require(ids.size in 2..GroupChatRules.MAX_MEMBERS && ids.distinct().size == ids.size) { "请选择 2–4 位不同的 TA" }
            val tas = ids.map { db.companions().get(it) ?: error("有一位 TA 已不存在，请重新选择") }
            val copies = mutableListOf<String>()
            var saved = false
            try {
                val avatars = tas.map { ta -> ta.avatar?.takeIf { images.file(it).exists() }?.let {
                    MessageImageCopies.copyFile(images.dir, it).also(copies::add)
                } }
                withContext(NonCancellable) {
                    val id = db.withTransaction {
                        check(tas.all { db.companions().get(it.id) != null }) { "有一位 TA 已不存在" }
                        val now = System.currentTimeMillis()
                        val id = dao.insertGroup(ChatGroupEntity(title = title, createdAt = now, updatedAt = now))
                        dao.insertMembers(tas.mapIndexed { index, ta -> GroupMemberEntity(id, ta.id, index,
                            ta.name.ifBlank { "TA" }, avatars[index], ta.avatarEmoji) })
                        id
                    }
                    saved = true
                    id
                }
            } finally { if (!saved) images.delete(copies) }
        }
    }

    suspend fun rename(id: Long, name: String) { ready.await(); gate.withLock {
        check(!paused) { "正在恢复备份" }; dao.rename(id, GroupChatRules.title(name))
    } }
    suspend fun updateMembers(id: Long, selected: List<Long>) = withContext(Dispatchers.IO) {
        ready.await()
        gate.withLock {
            check(!paused) { "正在恢复备份，请稍后再试" }
            check(jobs[id] == null) { "这一轮还在回复，请先停止再调整成员" }
            val current = dao.members(id)
            check(dao.group(id) != null) { "这个群已不存在" }
            val tas = selected.map { db.companions().get(it) ?: error("有一位 TA 已不存在，请重新选择") }
            GroupMembershipRules.selected(selected, tas.map { it.id }.toSet())
            if (selected.toSet() == current.filter { it.active }.map { it.companionId }.toSet()) return@withLock
            val known = current.map { it.companionId }.toSet()
            val copies = mutableListOf<String>()
            var saved = false
            try {
                val added = tas.filterNot { it.id in known }.map { ta ->
                    val avatar = ta.avatar?.takeIf { images.file(it).exists() }?.let {
                        MessageImageCopies.copyFile(images.dir, it).also(copies::add)
                    }
                    GroupMemberEntity(id, ta.id, 0, ta.name.ifBlank { "TA" }, avatar, ta.avatarEmoji)
                }
                withContext(NonCancellable) {
                    db.withTransaction {
                        val group = dao.group(id) ?: error("这个群已不存在")
                        val available = selected.filter { db.companions().get(it) != null }.toSet()
                        GroupMembershipRules.selected(selected, available)
                        val roster = GroupMembershipRules.roster(current, selected, added)
                        dao.saveMembers(roster)
                        dao.membershipChanged(id, System.currentTimeMillis(),
                            GroupChatRules.encode(GroupMembershipRules.mentions(GroupChatRules.ids(group.mentions), roster, available)))
                    }
                    saved = true
                }
            } finally { if (!saved) images.delete(copies) }
        }
    }
    suspend fun saveDraft(id: Long, text: String, mentions: List<Long>, epoch: Long = generation.value) { ready.await(); gate.withLock {
        if (!paused && epoch == generation.value && dao.group(id) != null) {
            val members = dao.members(id)
            val available = members.filter { it.active && db.companions().get(it.companionId) != null }.map { it.companionId }.toSet()
            dao.draft(id, text.take(GroupChatRules.MAX_TEXT), GroupChatRules.encode(GroupMembershipRules.mentions(mentions, members, available)))
        }
    } }

    suspend fun saveMemory(groupId: Long, memoryId: Long?, title: String, content: String,
        sourceMessageId: Long? = null, epoch: Long = generation.value) {
        ready.await()
        gate.withLock {
            check(!paused && epoch == generation.value) { "群聊数据已变化，请重新打开小本本" }
            check(jobs[groupId] == null) { "这一轮还在回复，请先停止再整理小本本" }
            val name = GroupMemoryRules.title(title)
            val text = GroupMemoryRules.content(content)
            db.withTransaction {
                check(dao.group(groupId) != null) { "这个群已不存在" }
                val now = System.currentTimeMillis()
                if (memoryId != null) {
                    check(dao.memory(groupId, memoryId) != null) { "这条记录已不存在" }
                    check(dao.editMemory(groupId, memoryId, name, text, now) == 1) { "这条记录已不存在" }
                } else {
                    val notes = dao.memories(groupId)
                    require(notes.size < GroupMemoryRules.MAX_NOTES) { "每个群最多记 ${GroupMemoryRules.MAX_NOTES} 条，可以先整理旧记录" }
                    sourceMessageId?.let { source ->
                        GroupMemoryRules.source(groupId, dao.message(source) ?: error("原消息已不存在"))
                        require(notes.none { it.sourceMessageId == source }) { "这条消息已经记下了，可以在小本本里修改" }
                    }
                    dao.insertMemory(GroupMemoryEntity(groupId = groupId, title = name, content = text,
                        createdAt = now, updatedAt = now, sourceMessageId = sourceMessageId,
                        sourceKind = if (sourceMessageId == null) "manual" else "message"))
                }
            }
        }
    }
    suspend fun deleteMemory(groupId: Long, memoryId: Long, epoch: Long = generation.value) {
        ready.await()
        gate.withLock {
            check(!paused && epoch == generation.value) { "群聊数据已变化，请重新打开小本本" }
            check(jobs[groupId] == null) { "这一轮还在回复，请先停止再整理小本本" }
            dao.deleteMemory(groupId, memoryId)
        }
    }

    suspend fun send(id: Long, text: String, mentions: List<Long>) {
        ready.await()
        gate.withLock {
            check(!paused) { "正在恢复备份，请稍后再试" }
            check(jobs[id] == null) { "这一轮还在回复，可以先停止" }
            val content = GroupChatRules.text(text)
            withContext(NonCancellable) {
            val turnId = db.withTransaction {
                val group = dao.group(id) ?: error("这个群已不存在")
                val members = dao.members(id)
                val active = members.filter { it.active && db.companions().get(it.companionId) != null }
                val pick = GroupChatRules.select(active.map { it.companionId }, mentions, group.nextSpeaker)
                val now = System.currentTimeMillis()
                val userId = dao.insertMessage(GroupMessageEntity(groupId = id, role = "user", content = content,
                    createdAt = now, speakerName = settings.current().userName.ifBlank { "我" }, mentions = GroupChatRules.encode(mentions)))
                val turn = dao.insertTurn(GroupTurnEntity(groupId = id, userMessageId = userId,
                    targets = GroupChatRules.encode(pick.targets), createdAt = now))
                for (target in pick.targets) {
                    val member = members.first { it.companionId == target }
                    dao.insertMessage(GroupMessageEntity(groupId = id, role = "assistant", content = "", createdAt = now,
                        speakerId = target, speakerName = member.name, speakerAvatar = member.avatar,
                        speakerEmoji = member.emoji, turnId = turn, status = "pending"))
                }
                dao.advance(id, now, pick.next)
                turn
            }
            start(id, turnId, null)
            }
        }
    }

    suspend fun retry(messageId: Long) {
        ready.await()
        gate.withLock {
            check(!paused) { "正在恢复备份" }
            val message = dao.message(messageId) ?: error("这条消息已不存在")
            check(jobs[message.groupId] == null) { "这一轮还在回复" }
            require(GroupChatRules.retryable(message)) { "这位 TA 已经回复，不会重复发送" }
            val turn = dao.latestTurn(message.groupId)
            require(turn != null && message.turnId == turn.id) { "已经开始了新话题，这条旧回复不再重试" }
            check(dao.members(message.groupId).any { it.companionId == message.speakerId && it.active } &&
                message.speakerId?.let { db.companions().get(it) } != null) { "这位 TA 已不在群里，不能重试" }
            withContext(NonCancellable) {
                dao.turnStatus(turn.id, "running")
                start(message.groupId, turn.id, messageId)
            }
        }
    }

    private fun start(groupId: Long, turnId: Long, only: Long?) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                GroupReplyEngine.round(dao.replies(turnId), only, { id ->
                    withContext(NonCancellable) {
                        val old = dao.message(id)
                        val began = dao.beginReply(id) == 1
                        if (began) images.delete(MessageImages.decode(old?.images).map { it.file })
                        began
                    }
                }, ::respond)
                db.withTransaction {
                    if (dao.turn(turnId)?.status == "running") dao.turnStatus(turnId,
                        if (dao.replies(turnId).all { it.status == "sent" }) "completed" else "failed")
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { db.withTransaction {
                    dao.interruptMessages(groupId, "cancelled", "这轮已停止，可以重试")
                    dao.interruptTurns(groupId, "cancelled")
                } }
                throw e
            } catch (_: Exception) {
                withContext(NonCancellable) { db.withTransaction {
                    dao.interruptMessages(groupId, "failed", "这轮没有完成，可以重试")
                    dao.interruptTurns(groupId, "failed")
                } }
            } finally {
                _working.update { it - groupId }
                jobs.remove(groupId, currentCoroutineContext().job)
            }
        }
        jobs[groupId] = job
        _working.update { it + groupId }
        job.start()
    }

    private suspend fun respond(slot: GroupMessageEntity) {
        var partial = ""
        var model = "[unresolved]"
        var sentPhotos = emptyList<MessageImage>()
        val deliveredIds = mutableSetOf<String>()
        val target = requireNotNull(slot.speakerId)
        var leased = false
        try {
            check(membersInUse.putIfAbsent(target, slot.groupId) == null) { "${slot.speakerName}正在另一个群回复，稍后可以重试" }
            leased = true
            check(!privateBusy(target)) { "${slot.speakerName}正在私聊或通话，稍后可以重试" }
            check(dao.members(slot.groupId).any { it.companionId == target && it.active }) { "这位 TA 已不在群里，不能继续回复" }
            val ta = db.companions().get(target) ?: error("这位 TA 已离开，不能继续回复")
            model = com.cleo.cleos.diagnostics.ToolDiagnosticStore.label(ta.apiModel)
            val key = secrets.key(ta.apiBaseUrl)?.takeIf { it.isNotBlank() } ?: error("请先为${slot.speakerName}保存模型和 Key")
            val group = dao.group(slot.groupId) ?: error("这个群已不存在")
            val turn = dao.turn(requireNotNull(slot.turnId)) ?: error("这一轮已不存在")
            val s = settings.current()
            val history = dao.history(slot.groupId, slot.id, s.historySize.coerceIn(5, 100)).reversed()
            val roster = dao.members(slot.groupId).map { member ->
                if (member.active && db.companions().get(member.companionId) == null) member.copy(active = false) else member
            }
            val query = dao.message(turn.userMessageId)?.content.orEmpty()
            val notes = GroupMemoryRules.selected(slot.groupId, dao.memories(slot.groupId), query)
            val sources = notes.mapNotNull { it.sourceMessageId }.distinct().mapNotNull { dao.message(it) }
            val specs = if (groupTools != null) GroupToolRules.offered(s) else emptyList()
            val messages = GroupChatRules.request(group, roster, ta.copy(name = slot.speakerName), history,
                s.userName, GroupChatRules.ids(dao.message(turn.userMessageId)?.mentions ?: "[]"), ZonedDateTime.now(),
                GroupMemoryRules.context(slot.groupId, notes, query, sources), specs)
            _progress.update { it + (slot.id to GroupReplyProgress(System.nanoTime())) }
            val text = GroupToolReply.reply(messages, specs, ta.deepThinking,
                stream = { context, offered, thinking -> client.stream(ApiEndpoint(ta.apiBaseUrl, key, ta.apiModel), context, offered, thinking) },
                execute = { call ->
                    currentCoroutineContext().ensureActive()
                    check(dao.members(slot.groupId).any { it.companionId == target && it.active } && db.companions().get(target) != null) { "这位 TA 已不在群里" }
                    val photoId = if (call.name == "share_photo") ToolArgs.parse(call.arguments)?.let { ToolArgs.text(it, "id") } else null
                    if (photoId != null && photoId in deliveredIds) ToolOutcome("本轮这张图库照片已经在本群保存，不再重复发送。", "这张图已发过")
                    else if (call.name == "share_photo" && sentPhotos.size >= GroupPhotoRules.MAX_IMAGES)
                        ToolOutcome("本次回复最多 3 张图片，这张没有发出。", "图片已达上限")
                    else {
                        val outcome = requireNotNull(groupTools).run(call, slot.groupId, target)
                        val photo = outcome.photo
                        if (photo == null) outcome else GroupPhotoDelivery.deliver(outcome, GroupPhotoRules.MAX_IMAGES - sentPhotos.size,
                            save = { bytes, mime ->
                                val stored = images.import(bytes, mime, McpImages.MAX_EDGE)
                                MessageImage(stored.file, stored.width, stored.height)
                            }, commit = { added ->
                                db.withTransaction {
                                    val current = db.companions().get(target)
                                    check(current != null && current.apiBaseUrl == ta.apiBaseUrl && current.apiModel == ta.apiModel && current.persona == ta.persona && current.deepThinking == ta.deepThinking) { "TA 的配置改变了，没有发送图片" }
                                    val next = sentPhotos + added
                                    check(GroupPhotoRules.valid(MessageImages.encode(next))) { "图片记录不完整" }
                                    check(dao.saveImages(slot.id, slot.groupId, target, turn.id, requireNotNull(MessageImages.encode(next))) == 1) { "这一轮已结束，没有发送图片" }
                                }
                                sentPhotos = sentPhotos + added
                                deliveredIds += photo.candidate.id
                            }, remove = { files -> images.delete(files) })
                    }
                }, onTrace = { trace ->
                    currentCoroutineContext().ensureActive()
                    check(dao.saveToolTrace(slot.id, slot.groupId, turn.id, GroupToolRules.encode(trace)) == 1) { "这一轮已结束，没有继续执行操作" }
                    diagnostic("event=group_tools calls=${trace.records.size} returned=${trace.records.count { it.state == "returned" }} model=$model")
                },
                hasPhotos = { sentPhotos.isNotEmpty() },
                onStage = { stage -> _progress.update { live -> live[slot.id]?.let { live + (slot.id to it.copy(stage = stage)) } ?: live } }) {
                partial = it
                _streaming.update { live -> live + (slot.id to it) }
            }
            currentCoroutineContext().ensureActive()
            db.withTransaction {
                val current = db.companions().get(target)
                check(dao.members(slot.groupId).any { it.companionId == target && it.active }) { "这位 TA 已不在群里，不能继续回复" }
                check(current != null && current.apiBaseUrl == ta.apiBaseUrl && current.apiModel == ta.apiModel && current.persona == ta.persona && current.deepThinking == ta.deepThinking) { "TA 的配置改变了，请重试" }
                if (dao.finishReply(slot.id, turn.id, text, "sent", null) == 1) dao.touch(slot.groupId, System.currentTimeMillis())
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.finishReply(slot.id, requireNotNull(slot.turnId), partial, "cancelled", "回复已停止，可以重试") }
            throw e
        } catch (e: Exception) {
            if (e is GroupReplyTimeoutException) diagnostic("event=stream_failure reason=group_timeout model=$model")
            dao.finishReply(slot.id, requireNotNull(slot.turnId), partial, "failed", e.message?.take(500) ?: "回复没有完成，可以重试")
        } finally {
            if (leased) membersInUse.remove(target, slot.groupId)
            _streaming.update { it - slot.id }
            _progress.update { it - slot.id }
        }
    }

    suspend fun stop(id: Long) {
        ready.await()
        gate.withLock {
            jobs[id]?.cancelAndJoin()
            db.withTransaction { dao.interruptMessages(id, "cancelled", "这轮已停止，可以重试"); dao.interruptTurns(id, "cancelled") }
        }
    }
    suspend fun delete(id: Long) {
        ready.await()
        gate.withLock {
            check(!paused) { "正在恢复备份" }
            jobs[id]?.cancelAndJoin()
            val files = dao.members(id).mapNotNull { it.avatar } + dao.allMessages().filter { it.groupId == id }.flatMap { MessageImages.decode(it.images).map { p -> p.file } }
            withContext(NonCancellable) {
                dao.delete(id); images.delete(files)
                if (settings.currentGroup.first() == id) settings.setCurrentGroup(null)
            }
        }
    }
    suspend fun stopAll() {
        ready.await()
        gate.withLock { paused = true; _generation.update { it + 1 }; jobs.values.toList().forEach { it.cancelAndJoin() } }
    }
    fun resume() { paused = false; _generation.update { it + 1 } }
}
