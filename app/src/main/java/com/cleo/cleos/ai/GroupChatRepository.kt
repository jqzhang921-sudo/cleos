package com.cleo.cleos.ai

import androidx.room.withTransaction
import android.net.Uri
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
    private val transcribeVoice: suspend (String, String, java.io.File) -> String = { _, _, _ -> error("未配置转文字服务") },
    private val synthesizeVoice: suspend (AppSettings, String) -> Pair<ByteArray, String?> = { _, _ -> error("未配置声音服务") },
    private val storeVoice: suspend (String, ByteArray, String?, String) -> MessageAudio = { _, _, _, _ -> error("无法保存语音") },
    private val privateBusy: suspend (Long) -> Boolean = { false }) {
    private val dao = db.groups()
    private val gate = Mutex()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val voiceJobs = ConcurrentHashMap<Long, Job>()
    private val _transcribing = MutableStateFlow<Set<Long>>(emptySet())
    val transcribing = _transcribing.asStateFlow()
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

    internal data class CallLease(val group: ChatGroupEntity, val members: List<GroupMemberEntity>,
        val tas: List<CompanionEntity>, val owner: Job, val epoch: Long)

    /** The session is a normal group job, so stop/delete/restore wait for all call writes to finish. */
    internal suspend fun reserveCall(id: Long, selected: List<Long>, owner: Job, expectedCreatedAt: Long): CallLease {
        ready.await()
        return gate.withLock {
            check(!paused && jobs[id] == null && !voiceJobs.containsKey(id)) { "群聊正在忙，请等这一轮结束再打电话" }
            val group = dao.group(id) ?: error("这个群已不存在")
            check(group.createdAt == expectedCreatedAt) { "群聊数据已变化，请重新打开" }
            val roster = dao.members(id)
            val available = roster.filter { it.active && db.companions().get(it.companionId) != null }.map { it.companionId }.toSet()
            GroupCallRules.selected(selected, available)
            val tas = selected.map { db.companions().get(it) ?: error("这位 TA 已不存在") }
            check(selected.none { privateBusy(it) }) { "有一位 TA 正在私聊或通话，请稍后再试" }
            val taken = mutableListOf<Long>()
            try {
                for (target in selected) {
                    check(membersInUse.putIfAbsent(target, id) == null) { "有一位 TA 正在其他群里回复，请稍后再试" }
                    taken += target
                }
                currentCoroutineContext().ensureActive()
                jobs[id] = owner
                _working.update { it + id }
                CallLease(group, selected.map { target -> roster.first { it.companionId == target } }, tas, owner, generation.value)
            } catch (e: Exception) { taken.forEach { membersInUse.remove(it, id) }; throw e }
        }
    }

    private fun validCall(lease: CallLease) = jobs[lease.group.id] === lease.owner && lease.owner.isActive && lease.epoch == generation.value && !paused

    private fun requireCall(lease: CallLease) {
        lease.owner.ensureActive()
        if (!validCall(lease)) {
            lease.owner.cancel()
            throw CancellationException("这次通话已结束")
        }
    }

    /** No private-memory query at all until this member has an explicit, valid local grant. */
    private suspend fun privateMemories(groupId: Long, target: Long): List<MemoryEntity> = db.withTransaction {
        GroupPrivateMemories.load(groupId, target, dao.members(groupId), enabled = ToolGroup.Memory in settings.current().tools) {
            owner, ids -> db.memories().selectedFor(owner, ids)
        }
    }

    internal suspend fun callRound(lease: CallLease, text: String, order: List<Long>): List<GroupMessageEntity> {
        requireCall(lease)
        GroupCallRules.selected(order, lease.members.map { it.companionId }.toSet())
        return db.withTransaction {
            requireCall(lease)
            check(dao.group(lease.group.id)?.createdAt == lease.group.createdAt) { "群聊数据已变化" }
            val now = System.currentTimeMillis()
            val user = dao.insertMessage(GroupMessageEntity(groupId = lease.group.id, role = "user",
                content = GroupCallRules.userWords(text), createdAt = now, speakerName = settings.current().userName.ifBlank { "我" },
                mentions = GroupChatRules.encode(order)))
            val turn = dao.insertTurn(GroupTurnEntity(groupId = lease.group.id, userMessageId = user,
                targets = GroupChatRules.encode(order), createdAt = now))
            order.map { target ->
                val member = lease.members.first { it.companionId == target }
                val row = GroupMessageEntity(groupId = lease.group.id, role = "assistant", content = "", createdAt = now,
                    speakerId = target, speakerName = member.name, speakerAvatar = member.avatar, speakerEmoji = member.emoji,
                    turnId = turn, status = "pending")
                row.copy(id = dao.insertMessage(row))
            }.also { dao.touch(lease.group.id, now) }
        }
    }

    internal suspend fun callReply(lease: CallLease, slot: GroupMessageEntity): Flow<ChatEvent> {
        requireCall(lease)
        check(slot.groupId == lease.group.id) { "通话回复位置不属于本群" }
        val target = lease.tas.first { it.id == slot.speakerId }
        check(db.companions().get(target.id)?.createdAt == target.createdAt &&
            dao.members(slot.groupId).any { it.companionId == target.id && it.active }) { "这位 TA 已不在本次通话中" }
        check(dao.beginReply(slot.id) == 1) { "这条通话回复已处理" }
        val s = settings.current()
        val model = target.modelFor(heard = true)
        val key = secrets.key(model.baseUrl)?.takeIf { it.isNotBlank() } ?: error("请为这位 TA 配好通话模型和 Key")
        val history = dao.history(slot.groupId, slot.id, s.historySize.coerceIn(5, 100)).reversed()
        val notes = GroupMemoryRules.selected(slot.groupId, dao.memories(slot.groupId), history.lastOrNull { it.role == "user" }?.content.orEmpty())
        val sources = notes.mapNotNull { it.sourceMessageId }.distinct().mapNotNull { dao.message(it) }
        val messages = GroupCallRules.request(lease.group, dao.members(slot.groupId), lease.members.map { it.companionId },
            target.copy(name = slot.speakerName), history, s.userName,
            GroupMemoryRules.context(slot.groupId, notes, history.lastOrNull { it.role == "user" }?.content.orEmpty(), sources), ZonedDateTime.now(),
            stickers = StickerBook(db.stickers().all().filter { images.file(it.file).isFile }),
            privateMemories = privateMemories(slot.groupId, target.id))
        return client.stream(ApiEndpoint(model.baseUrl, key, model.model), messages, emptyList(), false)
    }

    internal suspend fun saveCallReply(lease: CallLease, slot: GroupMessageEntity, text: String, ok: Boolean, cancelled: Boolean) {
        // No gate here: restore/delete hold it while cancelling and awaiting the owner job.
        if (jobs[lease.group.id] !== lease.owner) return
        db.withTransaction {
            if (dao.message(slot.id)?.status == "pending") dao.beginReply(slot.id)
            dao.finishReply(slot.id, requireNotNull(slot.turnId), text,
                if (text.isNotBlank() || ok) "sent" else if (cancelled) "cancelled" else "failed",
                if (ok) null else if (cancelled) "通话这轮被打断，未播放的内容没有发出" else "通话这段声音未完整完成")
        }
    }

    internal suspend fun callSpoken(lease: CallLease, slot: GroupMessageEntity, text: String) {
        check(jobs[lease.group.id] === lease.owner) { "这次通话已结束" }
        check(dao.saveCallTranscript(slot.id, slot.groupId, requireNotNull(slot.speakerId), requireNotNull(slot.turnId), text) == 1) {
            "通话记录已变化，请重新开始"
        }
    }

    internal suspend fun finishCallRound(lease: CallLease, slots: List<GroupMessageEntity>) {
        if (jobs[lease.group.id] !== lease.owner || slots.isEmpty()) return
        db.withTransaction {
            for (slot in slots) if (dao.message(slot.id)?.status in setOf("pending", "running")) {
                if (dao.message(slot.id)?.status == "pending") dao.beginReply(slot.id)
                dao.finishReply(slot.id, requireNotNull(slot.turnId), "", "cancelled", "通话这轮已停止")
            }
            val turn = requireNotNull(slots.first().turnId)
            dao.turnStatus(turn, if (dao.replies(turn).all { it.status == "sent" }) "completed" else "cancelled")
        }
    }

    internal fun releaseCall(lease: CallLease) {
        lease.members.forEach { membersInUse.remove(it.companionId, lease.group.id) }
        if (jobs.remove(lease.group.id, lease.owner)) _working.update { if (jobs[lease.group.id] == null) it - lease.group.id else it }
    }

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
    suspend fun saveOptions(id: Long, name: String, allowed: Boolean, epoch: Long = generation.value) {
        ready.await()
        gate.withLock {
            check(!paused && epoch == generation.value) { "群聊数据已变化，请重新打开" }
            check(jobs[id] == null) { "这一轮还在回复，请先停止再调整" }
            check(dao.options(id, GroupChatRules.title(name), allowed) == 1) { "这个群已不存在" }
        }
    }
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
    suspend fun saveMemorySharing(groupId: Long, companionId: Long, enabled: Boolean,
        grants: List<GroupMemoryGrant>, epoch: Long = generation.value) = withContext(Dispatchers.IO) {
        ready.await()
        gate.withLock {
            check(!paused && epoch == generation.value) { "群聊数据已变化，请重新打开" }
            check(jobs[groupId] == null) { "先停止回复或通话，再调整记忆共享" }
            db.withTransaction {
                check(dao.group(groupId) != null && db.companions().get(companionId) != null &&
                    dao.members(groupId).any { it.companionId == companionId && it.active }) { "这位 TA 已不在本群" }
                val encoded = if (!enabled) "[]" else {
                    require(grants.isNotEmpty() && grants.size <= GroupPrivateMemories.MAX_SELECTED) { "请先勾选允许带进群的记忆" }
                    GroupPrivateMemories.checked(companionId, grants, db.memories().selectedFor(companionId, grants.map { it.id }))
                }
                check(dao.memorySharing(groupId, companionId, enabled, encoded) == 1) { "群成员已变化，请重新打开" }
            }
        }
    }
    suspend fun saveDraft(id: Long, text: String, mentions: List<Long>, epoch: Long = generation.value) { ready.await(); gate.withLock {
        if (!paused && epoch == generation.value && dao.group(id) != null) {
            val members = dao.members(id)
            val available = members.filter { it.active && db.companions().get(it.companionId) != null }.map { it.companionId }.toSet()
            val words = if (dao.group(id)?.draftAudio != null) "" else text.take(GroupChatRules.MAX_TEXT)
            dao.draft(id, words, GroupChatRules.encode(GroupMembershipRules.mentions(mentions, members, available)))
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

    /** Reading a preview does not stop replies, remove files, or mutate history. */
    suspend fun previewMessageDeletion(groupId: Long, messageId: Long, wholeRound: Boolean, epoch: Long,
        expectedCreatedAt: Long): GroupMessageDeletion {
        ready.await()
        return gate.withLock {
            deletionReady(groupId, epoch)
            db.withTransaction { deletionPlan(groupId, messageId, wholeRound, epoch, expectedCreatedAt) }
        }
    }

    private fun deletionReady(groupId: Long, epoch: Long) {
        check(!paused && epoch == generation.value) { "群聊数据已变化，请重新打开删除确认" }
        check(jobs[groupId] == null && !voiceJobs.containsKey(groupId)) { "群聊还在回复或转写，请先结束后再删除" }
    }

    private suspend fun deletionPlan(groupId: Long, messageId: Long, wholeRound: Boolean, epoch: Long,
        expectedCreatedAt: Long): GroupMessageDeletion {
        val group = dao.group(groupId)?.takeIf { it.createdAt == expectedCreatedAt } ?: error("这个群已变化，请重新打开")
        val selected = dao.message(messageId)?.takeIf { it.groupId == groupId } ?: error("这条消息已不存在")
        val anchor = if (selected.role == "user") selected else {
            val turn = selected.turnId?.let { dao.turn(it) }?.takeIf { it.groupId == groupId } ?: error("这一轮已不存在")
            dao.message(turn.userMessageId)?.takeIf { it.groupId == groupId } ?: error("这一轮的起始消息已不存在")
        }
        val turns = dao.turnsForMessage(groupId, anchor.id)
        val replies = dao.deletionReplies(groupId, turns.map { it.id })
        val candidates = if (wholeRound || selected.role == "user") listOf(anchor.id) + replies.map { it.id } else listOf(selected.id)
        return GroupDeletionRules.plan(group, epoch, selected, anchor, turns, replies, wholeRound,
            dao.notesForMessages(groupId, candidates))
    }

    suspend fun deleteMessages(approved: GroupMessageDeletion) {
        ready.await()
        gate.withLock {
            deletionReady(approved.groupId, approved.epoch)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                db.withTransaction {
                    val current = deletionPlan(approved.groupId, approved.selected.id, approved.wholeRound,
                        approved.epoch, approved.groupCreatedAt)
                    GroupDeletionRules.requireCurrent(approved, current)
                    check(dao.deleteMessages(approved.groupId, approved.removed.map { it.id }) == approved.removed.size)
                    check(dao.deleteTurns(approved.groupId, approved.removeTurns) == approved.removeTurns.size)
                    for (turn in approved.updateTurns)
                        check(dao.afterMessageDeletion(approved.groupId, turn.id, turn.targets, turn.status) == 1)
                    // FK SET NULL keeps curated notes while detaching their removed source.
                }
                // The messages are committed. If a reference check fails, retain files rather than
                // reporting the deletion as failed or risking an attachment still used elsewhere.
                val unused = runCatching {
                    val s = settings.current()
                    val settingsFiles = (s.bubblePresets.flatMap { it.decoration.imageFiles() } + s.bubbleDecoration.imageFiles() +
                        listOfNotNull(s.wallpaper, s.feedCover, s.userAvatar)).toSet()
                    approved.files.filter { it !in settingsFiles && !dao.mediaReferenced(it) }
                }.getOrDefault(emptyList())
                withContext(Dispatchers.IO) { images.delete(unused) }
            }
        }
    }

    suspend fun stageVoice(id: Long, clip: MessageAudio, epoch: Long = generation.value) {
        var saved = false
        try {
            ready.await()
            gate.withLock {
                check(!paused && epoch == generation.value) { "群聊数据已变化，请重新录制" }
                val group = dao.group(id) ?: error("这个群已不存在")
                require(group.draftAudio == null && group.draftImages == null && group.draft.isBlank()) { "先发送或移除当前草稿，再录语音" }
                check(dao.allMessages().none { MessageAudios.decode(it.audio)?.file == clip.file }) { "这段语音已经发送过" }
                GroupVoiceRules.requireFile(clip, id, images.file(clip.file))
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    check(dao.draftAudio(id, null, GroupVoiceRules.encode(GroupVoiceDraft(clip.file, clip.ms))) == 1)
                    saved = true
                }
                startVoice(id, clip.file, epoch)
            }
        } finally {
            if (!saved && GroupVoiceRules.valid(clip, id)) withContext(NonCancellable) {
                val referenced = GroupVoiceRules.draft(dao.group(id)?.draftAudio, id)?.file == clip.file ||
                    dao.allMessages().any { MessageAudios.decode(it.audio)?.file == clip.file }
                if (!referenced) images.delete(listOf(clip.file))
            }
        }
    }
    suspend fun retryVoice(id: Long, file: String, epoch: Long = generation.value) {
        ready.await()
        gate.withLock {
            check(!paused && epoch == generation.value) { "群聊数据已变化，请重新打开" }
            val draft = GroupVoiceRules.checkedDraft(dao.group(id)?.draftAudio, id) ?: error("语音草稿已不存在")
            require(draft.file == file && draft.text.isBlank()) { "语音草稿已变化，请重新打开" }
            check(!voiceJobs.containsKey(id)) { "正在转文字，请稍等" }
            startVoice(id, file, epoch)
        }
    }
    private fun startVoice(id: Long, file: String, epoch: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val before = dao.group(id)?.draftAudio ?: return@launch
                val draft = GroupVoiceRules.checkedDraft(before, id) ?: return@launch
                check(draft.file == file)
                GroupVoiceRules.requireFile(draft.audio(), id, images.file(file))
                val s = settings.current()
                check(s.voiceBaseUrl.isNotBlank() && s.voiceModel.isNotBlank()) { "先在设置的「发语音」里配置转文字服务" }
                val text = GroupVoiceTranscription.run { transcribeVoice(s.voiceBaseUrl, s.voiceModel, images.file(file)) }
                currentCoroutineContext().ensureActive()
                gate.withLock {
                    check(!paused && epoch == generation.value)
                    val current = settings.current()
                    check(current.voiceBaseUrl == s.voiceBaseUrl && current.voiceModel == s.voiceModel) { "转文字配置已变化，请重试" }
                    dao.draftAudio(id, before, GroupVoiceRules.encode(draft.copy(text = text, error = null)))
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                gate.withLock {
                    if (!paused && epoch == generation.value) {
                        val before = dao.group(id)?.draftAudio
                        val draft = GroupVoiceRules.draft(before, id)
                        if (draft?.file == file && draft.text.isBlank()) dao.draftAudio(id, before,
                            GroupVoiceRules.encode(draft.copy(error = GroupVoiceRules.failure((e as? ChatException)?.status))))
                    }
                }
            } finally {
                if (voiceJobs.remove(id, currentCoroutineContext()[Job])) _transcribing.update { it - id }
            }
        }
        if (voiceJobs.putIfAbsent(id, job) != null) { job.cancel(); error("正在转文字，请稍等") }
        _transcribing.update { it + id }
        job.start()
    }
    suspend fun editVoice(id: Long, file: String, text: String, epoch: Long = generation.value) {
        ready.await()
        gate.withLock {
            if (paused || epoch != generation.value || voiceJobs.containsKey(id)) return@withLock
            require(text.length <= GroupChatRules.MAX_TEXT)
            val before = dao.group(id)?.draftAudio
            val draft = GroupVoiceRules.checkedDraft(before, id) ?: return@withLock
            if (draft.file != file) return@withLock
            dao.draftAudio(id, before, GroupVoiceRules.encode(draft.copy(text = text, error = null)))
        }
    }
    suspend fun removeVoice(id: Long, file: String, epoch: Long = generation.value) {
        ready.await()
        gate.withLock {
            check(!paused && epoch == generation.value) { "群聊数据已变化，请重新打开" }
            val before = dao.group(id)?.draftAudio
            val draft = GroupVoiceRules.checkedDraft(before, id) ?: error("语音草稿已不存在")
            require(draft.file == file) { "语音草稿已变化" }
            voiceJobs[id]?.cancelAndJoin()
            withContext(NonCancellable) {
                check(dao.draftAudio(id, before, null) == 1)
                images.delete(listOf(file))
            }
        }
    }
    suspend fun attachImages(id: Long, uris: List<Uri>, epoch: Long = generation.value) {
        ready.await()
        gate.withLock {
            check(!paused && epoch == generation.value) { "群聊数据已变化，请重新选择图片" }
            val group = dao.group(id) ?: error("这个群已不存在")
            require(group.draftAudio == null) { "先发送或移除语音草稿，再选择图片" }
            val before = GroupUploadRules.checked(group.draftImages, id)
            require(uris.isNotEmpty() && before.size + uris.size <= GroupUploadRules.MAX_IMAGES) { "每条消息最多 3 张图片，请先移除一些" }
            val created = mutableListOf<MessageImage>()
            var saved = false
            try {
                for (uri in uris) {
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        val stored = images.import(uri, McpImages.MAX_EDGE, GroupUploadRules.prefix(id))
                        created += MessageImage(stored.file, stored.width, stored.height, GroupUploadRules.SOURCE)
                    }
                }
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    check(!paused && epoch == generation.value) { "群聊数据已变化，请重新选择图片" }
                    check(dao.draftImages(id, MessageImages.encode(before + created)) == 1) { "这个群已不存在" }
                    saved = true
                }
            } finally { if (!saved) images.delete(created.map { it.file }) }
        }
    }
    suspend fun removeDraftImage(id: Long, file: String, epoch: Long = generation.value) {
        ready.await()
        gate.withLock {
            check(!paused && epoch == generation.value) { "群聊数据已变化，请重新打开" }
            val group = dao.group(id) ?: error("这个群已不存在")
            val pictures = GroupUploadRules.checked(group.draftImages, id)
            require(pictures.any { it.file == file }) { "这张图片已不在草稿里" }
            withContext(NonCancellable) {
                check(dao.draftImages(id, MessageImages.encode(pictures.filterNot { it.file == file })) == 1)
                images.delete(listOf(file))
            }
        }
    }
    suspend fun send(id: Long, text: String, mentions: List<Long>, voiceText: String? = null, voiceFile: String? = null) {
        enqueue(id, text, mentions, voiceText, voiceFile, null, generation.value)
    }

    /** Like private chat, a tapped sticker goes alone; the text/photo/voice draft stays untouched. */
    suspend fun sendSticker(id: Long, stickerId: Long, mentions: List<Long>, epoch: Long = generation.value) {
        enqueue(id, "", mentions, null, null, stickerId, epoch)
    }

    private suspend fun enqueue(id: Long, text: String, mentions: List<Long>, voiceText: String?, voiceFile: String?, stickerId: Long?, epoch: Long) {
        ready.await()
        gate.withLock {
            check(!paused) { "正在恢复备份，请稍后再试" }
            check(epoch == generation.value) { "群聊数据已变化，请重新打开" }
            check(jobs[id] == null) { "这一轮还在回复，可以先停止" }
            check(!voiceJobs.containsKey(id)) { "正在转文字，请稍等" }
            withContext(NonCancellable) {
            val turnId = db.withTransaction {
                val group = dao.group(id) ?: error("这个群已不存在")
                val sticker = stickerId?.let { sid ->
                    val saved = db.stickers().get(sid) ?: error("这张表情包已不在图库里")
                    check(images.file(saved.file).isFile) { "这张表情包的图片读不到，请重新添加" }
                    saved
                }
                val uploads = if (sticker != null) emptyList() else GroupUploadRules.checked(group.draftImages, id)
                val voice = if (sticker != null) null else GroupVoiceRules.checkedDraft(group.draftAudio, id)
                require(voice == null || voice.file == voiceFile && text.isBlank() && uploads.isEmpty()) { "语音草稿已变化，请重新打开后发送" }
                if (voice != null) GroupVoiceRules.requireFile(voice.audio(), id, images.file(voice.file))
                check(uploads.all { images.file(it.file).isFile }) { "草稿中的图片读不到，请移除后重新选择" }
                val content = if (sticker != null) StickerText.token(sticker.name)
                    else GroupChatRules.text(if (voice != null) voiceText ?: voice.text else text, uploads.isNotEmpty())
                val members = dao.members(id)
                val active = members.filter { it.active && db.companions().get(it.companionId) != null }
                val pick = GroupChatRules.select(active.map { it.companionId }, mentions, group.nextSpeaker)
                val now = System.currentTimeMillis()
                val userId = dao.insertMessage(GroupMessageEntity(groupId = id, role = "user", content = content,
                    createdAt = now, speakerName = settings.current().userName.ifBlank { "我" }, mentions = GroupChatRules.encode(mentions),
                    images = MessageImages.encode(uploads), audio = voice?.let { MessageAudios.encode(it.audio()) }))
                val turn = dao.insertTurn(GroupTurnEntity(groupId = id, userMessageId = userId,
                    targets = GroupChatRules.encode(pick.targets), createdAt = now))
                for (target in pick.targets) {
                    val member = members.first { it.companionId == target }
                    dao.insertMessage(GroupMessageEntity(groupId = id, role = "assistant", content = "", createdAt = now,
                        speakerId = target, speakerName = member.name, speakerAvatar = member.avatar,
                        speakerEmoji = member.emoji, turnId = turn, status = "pending"))
                }
                dao.advance(id, now, pick.next, keepDraft = sticker != null)
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
            require(turn != null && message.turnId == turn.id && turn.userMessageId == dao.latestUserMessageId(message.groupId)) { "已经开始了新话题，这条旧回复不再重试" }
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
                GroupInterjectionRound.run(dao.replies(turnId), only, { id ->
                    withContext(NonCancellable) {
                        val old = dao.message(id)
                        val began = dao.beginReply(id) == 1
                        if (began) images.delete(MessageImages.decode(old?.images).map { it.file } + listOfNotNull(MessageAudios.decode(old?.audio)?.file))
                        began
                    }
                }, { slot, canInvite -> respond(slot, canInvite) }, { dao.replies(turnId) })
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

    private suspend fun respond(slot: GroupMessageEntity, canInvite: Boolean) {
        var partial = ""
        var model = "[unresolved]"
        var sentPhotos = emptyList<MessageImage>()
        var speechWords = ""
        var requestedMember: Long? = null
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
            val s = TaVoices.effective(settings.current(), ta)
            suspend fun currentVoice(): AppSettings {
                val currentTa = db.companions().get(target) ?: error("这位 TA 已不存在")
                return TaVoices.effective(settings.current(), currentTa)
            }
            val history = dao.history(slot.groupId, slot.id, s.historySize.coerceIn(5, 100)).reversed()
            val roster = dao.members(slot.groupId).map { member ->
                if (member.active && db.companions().get(member.companionId) == null) member.copy(active = false) else member
            }
            val anchor = dao.message(turn.userMessageId)
            // Keep pre-retry attempts before this reply's trace starts overwriting its old trace.
            val earlierDeviceAttempts = dao.replies(turn.id).flatMap { GroupToolRules.decode(it.toolTrace).records }
            val query = anchor?.content.orEmpty()
            val literalAllowed = WakeOutput.allowsLiteral(query)
            val notes = GroupMemoryRules.selected(slot.groupId, dao.memories(slot.groupId), query)
            val sources = notes.mapNotNull { it.sourceMessageId }.distinct().mapNotNull { dao.message(it) }
            val availableMembers = roster.filter { it.active }.map { it.companionId }.toSet()
            val candidates = GroupInterjectionRules.candidates(group, turn, anchor, target, dao.replies(turn.id),
                roster, availableMembers, canInvite).filter { member ->
                val other = db.companions().get(member.companionId)
                other != null && other.apiModel.isNotBlank() && !secrets.key(other.apiBaseUrl).isNullOrBlank() &&
                    !busyMember(member.companionId) && !privateBusy(member.companionId)
            }
            val specs = (if (groupTools != null) GroupToolRules.offered(s) else emptyList()) +
                listOfNotNull(GroupInterjectionRules.spec(candidates))
            val selected = GroupVisionRules.selected(slot.groupId, history, turn.userMessageId)
            val pixels = mutableMapOf<String, String>()
            for (file in selected.values.flatten()) {
                currentCoroutineContext().ensureActive()
                if (images.file(file).isFile) images.dataUrl(file)?.let { pixels[file] = it }
            }
            val available = selected.mapValues { (_, files) -> files.filter { it in pixels } }
            val imageNotice = if (pixels.size < selected.values.sumOf { it.size }) "部分群图片暂时读不到，本次只提供实际可读取的画面及其余图片的来源说明。" else ""
            val messages = GroupChatRules.request(group, roster, ta.copy(name = slot.speakerName), history,
                s.userName, GroupChatRules.ids(dao.message(turn.userMessageId)?.mentions ?: "[]"), ZonedDateTime.now(),
                GroupMemoryRules.context(slot.groupId, notes, query, sources), specs, available,
                GroupInterjectionRules.isExtra(turn, anchor, slot),
                stickers = StickerBook(db.stickers().all().filter { images.file(it.file).isFile }),
                sendStickers = ToolGroup.Stickers in s.tools,
                privateMemories = privateMemories(slot.groupId, target))
                .map { m -> m.copy(images = m.images.map { requireNotNull(pixels[it]) }) }
            _progress.update { it + (slot.id to GroupReplyProgress(System.nanoTime())) }
            val text = GroupToolReply.reply(messages, specs, ta.deepThinking,
                stream = { context, offered, thinking -> client.stream(ApiEndpoint(ta.apiBaseUrl, key, ta.apiModel), context, offered, thinking) },
                execute = { call ->
                    currentCoroutineContext().ensureActive()
                    check(dao.members(slot.groupId).any { it.companionId == target && it.active } && db.companions().get(target) != null) { "这位 TA 已不在群里" }
                    if (call.name == GroupInterjectionRules.TOOL) {
                        val chosen = GroupInterjectionRules.target(call.arguments, candidates)
                        check(dao.group(slot.groupId)?.allowInterjections == true &&
                            dao.turn(turn.id)?.status == "running" && !busyMember(chosen) && !privateBusy(chosen) &&
                            dao.members(slot.groupId).any { it.companionId == chosen && it.active } && db.companions().get(chosen) != null) {
                            "这位伙伴暂时不能接话，没有安排"
                        }
                        if (requestedMember != null) ToolOutcome("本轮已记下一位伙伴的邀请，不再增加。只有本次回复成功结束后才会安排，对方还没有回答。", "已有一位候选")
                        else {
                            requestedMember = chosen
                            ToolOutcome("已记下对成员 $chosen 的接话邀请。你成功结束本次回复后，Cleos 才会检查并安排；对方尚未回答。不要声称已经回应或已安排后台互聊。", "接话邀请待本次回复完成")
                        }
                    } else if (call.name == "send_voice") {
                        if (speechWords.isNotEmpty()) ToolOutcome("本次已保存一条语音或其文字，这条没有发送；请不要重复同一句话。", "本次只发一条")
                        else {
                            check(GroupSpeechRules.sameVoice(s, currentVoice())) { "声音配置已变化，这条没有发送" }
                            val words = GroupSpeechRules.words(ToolArgs.parse(call.arguments)?.let { ToolArgs.text(it, "text") }.orEmpty())
                            GroupSpeechRules.merged(words, partial)
                            _progress.update { live -> live[slot.id]?.let { live + (slot.id to it.copy(stage = GroupReplyStage.SPEAKING)) } ?: live }
                            GroupSpeechDelivery.deliver(words, slot.groupId, target,
                                request = { synthesizeVoice(s, it) },
                                save = { text, bytes, mime -> storeVoice(text, bytes, mime, GroupSpeechRules.prefix(slot.groupId, target)) },
                                commit = { text, clip ->
                                    db.withTransaction {
                                        val current = db.companions().get(target)
                                        check(current != null && current.apiBaseUrl == ta.apiBaseUrl && current.apiModel == ta.apiModel && current.persona == ta.persona && current.deepThinking == ta.deepThinking) { "TA 的配置改变了，没有发送语音" }
                                        check(GroupSpeechRules.sameVoice(s, currentVoice())) { "声音配置改变了，没有发送语音" }
                                        check(dao.saveSpeech(slot.id, slot.groupId, target, turn.id,
                                            GroupSpeechRules.merged(text, partial), clip?.let(GroupSpeechRules::encode)) == 1) { "这一轮已结束，没有发送语音" }
                                    }
                                    speechWords = text
                                    _streaming.update { it + (slot.id to GroupSpeechRules.merged(text, partial)) }
                                }, remove = { images.delete(listOf(it)) }, onFailure = { phase, kind ->
                                    diagnostic("event=group_speech_fallback reason=$phase type=$kind model=$model")
                                })
                        }
                    } else {
                    val photoId = if (call.name == "share_photo") ToolArgs.parse(call.arguments)?.let { ToolArgs.text(it, "id") } else null
                    if (photoId != null && photoId in deliveredIds) ToolOutcome("本轮这张图库照片已经在本群保存，不再重复发送。", "这张图已发过")
                    else if (call.name == "share_photo" && sentPhotos.size >= GroupPhotoRules.MAX_IMAGES)
                        ToolOutcome("本次回复最多 3 张图片，这张没有发出。", "图片已达上限")
                    else {
                        val earlier = if (GroupDeviceActions.isWrite(call.name)) earlierDeviceAttempts + dao.replies(turn.id).flatMap { row ->
                            GroupToolRules.decode(row.toolTrace).records.filterNot { row.id == slot.id && it.id == call.id }
                        } else emptyList()
                        val outcome = GroupDeviceActions.previous(call, earlier) ?: requireNotNull(groupTools).run(call, slot.groupId, target)
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
                    }
                }, onTrace = { trace ->
                    currentCoroutineContext().ensureActive()
                    check(dao.saveToolTrace(slot.id, slot.groupId, turn.id, GroupToolRules.encode(trace)) == 1) { "这一轮已结束，没有继续执行操作" }
                    diagnostic("event=group_tools calls=${trace.records.size} returned=${trace.records.count { it.state == "returned" }} model=$model")
                },
                hasPhotos = { sentPhotos.isNotEmpty() },
                hasSpeech = { speechWords.isNotEmpty() },
                literalAllowed = literalAllowed,
                imageNotice = imageNotice,
                onStage = { stage -> _progress.update { live -> live[slot.id]?.let { live + (slot.id to it.copy(stage = stage)) } ?: live } }) {
                val visible = GroupSpeechRules.merged(speechWords, it)
                partial = it
                _streaming.update { live -> live + (slot.id to visible) }
            }
            currentCoroutineContext().ensureActive()
            db.withTransaction {
                val current = db.companions().get(target)
                check(dao.members(slot.groupId).any { it.companionId == target && it.active }) { "这位 TA 已不在群里，不能继续回复" }
                check(current != null && current.apiBaseUrl == ta.apiBaseUrl && current.apiModel == ta.apiModel && current.persona == ta.persona && current.deepThinking == ta.deepThinking) { "TA 的配置改变了，请重试" }
                if (dao.finishReply(slot.id, turn.id, GroupSpeechRules.merged(speechWords, text), "sent", null) == 1) {
                    val now = System.currentTimeMillis()
                    dao.touch(slot.groupId, now)
                    requestedMember?.let { invited ->
                        val currentGroup = requireNotNull(dao.group(slot.groupId))
                        val currentTurn = requireNotNull(dao.turn(turn.id))
                        val currentRoster = dao.members(slot.groupId)
                        val availableIds = currentRoster.filter { db.companions().get(it.companionId) != null }.map { it.companionId }.toSet()
                        val member = GroupInterjectionRules.candidates(currentGroup, currentTurn, dao.message(turn.userMessageId),
                            target, dao.replies(turn.id), currentRoster, availableIds, canInvite).firstOrNull { it.companionId == invited }
                        if (member != null && !busyMember(invited) && !privateBusy(invited)) {
                            val next = GroupChatRules.encode(GroupChatRules.ids(currentTurn.targets) + invited)
                            check(dao.appendTarget(turn.id, slot.groupId, currentTurn.targets, next, invited) == 1) { "这一轮已变化，没有安排接话" }
                            dao.insertMessage(GroupMessageEntity(groupId = slot.groupId, role = "assistant", content = "", createdAt = now,
                                speakerId = invited, speakerName = member.name, speakerAvatar = member.avatar,
                                speakerEmoji = member.emoji, turnId = turn.id, status = "pending"))
                        } else diagnostic("event=group_interjection_skipped reason=changed model=$model")
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.finishReply(slot.id, requireNotNull(slot.turnId), GroupSpeechRules.merged(speechWords, partial), "cancelled", "回复已停止，可以重试") }
            throw e
        } catch (e: Exception) {
            if (e is GroupReplyTimeoutException) diagnostic("event=stream_failure reason=group_timeout model=$model")
            dao.finishReply(slot.id, requireNotNull(slot.turnId), GroupSpeechRules.merged(speechWords, partial), "failed", e.message?.take(500) ?: "回复没有完成，可以重试")
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
            voiceJobs[id]?.cancelAndJoin()
            val files = dao.members(id).mapNotNull { it.avatar } + dao.allMessages().filter { it.groupId == id }.flatMap { MessageImages.decode(it.images).map { p -> p.file } } +
                MessageImages.decode(dao.group(id)?.draftImages).map { it.file } +
                dao.allMessages().filter { it.groupId == id }.mapNotNull { MessageAudios.decode(it.audio)?.file } +
                listOfNotNull(GroupVoiceRules.draft(dao.group(id)?.draftAudio, id)?.file)
            withContext(NonCancellable) {
                dao.delete(id); images.delete(files)
                if (settings.currentGroup.first() == id) settings.setCurrentGroup(null)
            }
        }
    }
    suspend fun stopAll() {
        ready.await()
        gate.withLock { paused = true; _generation.update { it + 1 }; jobs.values.toList().forEach { it.cancelAndJoin() }; voiceJobs.values.toList().forEach { it.cancelAndJoin() } }
    }
    fun resume() { paused = false; _generation.update { it + 1 } }
}
