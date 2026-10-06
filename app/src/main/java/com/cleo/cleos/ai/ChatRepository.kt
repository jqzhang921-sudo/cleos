package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.CallRecord
import com.cleo.cleos.data.CallRecords
import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.data.MessageAudio
import com.cleo.cleos.data.MessageAudios
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.MessageQuote
import com.cleo.cleos.data.MessageQuotes
import com.cleo.cleos.data.MessageReactions
import com.cleo.cleos.data.MessageThought
import com.cleo.cleos.data.MessageThoughts
import com.cleo.cleos.data.PatRecord
import com.cleo.cleos.data.Pats
import com.cleo.cleos.data.SecretStore
import com.cleo.cleos.data.SettingsRepository
import com.cleo.cleos.data.StickerBook
import com.cleo.cleos.data.StickerText
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.ConversationEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The reply being written right now. */
data class StreamingReply(
    val conversationId: Long,
    val text: String,
    val thinking: Boolean,
    /** Set once [text] has been stored; the screen then shows the stored copy instead. */
    val savedId: Long? = null,
    /** A tool running between two parts of the reply: 在查天气. */
    val activity: String? = null,
    /** The reply is over; this is only the hand-over to the stored bubble. */
    val finished: Boolean = false,
    /** A call to an outside service waiting for the person to allow it. */
    val asking: McpAsk? = null,
    /** What the model has thought so far, before [text]. */
    val thought: String = "",
    /** How long it thought, once it is done thinking; null while it still is. */
    val thoughtMs: Long? = null,
)

/**
 * Sending and receiving. Replies run in the app-wide scope, not a screen's: switching
 * to the diary mid-reply should not cut the reply off.
 *
 * One reply at a time per conversation, but conversations don't wait for each other: the
 * person can go and talk to another TA while the first one is still answering.
 *
 * A reply with tools is a loop: the model answers with text, tool calls or both; the
 * calls run, their results go back, and the model continues, until it answers without
 * calling anything. Each step is stored as it happens (the text, the calls, every
 * result), so what was done survives a crash or a stop, and a retry continues from the
 * results instead of adding the same todo twice.
 */
class ChatRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val client: ChatClient,
    private val tools: ToolBox,
    private val images: ImageStore,
    private val companions: Companions,
    private val recaps: Recaps,
    private val mcp: McpHub,
    private val transcriber: Transcriber,
    private val speaker: Speaker,
    private val scope: CoroutineScope,
    /**
     * A reply has been written: what the TA said in it, in order. The app shows it as a
     * notification unless that chat is on screen (AppContainer).
     */
    private val replied: suspend (ta: CompanionEntity, conversationId: Long, said: List<MessageEntity>) -> Unit = { _, _, _ -> },
    /** The song playing, as a reply is told it (Listening), while 一起听歌 is on; null when nothing plays. */
    private val listening: suspend () -> String? = { null },
    private val interrupted: (Long) -> Unit = {},
    private val activities: WakeActivities = WakeActivities(db),
) {
    /** The replies being written, by conversation. */
    private val _streaming = MutableStateFlow<Map<Long, StreamingReply>>(emptyMap())
    val streaming: StateFlow<Map<Long, StreamingReply>> = _streaming.asStateFlow()

    /** A message found somewhere else (a search) that the chat should bring into view. */
    data class Focus(val conversationId: Long, val messageId: Long)

    data class SecretDraft(val conversationId: Long, val diaryId: Long, val text: String)
    val secretDraft = MutableStateFlow<SecretDraft?>(null)

    private val _focus = MutableStateFlow<Focus?>(null)
    val focus: StateFlow<Focus?> = _focus.asStateFlow()

    /** Opens [message]'s conversation and asks the chat to show it. */
    suspend fun show(message: MessageEntity) {
        settings.setCurrentConversation(message.conversationId)
        _focus.value = Focus(message.conversationId, message.id)
    }

    /** The chat has shown it (or given up): nothing to bring into view any more. */
    fun shown() {
        _focus.value = null
    }

    /** Voice messages being turned into text, by message id. */
    private val _transcribing = MutableStateFlow<Set<Long>>(emptySet())
    val transcribing: StateFlow<Set<Long>> = _transcribing.asStateFlow()
    private val jobs = ConcurrentHashMap<Long, Job>()

    /**
     * Endpoint and model pairs that turned down a request with tools, in this run of the
     * app. They are asked without tools from then on instead of failing every message.
     */
    private val refusesTools = ConcurrentHashMap.newKeySet<String>()

    /** The same for pictures: models that can't look at images are sent the text only. */
    private val refusesImages = ConcurrentHashMap.newKeySet<String>()

    /** And for the thinking switch: models that don't take it are asked the plain way. */
    private val refusesThinking = ConcurrentHashMap.newKeySet<String>()

    /** The person's answer to a card asking whether the TA may use an outside service's tool. */
    enum class Answer { Yes, Always, No }

    /** Calls waiting on the person, by conversation. */
    private val asks = ConcurrentHashMap<Long, CompletableDeferred<Answer>>()

    /** The conversation a phone call is on in (ai/Call.kt), if any. */
    @Volatile
    private var calling: Long? = null

    /** Whether a reply is waiting or under way in [conversationId], including while its tools run, or a call is on there. */
    fun busy(conversationId: Long): Boolean = jobs[conversationId]?.isActive == true || calling == conversationId

    /** Guards starting and ending a conversation's job, which messages sent from anywhere can race. */
    private val lock = Any()

    /** Runs [block] as [conversationId]'s reply; false, and nothing runs, while one is under way there. */
    private fun start(conversationId: Long, block: suspend () -> Unit): Boolean {
        synchronized(lock) {
            if (busy(conversationId)) return false
            launchFor(conversationId) { block() }
            return true
        }
    }

    /**
     * Conversations with a reply (or a wake) waiting or under way: while there are any, leaving the
     * app keeps it running until they are done (ReplyKeeper).
     */
    private val _working = MutableStateFlow<Set<Long>>(emptySet())
    val working: StateFlow<Set<Long>> = _working.asStateFlow()

    /** [block] as [conversationId]'s job: registered before it starts, so a quick one can't finish before it is on the map. */
    private fun launchFor(conversationId: Long, block: suspend CoroutineScope.() -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        jobs[conversationId] = job
        _working.update { it + conversationId }
        job.invokeOnCompletion {
            jobs.remove(conversationId, job)
            // Unless the next one here has started meanwhile.
            if (jobs[conversationId] == null) _working.update { it - conversationId }
        }
        job.start()
    }

    // Several messages in a row, the way people send them: the reply waits until the person
    // has stopped for a moment, and whatever they send while it is being written is answered
    // right after it.

    /** How many times the person has sent something, by conversation: a reply can tell whether more came meanwhile. */
    private val sends = ConcurrentHashMap<Long, Long>()

    /** When they last sent something, by conversation. */
    private val lastSent = ConcurrentHashMap<Long, Long>()

    /** Conversations where the person is typing right now. */
    private val typingIn = ConcurrentHashMap.newKeySet<Long>()
    private val quietSince = ConcurrentHashMap<Long, Long>()
    private val mediaIn = ConcurrentHashMap.newKeySet<Long>()

    /** Voice messages still being turned into text, counted by conversation: a reply waits for their words. */
    private val holds = ConcurrentHashMap<Long, Int>()

    /** The newest of the person's messages the last reply here took in (its time), by conversation. */
    private val answeredUpTo = ConcurrentHashMap<Long, Long>()

    private val lastStamp = AtomicLong(0)

    /** Now, but always after the last one handed out: messages sent in quick succession keep their order. */
    private fun stamp(): Long = lastStamp.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }

    /** Whether the person is writing something in [conversationId]: a reply waits for them, a while. */
    fun typing(conversationId: Long, now: Boolean, processingMedia: Boolean = false) {
        if (now) { cancelFollowUp(conversationId); interrupted(conversationId) }
        if (now && processingMedia) mediaIn += conversationId else mediaIn -= conversationId
        if (now) typingIn += conversationId
        else if (typingIn.remove(conversationId)) quietSince[conversationId] = System.currentTimeMillis()
    }

    /** Whether something is in the person's input box in [conversationId] right now. */
    fun isTyping(conversationId: Long): Boolean = conversationId in typingIn

    /**
     * The person sent something here: a reply comes once they have stopped for a moment. One
     * waiting already just waits a little longer; one being written is followed by another,
     * for what came meanwhile.
     */
    private fun answerSoon(conversationId: Long) {
        cancelFollowUp(conversationId)
        interrupted(conversationId)
        synchronized(lock) {
            sends.merge(conversationId, 1L) { a, b -> a + b }
            lastSent[conversationId] = System.currentTimeMillis()
            if (busy(conversationId)) return
            launchFor(conversationId) { answerUntilQuiet(conversationId) }
        }
    }

    /** Replies, as the conversation's job, until the person has sent nothing that is not answered. */
    private suspend fun answerUntilQuiet(conversationId: Long) {
        val job = currentCoroutineContext().job
        while (true) {
            awaitQuiet(conversationId)
            val seen = sends[conversationId]
            if (db.conversations().get(conversationId) != null && unanswered(conversationId)) reply(conversationId)
            synchronized(lock) {
                // Nothing sent meanwhile: done. Off the map here, under the lock, so a message
                // sent from now on starts a reply of its own instead of counting on this one.
                if (sends[conversationId] == seen) {
                    jobs.remove(conversationId, job)
                    return
                }
            }
        }
    }

    /**
     * Wait the TA's configured quiet interval. Unsent text can hold for thirty seconds;
     * recording, importing pictures and transcription finish before their messages are read.
     */
    private suspend fun awaitQuiet(conversationId: Long) {
        val waitSeconds = ReplyWaitRules.seconds(taOf(conversationId).replyWaitSeconds)
        while (true) {
            val now = System.currentTimeMillis()
            val sent = lastSent[conversationId] ?: 0L
            val quiet = maxOf(sent, quietSince[conversationId] ?: 0L)
            if (ReplyWaitRules.ready(now - sent, now - quiet, waitSeconds,
                    composing = conversationId in typingIn, transcribing = (holds[conversationId] ?: 0) > 0 || conversationId in mediaIn)) return
            delay(HOLD_CHECK)
        }
    }

    /** Whether the person has said something since what the last reply took in. */
    private suspend fun unanswered(conversationId: Long): Boolean {
        val upTo = answeredUpTo[conversationId] ?: Long.MIN_VALUE
        return db.messages().newest(conversationId, UNANSWERED_LOOKBACK).any { m ->
            m.role == "user" && m.note == null && m.error == null && m.createdAt > upTo &&
                (m.content.isNotBlank() || m.images != null)
        }
    }

    /** [block] with the reply here held back (a voice message being turned into words). */
    private suspend fun <T> holding(conversationId: Long, block: suspend () -> T): T {
        holds.merge(conversationId, 1) { a, b -> a + b }
        try {
            return block()
        } finally {
            holds.compute(conversationId) { _, n -> if (n == null || n <= 1) null else n - 1 }
        }
    }

    private fun show(reply: StreamingReply) = _streaming.update { it + (reply.conversationId to reply) }

    private fun hide(conversationId: Long) = _streaming.update { it - conversationId }

    suspend fun newConversation(companionId: Long): Long {
        val now = System.currentTimeMillis()
        return db.conversations().insert(
            ConversationEntity(title = DEFAULT_TITLE, createdAt = now, updatedAt = now, companionId = companionId),
        )
    }

    /**
     * The conversation to show with TA [companionId]: the remembered one if it is still
     * there and theirs, else their latest, else a new one.
     */
    suspend fun resolveConversation(remembered: Long?, companionId: Long): Long {
        if (remembered != null && db.conversations().get(remembered)?.companionId == companionId) return remembered
        return db.conversations().latestFor(companionId)?.id ?: newConversation(companionId)
    }

    /** The TA a conversation is with (the first one if the conversation is gone). */
    private suspend fun taOf(conversationId: Long) =
        db.conversations().get(conversationId)?.companionId?.let { companions.get(it) } ?: companions.current()

    /**
     * The tools [ta] gets: the ones switched on, send_voice only once there is a voice to speak
     * with, and note_for_later only while it may reach out on its own (a switch of its own, not
     * one of the shared ones). Stickers are not a tool: they go along even when tools can't
     * ([stickersFor]), and a request asking for no tools but stickers would still count as one
     * with tools, and be refused over and over by a model that takes none.
     */
    private fun groupsFor(s: AppSettings, ta: CompanionEntity): Set<ToolGroup> {
        val on = (if (Speech.ready(s)) s.tools else s.tools - ToolGroup.Speak) - ToolGroup.Stickers
        return if (ta.proactive) on + ToolGroup.Later else on
    }

    /** The collection, read once for a request, and whether the TA may send from it (its switch). */
    private suspend fun stickersFor(s: AppSettings): Pair<StickerBook, Boolean> =
        StickerBook(db.stickers().all()) to (ToolGroup.Stickers in s.tools)

    /**
     * A voice message: stored at once, so it shows while it is being turned into text, then
     * answered like a typed one, even if it was sent while a reply was being written.
     */
    fun sendVoice(conversationId: Long, clip: MessageAudio, quote: MessageQuote? = null) {
        val at = stamp()
        scope.launch {
            val id = db.messages().insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = "user",
                    content = "",
                    createdAt = at,
                    audio = MessageAudios.encode(clip),
                    quote = quote?.let(MessageQuotes::encode),
                ),
            )
            db.conversations().touch(conversationId, at)
            if (holding(conversationId) { transcribe(id, clip) }) answerSoon(conversationId)
        }
    }

    /** A voice message that couldn't be turned into text, tried again; answered if it can be now. */
    fun retryVoice(conversationId: Long, messageId: Long) {
        scope.launch {
            val clip = db.messages().get(messageId)?.let { MessageAudios.decode(it.audio) } ?: return@launch
            db.messages().setError(messageId, null)
            if (holding(conversationId) { transcribe(messageId, clip) }) answerSoon(conversationId)
        }
    }

    /** Message [id]'s recording into its text; false, with the reason on the message, when it can't be. */
    private suspend fun transcribe(id: Long, clip: MessageAudio): Boolean {
        _transcribing.update { it + id }
        try {
            val s = settings.current()
            if (s.voiceBaseUrl.isBlank() || s.voiceModel.isBlank()) throw ChatException("还没接语音转文字的服务，去设置「发语音」里选一个。")
            val text = transcriber.transcribe(s.voiceBaseUrl, s.voiceModel, images.file(clip.file)).trim()
            if (text.isEmpty()) throw ChatException("没听清，再说一次？")
            db.messages().setContent(id, text)
            return true
        } catch (e: ChatException) {
            withContext(NonCancellable) { db.messages().setError(id, "没转成文字：${e.message}") }
            return false
        } finally {
            _transcribing.update { it - id }
        }
    }

    /**
     * Sends at once, also while a reply is being written; the answer comes once the person has
     * stopped for a moment ([answerSoon]). [quote]: the message this one answers. False only
     * when there is nothing to send.
     */
    fun send(conversationId: Long, text: String, pictures: List<MessageImage> = emptyList(), quote: MessageQuote? = null, diaryRequestId: Long? = null): Boolean {
        val content = text.trim()
        if (content.isEmpty() && pictures.isEmpty()) return false
        val at = stamp()
        scope.launch {
            val request = diaryRequestId?.let { id ->
                val entry = db.diary().get(id)
                val ta = db.conversations().get(conversationId)?.companionId
                id.takeIf { entry?.author == com.cleo.cleos.data.db.DiaryEntryEntity.AUTHOR_AI && entry.secret && entry.companionId == ta }
            }
            db.messages().insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = "user",
                    content = content,
                    diaryRequestId = request,
                    createdAt = at,
                    images = MessageImages.encode(pictures),
                    quote = quote?.let(MessageQuotes::encode),
                ),
            )
            val conversation = db.conversations().get(conversationId)
            if (conversation != null && conversation.title == DEFAULT_TITLE) {
                db.conversations().rename(conversationId, StickerText.plain(content).lineSequence().first().take(24).ifBlank { "[图片]" })
            }
            db.conversations().touch(conversationId, at)
            answerSoon(conversationId)
        }
        return true
    }

    /** A sticker from the drawer: sent as its name, the way the TA sends them (StickerText). */
    fun sendSticker(conversationId: Long, name: String, quote: MessageQuote? = null): Boolean =
        send(conversationId, StickerText.token(name), quote = quote)

    /** Taken one at a time: two quick taps must not each put back what the other changed. */
    private val reacting = Mutex()

    /**
     * Puts [emoji] on the TA's message [messageId], or takes it off when it is on. Nothing is
     * answered: the TA hears of it with the person's next message (Prompt), the way a reaction in
     * a chat app is seen without being a message of its own.
     */
    fun react(messageId: Long, emoji: String) {
        scope.launch {
            reacting.withLock {
                val m = db.messages().get(messageId)?.takeIf { it.role == "assistant" } ?: return@withLock
                val next = MessageReactions.toggle(MessageReactions.decode(m.reactions), emoji, stamp())
                db.messages().setReactions(messageId, MessageReactions.encode(next))
            }
        }
    }

    private val patting = Mutex()

    /**
     * 拍一拍: leaves a line in [conversationId], or adds one to the run of pats just before it.
     * Nothing is answered: the TA hears of it with the person's next message (Prompt).
     * [who] is [Pats.AI] or [Pats.ME].
     */
    fun pat(conversationId: Long, who: String, verb: String, suffix: String) {
        scope.launch {
            patting.withLock {
                val last = db.messages().newest(conversationId, 1).firstOrNull()?.takeIf { it.role == "pat" }
                val now = stamp()
                val record = Pats.again(Pats.decode(last?.content), last?.createdAt ?: 0L, who, verb, suffix, now)
                if (last != null && record.count > 1) {
                    db.messages().setPat(last.id, Pats.encode(record), now)
                    if (record.count == Pats.HEAVY_AT) answerHeavyPats(conversationId, last.id)
                } else {
                    db.messages().insert(
                        MessageEntity(conversationId = conversationId, role = "pat", content = Pats.encode(record), createdAt = now),
                    )
                }
            }
        }
    }

    /**
     * The TA patting the person (pat_user): a line in the chat, after what it has said so far.
     * The person's phone buzzes for it (the chat does that when the line appears).
     */
    suspend fun patBack(conversationId: Long, suffix: String) {
        val verb = settings.current().patVerb
        withContext(NonCancellable) {
            db.messages().insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = "pat",
                    content = Pats.encode(PatRecord(Pats.FROM_AI, 1, verb, Pats.cleanSuffix(suffix))),
                    createdAt = stamp(),
                ),
            )
        }
    }

    /**
     * Patted so many times in a row ([Pats.HEAVY_AT]): once they have stopped, a word or two back.
     * Not if they have written something meanwhile (that gets its own answer, with the pats before
     * it), if the TA is already answering, or if the TA may not pat back (its switch).
     */
    private fun answerHeavyPats(conversationId: Long, patId: Long) {
        scope.launch {
            if (ToolGroup.Pat !in settings.current().tools) return@launch
            while (true) {
                val row = db.messages().newest(conversationId, 1).firstOrNull()?.takeIf { it.id == patId && it.role == "pat" } ?: return@launch
                val quietFor = System.currentTimeMillis() - row.createdAt
                if (quietFor >= Pats.STREAK_MS + 500) break
                delay(Pats.STREAK_MS + 500 - quietFor)
            }
            val ta = taOf(conversationId)
            if (secrets.key(ta.modelFor(heard = false).baseUrl).isNullOrBlank()) return@launch
            start(conversationId) { reply(conversationId) }
        }
    }

    /** Throw away [assistantMessageId] (a failed or unwanted reply) and ask again. */
    fun retry(conversationId: Long, assistantMessageId: Long) {
        start(conversationId) {
            db.messages().delete(assistantMessageId)
            reply(conversationId)
        }
    }

    fun stop(conversationId: Long) {
        jobs[conversationId]?.cancel()
    }

    /**
     * Stops the replies in these conversations and waits until they have let go. A reply
     * still writing into a conversation that is being deleted would fail on the missing
     * row, so this comes first.
     */
    suspend fun stopReplies(conversationIds: Collection<Long>) {
        conversationIds.mapNotNull { jobs[it] }.forEach { it.cancelAndJoin() }
    }

    /** Before one TA goes, with every conversation they had. */
    suspend fun stopRepliesOf(companionId: Long) = stopReplies(db.conversations().idsFor(companionId))

    /** Before a restore replaces every conversation. */
    suspend fun stopAll() = stopReplies(jobs.keys.toList())

    /** The row and the pictures sent with it: nothing else points at those files. A call goes with all that was said in it. */
    fun deleteMessage(id: Long) {
        scope.launch {
            val m = db.messages().get(id)
            if (m?.role == "call") {
                db.messages().deleteCall(id)
                return@launch
            }
            db.messages().delete(id)
            images.delete(MessageImages.decode(m?.images).map { it.file } + listOfNotNull(MessageAudios.decode(m?.audio)?.file))
        }
    }

    // Phone calls (ai/Call.kt). What the two say goes into the conversation as messages marked as
    // the call's, so the TA remembers it like anything typed. While a call is on, replies and wakes
    // there wait (busy): nothing comes between what the two say.

    /**
     * A call begins in [conversationId]: a reply still under way there stops, since the person has
     * moved on to calling, and the call's row goes in. Returns its id.
     */
    suspend fun beginCall(conversationId: Long): Long {
        calling = conversationId
        stopReplies(listOf(conversationId))
        val at = stamp()
        val id = db.messages().insert(
            MessageEntity(conversationId = conversationId, role = "call", content = CallRecords.encode(CallRecord()), createdAt = at),
        )
        db.conversations().touch(conversationId, at)
        return id
    }

    /** The TA picked up, [at] then: the call's length counts from here. */
    suspend fun callAnswered(callId: Long, at: Long) = changeCall(callId) { it.copy(answeredAt = at) }

    /** Something said in the call, by the person ("user") or the TA ("assistant"). */
    suspend fun callLine(conversationId: Long, callId: Long, role: String, text: String) {
        withContext(NonCancellable) {
            val at = stamp()
            db.messages().insert(MessageEntity(conversationId = conversationId, role = role, content = text, createdAt = at, call = callId))
            db.conversations().touch(conversationId, at)
        }
    }

    /** The call is over: its row says when, replies there can come again, and the recap catches up with it. */
    suspend fun endCall(conversationId: Long, callId: Long) {
        withContext(NonCancellable) {
            changeCall(callId) { it.copy(endedAt = System.currentTimeMillis()) }
            db.conversations().touch(conversationId, stamp())
            if (calling == conversationId) calling = null
            recaps.foldLater(conversationId)
        }
    }

    /** Calls left open by the app stopping mid-call (killed, crashed): ended where the last thing was said in them. */
    suspend fun closeOpenCalls() {
        for (row in db.messages().calls()) {
            val record = CallRecords.decode(row.content) ?: continue
            if (record.endedAt != null || row.conversationId == calling) continue
            val last = db.messages().lastInCall(row.id) ?: record.answeredAt ?: row.createdAt
            db.messages().setContent(row.id, CallRecords.encode(record.copy(endedAt = maxOf(last, record.answeredAt ?: last))))
        }
    }

    /** The calls [history] has lines of but not the row of (the window begins mid-call): how long each went on is in the row. */
    private suspend fun callsOutside(history: List<MessageEntity>): Map<Long, CallRecord> {
        val missing = history.mapNotNullTo(HashSet()) { it.call } - history.filter { it.role == "call" }.map { it.id }.toSet()
        if (missing.isEmpty()) return emptyMap()
        return db.messages().byIds(missing).mapNotNull { m -> CallRecords.decode(m.content)?.let { m.id to it } }.toMap()
    }

    private suspend fun changeCall(callId: Long, change: (CallRecord) -> CallRecord) {
        withContext(NonCancellable) {
            val row = db.messages().get(callId) ?: return@withContext
            db.messages().setContent(callId, CallRecords.encode(change(CallRecords.decode(row.content) ?: CallRecord())))
        }
    }

    /**
     * The TA's turn in a call: its words go to [say] as they are written, for the call to speak
     * sentence by sentence. Tools as in a reply, those that make sense on the phone (CALL_TOOLS),
     * their calls and results stored as the call's, since they happened. The words aren't stored
     * here: the call stores what of them was heard (callLine). No thinking: on the phone a silence
     * that long is a dropped line. [instruction]: a turn nobody asked for (the line went quiet), told
     * the TA unseen, the way a wake is. Returns all it said; throws ChatException when there is no answer.
     */
    suspend fun callReply(conversationId: Long, callId: Long, instruction: String? = null, say: (String) -> Unit): String {
        val s = settings.current()
        val ta = taOf(conversationId)
        // Everything said on the phone is heard: the model the TA has for that, when it has one.
        val use = ta.modelFor(heard = true)
        val key = secrets.key(use.baseUrl)?.takeIf { it.isNotBlank() }
            ?: throw ChatException(if (use.forHeard) "打电话用的模型还没有填 API Key" else "还没有填 API Key")
        val conversation = db.conversations().get(conversationId) ?: throw ChatException("这段对话已经删了")
        val endpoint = ApiEndpoint(use.baseUrl, key, use.model)
        val endpointKey = endpoint.chatUrl + "|" + endpoint.model
        val history = Recap.sent(recaps.live(conversation), s.historySize)
        var groups = if (endpointKey in refusesTools) emptySet() else groupsFor(s, ta) intersect CALL_TOOLS
        val memories = if (ToolGroup.Memory in s.tools) db.memories().allFor(ta.id) else emptyList()
        val lore = if (ToolGroup.Lore in s.tools) db.lore().allFor(ta.id) else emptyList()
        // The person's stickers in what came before, told in words: none are sent on the phone.
        val stickers = StickerBook(db.stickers().all())
        val calls = callsOutside(history)
        fun build() = Prompt.messages(
            s, ta, history, ZonedDateTime.now(), groups, false, memories, conversation.recap, stickers = stickers, call = callId, calls = calls,
            lore = lore,
        ).let { if (instruction != null) Prompt.withWake(it, instruction) else it }
        var messages = build()
        val said = StringBuilder()
        var rounds = 0
        while (true) {
            val step = callStep(conversationId, callId, endpoint, messages, tools.specs(groups), mayRefuse = rounds == 0 && groups.isNotEmpty()) {
                said.append(it)
                say(it)
            }
            when (step) {
                Step.Refused -> {
                    groups = emptySet()
                    messages = build()
                }
                is Step.Called -> {
                    if (rounds == MAX_TOOL_ROUNDS) return said.toString()
                    val results = runTools(conversationId, step, s.copy(tools = groups), ta.id, emptyList(), ta.name, inCall = callId)
                    messages = messages + step.message + results
                    rounds++
                }
                else -> return said.toString()
            }
        }
    }

    /**
     * One request in a call: the words to [say] as they come, nothing shown in the chat. Calls are
     * stored at once (they are about to be acted on); an answer without calls comes back as
     * [Step.Said], unstored. A failure is thrown, unless it is the refusal [mayRefuse] allows for.
     */
    private suspend fun callStep(
        conversationId: Long,
        callId: Long,
        endpoint: ApiEndpoint,
        messages: List<ApiMessage>,
        specs: List<ToolSpec>,
        mayRefuse: Boolean,
        say: (String) -> Unit,
    ): Step {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var calls = emptyList<ToolCall>()
        try {
            client.stream(endpoint, messages, specs).collect { event ->
                when (event) {
                    is ChatEvent.Delta -> {
                        text.append(event.text)
                        say(event.text)
                    }
                    is ChatEvent.Reasoning -> if (event.sendBack) reasoning.append(event.text)
                    is ChatEvent.ToolCalls -> {
                        // Sending messages isn't offered on the phone. A model used to it may send one
                        // all the same: what it meant to send is said instead.
                        val (sends, others) = event.calls.partition { it.name in ToolSpecs.speaking }
                        for (send in sends) {
                            val words = ToolArgs.parse(send.arguments)?.let { ToolArgs.text(it, "text") }?.trim().orEmpty()
                            if (words.isNotEmpty()) {
                                val line = if (text.isEmpty()) words else "\n$words"
                                text.append(line)
                                say(line)
                            }
                        }
                        calls = others
                    }
                }
            }
        } catch (e: ChatException) {
            if (mayRefuse && text.isEmpty() && e.status in REFUSED_STATUSES) return Step.Refused
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ChatException("出错了：${e.message ?: e.javaClass.simpleName}")
        }
        if (calls.isEmpty()) return Step.Said(text.toString(), null, null)
        val sentBack = reasoning.toString().ifEmpty { null }
        val id = withContext(NonCancellable) {
            db.messages().insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = "assistant",
                    content = "",
                    createdAt = stamp(),
                    toolCalls = ToolCallCodec.encode(calls),
                    reasoning = sentBack,
                    call = callId,
                ),
            )
        }
        return Step.Called(ApiMessage("assistant", text.toString(), calls, reasoning = sentBack), savedId = id)
    }

    /** A conversation, its pictures and recordings: the rows go by cascade, the files would stay behind. */
    fun deleteConversation(id: Long) {
        scope.launch {
            stopReplies(listOf(id))
            val pictures = db.messages().imagesIn(id).flatMap { MessageImages.decode(it) }.map { it.file } +
                db.messages().audioIn(id).mapNotNull { MessageAudios.decode(it)?.file }
            db.conversations().delete(id)
            images.delete(pictures)
        }
    }

    /**
     * The person's answer to a request card: show that secret this once, or don't. Either
     * way it is their turn in the conversation, so the model answers it.
     */
    fun answerSecretRequest(conversationId: Long, requestMessageId: Long, grant: Boolean) {
        start(conversationId) answer@{
            val row = db.messages().get(requestMessageId) ?: return@answer
            val request = SecretRequests.decode(row.content)?.takeIf { it.status == SecretRequest.PENDING } ?: return@answer
            val ai = taOf(conversationId).name.trim().ifEmpty { "TA" }
            val day = LocalDate.ofEpochDay(request.day)
            val entry = db.diary().get(request.diaryId)?.takeIf { it.author == com.cleo.cleos.data.db.DiaryEntryEntity.AUTHOR_ME }
            if (grant && entry == null) {
                db.messages().setContent(row.id, SecretRequests.encode(request.copy(status = SecretRequest.GONE)))
                note(conversationId, "这个小秘密已经删掉了，没法给${ai}看")
                return@answer
            }
            val status = if (grant) SecretRequest.GRANTED else SecretRequest.DECLINED
            db.messages().setContent(row.id, SecretRequests.encode(request.copy(status = status)))
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            db.messages().insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = "user",
                    content = if (entry != null && grant) SecretRequests.shared(entry, today) else SecretRequests.declined(day, today),
                    createdAt = now,
                    note = (if (grant) "给${ai}看了" else "没给${ai}看") + "${Describe.monthDay(day)}的小秘密",
                ),
            )
            db.conversations().touch(conversationId, now)
            reply(conversationId)
        }
    }

    /** What a wake came to (see Later). */
    sealed interface WakeResult {
        /** What the TA sent, in order. */
        class Sent(val messages: List<MessageEntity>) : WakeResult

        /** It decided not to; [why] is what it said after SKIP. */
        class Skipped(val why: String) : WakeResult

        /** A reply was under way there: nothing was done. */
        data object Busy : WakeResult

        class Failed(val why: String) : WakeResult
    }

    /**
     * The TA reading something it noted, now that it is due, and deciding whether to say it:
     * [instruction] goes after the conversation as a turn the person never sees (Prompt.withWake).
     * It runs as the conversation's job, like a reply, so the two never write at once, and what
     * the person sends meanwhile is answered right after. Nothing shows while the TA decides:
     * typing dots that came to nothing would be a message that never came.
     */
    private val followUpJobs = ConcurrentHashMap<Long, Job>()
    private val wakeJobsByTa = ConcurrentHashMap<Long, Job>()

    fun cancelFollowUp(conversationId: Long) {
        synchronized(lock) { followUpJobs.remove(conversationId)?.cancel() }
    }

    suspend fun wake(conversationId: Long, instruction: String, followUp: Boolean = false,
                     source: String = com.cleo.cleos.data.db.WakeActivityEntity.NOTE,
                     allowed: suspend () -> Boolean = { true }): WakeResult {
        val taId = db.conversations().get(conversationId)?.companionId ?: return WakeResult.Skipped("对话已删除")
        val activity = activities.begin(taId, conversationId, source)
        return try {
            val result = wakeRecorded(conversationId, instruction, followUp, activity, allowed)
            if (result is WakeResult.Failed && result.why == STOPPED)
                activities.finish(activity, com.cleo.cleos.data.db.WakeActivityEntity.CANCELLED, "执行被取消，可能已发送部分内容；可在聊天里查看")
            else activities.result(activity, result)
            result
        } catch (e: CancellationException) {
            withContext(NonCancellable) { activities.finish(activity, com.cleo.cleos.data.db.WakeActivityEntity.CANCELLED, "执行被取消，可能已发送部分内容；可在聊天里查看") }
            throw e
        } catch (e: Exception) {
            activities.finish(activity, com.cleo.cleos.data.db.WakeActivityEntity.FAILED, e.message ?: "执行失败")
            throw e
        }
    }

    private suspend fun wakeRecorded(conversationId: Long, instruction: String, followUp: Boolean, activity: Long?, allowed: suspend () -> Boolean): WakeResult {
        val taId = db.conversations().get(conversationId)?.companionId ?: return WakeResult.Skipped("对话已删除")
        val outcome = CompletableDeferred<WakeResult>()
        synchronized(lock) {
            if (busy(conversationId) || wakeJobsByTa[taId]?.isActive == true) return WakeResult.Busy
            val seen = sends[conversationId]
            launchFor(conversationId) {
                outcome.complete(
                    try {
                        if (allowed()) wakeTurn(conversationId, instruction, activity) else WakeResult.Skipped("这次补充已取消")
                    } catch (e: CancellationException) {
                        outcome.complete(WakeResult.Failed(STOPPED))
                        throw e
                    } catch (e: Exception) {
                        WakeResult.Failed(e.message ?: e.javaClass.simpleName)
                    },
                )
                followUpJobs.remove(conversationId, coroutineContext.job)
                wakeJobsByTa.remove(taId, coroutineContext.job)
                synchronized(lock) {
                    // Nothing sent meanwhile: done, off the map under the lock (see answerUntilQuiet).
                    if (sends[conversationId] == seen) {
                        jobs.remove(conversationId, coroutineContext.job)
                        return@launchFor
                    }
                }
                answerUntilQuiet(conversationId)
            }
            if (followUp) jobs[conversationId]?.let { job ->
                followUpJobs[conversationId] = job
                job.invokeOnCompletion { followUpJobs.remove(conversationId, job) }
            }
            jobs[conversationId]?.let { job ->
                wakeJobsByTa[taId] = job
                job.invokeOnCompletion { wakeJobsByTa.remove(taId, job) }
            }
            // Stopped before it began (its TA deleted meanwhile): an answer all the same.
            jobs[conversationId]?.invokeOnCompletion { outcome.complete(WakeResult.Failed(STOPPED)) }
        }
        return outcome.await()
    }

    private suspend fun wakeTurn(conversationId: Long, instruction: String, activity: Long?): WakeResult {
        activities.preparing(activity)
        val s = settings.current()
        val ta = taOf(conversationId)
        val key = secrets.key(ta.apiBaseUrl)
        if (key.isNullOrBlank()) return WakeResult.Failed("还没填 API Key")
        val conversation = db.conversations().get(conversationId) ?: return WakeResult.Failed("对话已经删了")
        val endpoint = ApiEndpoint(ta.apiBaseUrl, key, ta.apiModel)
        val endpointKey = endpoint.chatUrl + "|" + endpoint.model
        val history = Recap.sent(recaps.live(conversation), s.historySize)
        val now = ZonedDateTime.now()
        val since = System.currentTimeMillis()
        // No outside services and no pictures: nobody is there to allow a call, and deciding
        // whether to say something shouldn't cost what answering a picture does. No music either:
        // a song is paused or skipped when the person asks, and a wake is nobody asking.
        var groups = if (endpointKey in refusesTools) emptySet() else groupsFor(s, ta) - ToolGroup.Music
        var thinking = ta.deepThinking && endpointKey !in refusesThinking
        val memories = if (ToolGroup.Memory in s.tools) db.memories().allFor(ta.id) else emptyList()
        val lore = if (ToolGroup.Lore in s.tools) db.lore().allFor(ta.id) else emptyList()
        val (stickers, sendStickers) = stickersFor(s)
        val calls = callsOutside(history)
        fun build() = Prompt.withWake(
            Prompt.messages(s, ta, history, now, groups, false, memories, conversation.recap, stickers = stickers, sendStickers = sendStickers, calls = calls, lore = lore),
            instruction,
        )
        var messages = build()
        var rounds = 0
        suspend fun sent() = db.messages().proactiveSince(conversationId, since)
        suspend fun result(why: String): WakeResult {
            val said = sent()
            if (said.isEmpty()) return WakeResult.Skipped(why)
            recaps.foldLater(conversationId)
            return WakeResult.Sent(said)
        }
        try {
            while (true) {
                val mayRefuse = rounds == 0 && (groups.isNotEmpty() || thinking)
                when (val step = step(conversationId, endpoint, messages, tools.specs(groups), mayRefuse, thinking, showThought = ta.deepThinking, wake = true, onRequest = { activities.request(activity) })) {
                    is Step.Said -> {
                        val error = step.error
                        if (error != null) return if (sent().isEmpty()) WakeResult.Failed(error) else result("")
                        val text = step.text.trim()
                        if (text.isEmpty() || LaterRules.isSkip(text)) return result(LaterRules.skipReason(text))
                        // A model that doesn't send messages through the tool says it in plain words.
                        withContext(NonCancellable) {
                            val at = System.currentTimeMillis()
                            db.messages().insert(
                                MessageEntity(
                                    conversationId = conversationId,
                                    role = "assistant",
                                    content = text,
                                    createdAt = at,
                                    thought = step.thought?.let(MessageThoughts::encode),
                                    proactive = true,
                                ),
                            )
                            db.conversations().touch(conversationId, at)
                        }
                        return result("")
                    }
                    Step.Refused -> {
                        // As in a reply: the thinking switch goes first, then the tools. The chat is told
                        // about it at the next reply, not by a line appearing out of nowhere.
                        if (thinking) thinking = false else groups = emptySet()
                        messages = build()
                    }
                    is Step.Called -> {
                        if (rounds == MAX_TOOL_ROUNDS) return result("连着用了太多次工具")
                        activities.tools(activity, step.message.toolCalls.map { tools.action(it.name) })
                        val results = runTools(conversationId, step, s.copy(tools = groups), ta.id, emptyList(), ta.name, wake = true)
                        if (step.message.toolCalls.all { it.name in ToolSpecs.speaking }) return result("")
                        messages = messages + step.message + results
                        rounds++
                    }
                    is Step.Ended -> return result("")
                }
            }
        } finally {
            // Nothing said: none of it happened, as far as the chat goes. Its calls (putting the note
            // off, say) and its thinking would otherwise sit there under "自己想起来的" with no message
            // after them, and the person would see what was meant to stay unsaid. What it did is in
            // the settings line; a note taken again is in its own table.
            withContext(NonCancellable) {
                if (sent().isEmpty()) {
                    db.messages().deleteProactiveSince(conversationId, since)
                    // Nor did it bring the conversation up the list.
                    val newest = db.messages().newest(conversationId, 1).firstOrNull()?.createdAt ?: 0L
                    db.conversations().touch(conversationId, maxOf(conversation.updatedAt, newest))
                }
            }
        }
    }

    private suspend fun reply(conversationId: Long) {
        val startedAt = System.currentTimeMillis()
        val s = settings.current()
        val ta = taOf(conversationId)
        // What isn't folded into the recap yet, as much of it as the window takes. The recap
        // stands in for everything before.
        val conversation = db.conversations().get(conversationId)
        val history = if (conversation == null) emptyList() else Recap.sent(recaps.live(conversation), s.historySize)
        // Something said aloud is answered by the model the TA has for words heard, when it has one.
        val use = ta.modelFor(heard = history.lastOrNull { it.role == "user" }?.audio != null)
        val key = secrets.key(use.baseUrl)
        if (key.isNullOrBlank()) {
            db.messages().insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = "assistant",
                    content = "",
                    createdAt = System.currentTimeMillis(),
                    error = if (use.forHeard) "回语音用的模型还没有填 API Key。去设置 ›「模型」里填上。" else "还没有填 API Key。去设置里填上，就能聊了。",
                ),
            )
            return
        }
        // The dots at once. Getting ready can take a while (an outside service slow to list its
        // tools, one that is down until it times out), and the chat should show meanwhile that
        // a reply is coming, with stop.
        show(StreamingReply(conversationId, "", thinking = false))
        try {
            val endpoint = ApiEndpoint(use.baseUrl, key, use.model)
            val endpointKey = endpoint.chatUrl + "|" + endpoint.model
            // What this reply takes in: whatever the person sends from here on is answered after it.
            history.lastOrNull { it.role == "user" }?.let { m -> answeredUpTo.merge(conversationId, m.createdAt) { a, b -> maxOf(a, b) } }
            val recap = conversation?.recap
            val now = ZonedDateTime.now()
            var groups = if (endpointKey in refusesTools) emptySet() else groupsFor(s, ta)
            // The tools of the MCP services switched on come along whenever tools do.
            var outside = if (endpointKey in refusesTools) emptyList() else mcp.tools()
            var withImages = endpointKey !in refusesImages && history.any { it.role == "user" && it.images != null }
            var thinking = ta.deepThinking && endpointKey !in refusesThinking
            // What the TA remembers, read once for this reply.
            val memories = if (ToolGroup.Memory in s.tools) db.memories().allFor(ta.id) else emptyList()
            // What the world book of this TA says, read in the same breath; it is only a list of
            // names until the TA looks one up.
            val lore = if (ToolGroup.Lore in s.tools) db.lore().allFor(ta.id) else emptyList()
            // What it noted that has come due while the two are talking: this reply takes it in,
            // and once it has gone through, it is dealt with (Later wakes nobody for it).
            val due = if (ta.proactive) db.later().dueFor(ta.id, System.currentTimeMillis()) else emptyList()
            // The song playing as this reply begins, told beside the time. Asked for even when the model
            // takes no tools: it is something to know, not something to do.
            val heard = if (ToolGroup.Music in s.tools) runCatching { listening() }.getOrNull() else null
            val (stickers, sendStickers) = stickersFor(s)
            val calls = callsOutside(history)
            fun build() = Prompt.messages(
                s, ta, history, now, groups, withImages, memories, recap, outside, due.map { it.what }, heard, stickers, sendStickers, calls = calls,
                lore = lore,
            )
            var messages = prepare(build())
            var rounds = 0
            // What the last refusal made this reply leave out, and when.
            var leftOut: LeftOut? = null
            suspend fun done() {
                remember(leftOut, conversationId, endpointKey)
                recaps.foldLater(conversationId)
                if (due.isNotEmpty()) db.later().delete(due.map { it.id })
                db.messages().repliedSince(conversationId, startedAt).takeIf { it.isNotEmpty() }?.let { replied(ta, conversationId, it) }
            }
            while (true) {
                val mayRefuse = rounds == 0 && (groups.isNotEmpty() || outside.isNotEmpty() || withImages || thinking)
                val specs = tools.specs(groups) + outside.map { it.spec }
                when (val step = step(conversationId, endpoint, messages, specs, mayRefuse, thinking, showThought = ta.deepThinking)) {
                    is Step.Ended -> {
                        if (step.ok) done()
                        return
                    }
                    // Only in a wake.
                    is Step.Said -> return
                    Step.Refused -> {
                        // The thinking switch goes first: it was asked for on top of the rest. Then
                        // pictures: many more models take tools than take pictures.
                        val what = when {
                            thinking -> Left.Thinking
                            withImages -> Left.Images
                            else -> Left.Tools
                        }
                        leftOut = LeftOut(what, at = System.currentTimeMillis())
                        when (what) {
                            Left.Thinking -> thinking = false
                            Left.Images -> withImages = false
                            Left.Tools -> {
                                groups = emptySet()
                                outside = emptyList()
                            }
                        }
                        messages = prepare(build())
                    }
                    is Step.Called -> {
                        if (rounds == MAX_TOOL_ROUNDS) {
                            note(conversationId, TOO_MANY_ROUNDS)
                            hide(conversationId)
                            return
                        }
                        val results = runTools(conversationId, step, s.copy(tools = groups), ta.id, outside, ta.name)
                        // Only messages sent: that was the whole reply. Asking again would bring
                        // nothing new, or a "发好了".
                        if (step.message.toolCalls.all { it.name in ToolSpecs.speaking }) {
                            done()
                            hide(conversationId)
                            return
                        }
                        messages = messages + step.message + results
                        rounds++
                    }
                }
            }
        } catch (e: Throwable) {
            // Stopped (or failed) while getting ready or while a tool ran: no stream is open to
            // clear the live row on its way out.
            if (_streaming.value[conversationId]?.finished != true) hide(conversationId)
            throw e
        }
    }

    private enum class Left { Thinking, Images, Tools }

    private class LeftOut(val what: Left, val at: Long)

    /**
     * Once a reply got through without what a refusal made it leave out, that is what the
     * model can't take: it is left out from now on, and the chat says so.
     */
    private suspend fun remember(out: LeftOut?, conversationId: Long, endpointKey: String) {
        if (out == null) return
        val line = when (out.what) {
            Left.Thinking -> THINKING_REFUSED.also { refusesThinking += endpointKey }
            Left.Images -> IMAGES_REFUSED.also { refusesImages += endpointKey }
            Left.Tools -> TOOLS_REFUSED.also { refusesTools += endpointKey }
        }
        note(conversationId, line, at = out.at - 1)
    }

    /** Pictures become data: URLs just before sending; one that can't be read is left out. */
    private suspend fun prepare(messages: List<ApiMessage>): List<ApiMessage> = messages.map { m ->
        if (m.images.isEmpty()) m else m.copy(images = m.images.mapNotNull { images.dataUrl(it) })
    }

    private sealed interface Step {
        class Ended(val ok: Boolean) : Step

        /** A wake's answer without calls, not stored: it may be SKIP, and the caller decides. */
        class Said(val text: String, val error: String?, val thought: MessageThought?) : Step

        /**
         * The first request failed the way requests fail on a model that can't take what
         * was in them: tools, or pictures.
         */
        data object Refused : Step

        /**
         * [savedId]: the row the text said before the calls went into, if there was any to store.
         * [thought]: the thinking, when it has no row yet (the first message sent will take it).
         */
        class Called(val message: ApiMessage, val savedId: Long?, val thought: MessageThought? = null) : Step
    }

    /**
     * One request: streams it to the screen, stores what came back, says what's next. [showThought]:
     * the TA's thinking switch. Off, a model that thinks all the same (or a relay that didn't pass
     * on "don't") only shows the dots while it does, and nothing of it is kept.
     *
     * [wake]: the TA deciding on its own whether to say something (see [wake]). Nothing is shown
     * while it decides, what it sends is marked as its own, and an answer without calls comes
     * back as [Step.Said], unstored, since it may be SKIP.
     */
    private suspend fun step(
        conversationId: Long,
        endpoint: ApiEndpoint,
        messages: List<ApiMessage>,
        specs: List<ToolSpec>,
        mayRefuse: Boolean,
        thinking: Boolean,
        showThought: Boolean,
        wake: Boolean = false,
        onRequest: suspend () -> Unit = {},
    ): Step {
        val startedAt = System.currentTimeMillis()
        val text = StringBuilder()
        // What goes back to the model within this turn, and what the person gets to read.
        val reasoning = StringBuilder()
        val thinkingText = StringBuilder()
        var thinkingFrom = 0L
        var thinkingMs: Long? = null
        // The thinking as the live reply shows it once the words have begun.
        var thoughtShown = ""
        var calls = emptyList<ToolCall>()
        var error: String? = null
        var status: Int? = null
        fun doneThinking() {
            if (thinkingText.isNotEmpty() && thinkingMs == null) {
                thinkingMs = System.currentTimeMillis() - thinkingFrom
                thoughtShown = thinkingText.toString()
            }
        }
        fun thought() = thinkingText.toString().trim().takeIf { it.isNotEmpty() }?.let {
            doneThinking()
            MessageThought(it, thinkingMs ?: 0)
        }
        fun live(reply: StreamingReply) {
            if (!wake) show(reply)
        }
        live(StreamingReply(conversationId, "", thinking = false))
        try {
            onRequest()
            client.stream(endpoint, messages, specs, thinking).collect { event ->
                when (event) {
                    is ChatEvent.Delta -> {
                        doneThinking()
                        text.append(event.text)
                        live(StreamingReply(conversationId, text.toString(), thinking = false, thought = thoughtShown, thoughtMs = thinkingMs))
                    }
                    is ChatEvent.Reasoning -> {
                        if (event.sendBack) reasoning.append(event.text)
                        if (showThought) {
                            if (thinkingText.isEmpty()) thinkingFrom = System.currentTimeMillis()
                            thinkingText.append(event.text)
                        }
                        if (text.isEmpty()) live(StreamingReply(conversationId, "", thinking = true, thought = thinkingText.toString()))
                    }
                    is ChatEvent.ToolCalls -> calls = event.calls
                }
            }
        } catch (e: CancellationException) {
            // Half of what a wake was deciding to say is not a message.
            if (!wake) withContext(NonCancellable) { finish(conversationId, startedAt, text.toString(), STOPPED, keepEmpty = false, thought()) }
            throw e
        } catch (e: ChatException) {
            error = e.message
            status = e.status
        } catch (e: Exception) {
            error = "出错了：${e.message ?: e.javaClass.simpleName}"
        }
        // A model that can't take tools, pictures or the thinking switch turns the first
        // request down before writing a word. The caller asks again without them.
        if (error != null && mayRefuse && text.isEmpty() && status in REFUSED_STATUSES) return Step.Refused

        return withContext(NonCancellable) {
            val shown = thought()
            if (error == null && calls.isNotEmpty()) {
                val body = text.toString()
                val sentBack = reasoning.toString().ifEmpty { null }
                // Sent messages are stored as bubbles of their own, not as calls (Prompt turns
                // bubbles in a row back into calls). With some sent, the words said first go in
                // now as a bubble too, and the other calls only after the messages (runTools):
                // a call has to stay right before its results.
                val speaks = calls.any { it.name in ToolSpecs.speaking }
                val id = when {
                    !speaks -> db.messages().insert(
                        MessageEntity(
                            conversationId = conversationId,
                            role = "assistant",
                            content = body,
                            createdAt = startedAt,
                            toolCalls = ToolCallCodec.encode(calls),
                            reasoning = sentBack,
                            thought = shown?.let(MessageThoughts::encode),
                            proactive = wake,
                        ),
                    )
                    body.isBlank() -> null
                    else -> db.messages().insert(
                        MessageEntity(
                            conversationId = conversationId,
                            role = "assistant",
                            content = body,
                            createdAt = startedAt,
                            thought = shown?.let(MessageThoughts::encode),
                            proactive = wake,
                        ),
                    )
                }
                db.conversations().touch(conversationId, System.currentTimeMillis())
                // Nothing stored yet: the first message sent takes the thinking along.
                Step.Called(ApiMessage("assistant", body, calls, reasoning = sentBack), savedId = id, thought = shown.takeIf { id == null })
            } else if (wake) {
                Step.Said(text.toString(), error, shown)
            } else {
                finish(conversationId, startedAt, text.toString(), error, keepEmpty = true, shown)
                Step.Ended(ok = error == null)
            }
        }
    }

    /**
     * Runs the calls, storing each result with its line for the chat. Sent messages come
     * first: each is the TA speaking and becomes a bubble, a moment after the one before, the
     * way messages arrive when someone types them one by one. Then the other calls, in order.
     * The results go back in the order of the calls. [wake]: as in [step]. [inCall]: the phone call
     * this is in (callReply): nothing shows in the chat, and every row is marked as the call's.
     */
    private suspend fun runTools(
        conversationId: Long,
        step: Step.Called,
        s: AppSettings,
        companionId: Long,
        outside: List<McpTool>,
        ai: String,
        wake: Boolean = false,
        inCall: Long? = null,
    ): List<ApiMessage> {
        val quiet = wake || inCall != null
        val said = step.message.content
        val (sends, others) = step.message.toolCalls.partition { it.name in ToolSpecs.speaking }
        val results = HashMap<String, ApiMessage>()
        var previous = said.takeIf { it.isNotBlank() }
        var sent = 0
        // The step's thinking, while nothing it put in the chat has it yet.
        var thought = step.thought?.let(MessageThoughts::encode)
        // What the person said lately, newest first: what a message's quote is looked up in.
        var recent: List<MessageEntity>? = null
        suspend fun quoteIn(args: JsonObject?): MessageQuote? {
            val quoted = args?.let { ToolArgs.text(it, "quote") }?.trim().orEmpty()
            if (quoted.isEmpty() || ToolArgs.isNone(quoted)) return null
            val said = recent ?: db.messages().newest(conversationId, UNANSWERED_LOOKBACK)
                .filter { it.role == "user" && it.note == null }
                .also { recent = it }
            return MessageQuotes.find(said, quoted)?.let(MessageQuotes::of)
        }
        for (call in sends) {
            val args = ToolArgs.parse(call.arguments)
            val words = args?.let { ToolArgs.text(it, "text") }?.trim().orEmpty()
            val result = when {
                words.isEmpty() -> "没有内容，没发出去。"
                sent >= MAX_MESSAGES -> "一次最多发 $MAX_MESSAGES 条，这条没发出去。"
                else -> {
                    previous?.let {
                        // Typing the next one: the dots, for a moment that grows a little with what was just said.
                        if (!quiet) show(StreamingReply(conversationId, said, thinking = false, savedId = step.savedId.takeIf { said.isNotEmpty() }))
                        delay((400L + it.length * 25L).coerceAtMost(1500L))
                    }
                    // A voice message is made first (a voice service can take a few seconds). One that
                    // can't be made goes as text instead: what the TA said isn't lost.
                    var voice: MessageAudio? = null
                    var why: String? = null
                    if (call.name == ToolSpecs.sendVoice.name) {
                        if (!quiet) show(StreamingReply(conversationId, said, thinking = false, savedId = step.savedId.takeIf { said.isNotEmpty() }, activity = "在录语音"))
                        try {
                            voice = speaker.speak(s, words)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            why = e.message ?: e.javaClass.simpleName
                        }
                    }
                    // One the model can't place is simply not shown as a quote.
                    val quote = if (call.name == ToolSpecs.sendMessage.name) quoteIn(args) else null
                    withContext(NonCancellable) {
                        val at = System.currentTimeMillis()
                        db.messages().insert(
                            MessageEntity(
                                conversationId = conversationId,
                                role = "assistant",
                                content = words,
                                createdAt = at,
                                audio = voice?.let(MessageAudios::encode),
                                thought = thought,
                                quote = quote?.let(MessageQuotes::encode),
                                proactive = wake,
                                call = inCall,
                            ),
                        )
                        db.conversations().touch(conversationId, at)
                    }
                    thought = null
                    why?.let { note(conversationId, "语音没做出来（$it），这句改成了文字") }
                    previous = words
                    sent++
                    if (why != null) "语音没做出来（$why），这句已经改成文字发出去了。" else ToolSpecs.SENT
                }
            }
            results[call.id] = ApiMessage("tool", result, toolCallId = call.id)
        }
        if (sends.isNotEmpty() && others.isNotEmpty()) {
            // Held back in step(): stored now, after the messages and right before the results.
            withContext(NonCancellable) {
                db.messages().insert(
                    MessageEntity(
                        conversationId = conversationId,
                        role = "assistant",
                        content = "",
                        createdAt = System.currentTimeMillis(),
                        toolCalls = ToolCallCodec.encode(others),
                        reasoning = step.message.reasoning,
                        // Only if no message went out to carry it.
                        thought = thought,
                        proactive = wake,
                        call = inCall,
                    ),
                )
            }
        }
        for (call in others) {
            val outer = outside.firstOrNull { it.fnName == call.name }
            // What was said before the calls stays up; the screen switches to the stored
            // copy (savedId) as soon as it is in the list.
            val live = StreamingReply(
                conversationId,
                said,
                thinking = false,
                savedId = step.savedId.takeIf { said.isNotEmpty() },
                activity = outer?.let { "在用${it.serverName}" } ?: tools.activity(call.name),
            )
            if (!quiet) show(live)
            val outcome = try {
                if (outer != null) runOutside(conversationId, outer, call, live, ai) else tools.run(call, s, conversationId, companionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolOutcome("工具出错了：${e.message ?: e.javaClass.simpleName}", "${outer?.spec?.action ?: tools.action(call.name)}出错了")
            }
            // The tool has acted (a todo exists now), so its result is stored even if
            // stop was pressed meanwhile: the history should match what happened.
            withContext(NonCancellable) {
                val at = System.currentTimeMillis()
                db.messages().insert(
                    MessageEntity(
                        conversationId = conversationId,
                        role = "tool",
                        content = outcome.result,
                        createdAt = at,
                        toolCallId = call.id,
                        note = outcome.note,
                        proactive = wake,
                        call = inCall,
                    ),
                )
                outcome.sharedDiaryId?.let { id ->
                    db.messages().insert(MessageEntity(conversationId = conversationId, role = "note", content = outcome.sharedExcerpt?.let { "secret-excerpt:" + SecretShares.encode(SecretShare(id, it)) } ?: "shared-diary:$id", note = if (outcome.sharedExcerpt != null) "愿意告诉你一点" else "查看这篇小秘密", createdAt = at, call = inCall))
                }
                outcome.request?.let {
                    db.messages().insert(
                        MessageEntity(conversationId = conversationId, role = "request", content = SecretRequests.encode(it), createdAt = at, call = inCall),
                    )
                }
            }
            results[call.id] = ApiMessage("tool", outcome.result, toolCallId = call.id)
        }
        return step.message.toolCalls.mapNotNull { results[it.id] }
    }

    /**
     * A call to an MCP service's tool. Unless the tool only reads, or the person said to always
     * allow it, they are asked first, on a card in the chat: a service can take orders and
     * payments, and a TA must not do that on its own.
     */
    private suspend fun runOutside(conversationId: Long, tool: McpTool, call: ToolCall, live: StreamingReply, ai: String): ToolOutcome {
        val what = "${tool.serverName}的${tool.title}"
        val server = mcp.servers.get(tool.serverId)?.takeIf { it.enabled }
            ?: return ToolOutcome("这个服务在设置里关掉了，现在用不了。", "用${what}没成：设置里关掉了")
        val args = ToolArgs.parse(call.arguments)
            ?: return ToolOutcome("参数不是合法的 JSON 对象，按参数说明重新调用。", "用${what}没成：参数写错了")
        if (server.askFirst && !tool.readOnly && tool.name !in server.allowed) {
            when (ask(conversationId, live, McpAsk(tool.serverName, tool.title, Mcp.preview(args)))) {
                Answer.No -> return ToolOutcome(
                    "对方没有同意，这次没有调用。需要的话在聊天里问问对方。",
                    "没让${ai.ifBlank { "TA" }}用$what",
                )
                Answer.Always -> mcp.servers.allow(server.id, tool.name)
                Answer.Yes -> Unit
            }
            show(live)
        }
        return try {
            ToolOutcome(mcp.call(server, tool, args), "用了$what")
        } catch (e: McpException) {
            ToolOutcome("没有调用成功：${e.message}。照实告诉对方没成，别编结果。", "用${what}没成：${e.message.orEmpty().lineSequence().first()}")
        }
    }

    /** Shows the card and waits for the person. No answer in [ASK_WAIT] counts as no. */
    private suspend fun ask(conversationId: Long, live: StreamingReply, question: McpAsk): Answer {
        val answer = CompletableDeferred<Answer>()
        asks[conversationId] = answer
        show(live.copy(activity = null, asking = question))
        return try {
            withTimeoutOrNull(ASK_WAIT) { answer.await() } ?: Answer.No
        } finally {
            asks.remove(conversationId, answer)
        }
    }

    /** The person answered the card in [conversationId]. */
    fun answer(conversationId: Long, answer: Answer) {
        asks[conversationId]?.complete(answer)
    }

    private suspend fun finish(
        conversationId: Long,
        startedAt: Long,
        body: String,
        error: String?,
        keepEmpty: Boolean,
        thought: MessageThought? = null,
    ) {
        if (body.isNotEmpty() || (keepEmpty && error != null)) {
            val id = db.messages().insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = "assistant",
                    content = body,
                    createdAt = startedAt,
                    error = error,
                    thought = thought?.let(MessageThoughts::encode),
                ),
            )
            db.conversations().touch(conversationId, System.currentTimeMillis())
            // Hand over from the live bubble to the stored one without a gap:
            // the screen hides the live bubble once it sees savedId in its list.
            // The cleanup runs on its own so this job (and `busy`) ends now.
            val handover = StreamingReply(
                conversationId,
                body,
                thinking = false,
                savedId = id,
                finished = true,
                thought = thought?.text.orEmpty(),
                thoughtMs = thought?.ms,
            )
            show(handover)
            scope.launch {
                delay(1500)
                // Unless the next reply here has begun meanwhile.
                _streaming.update { if (it[conversationId] == handover) it - conversationId else it }
            }
        } else {
            hide(conversationId)
        }
    }

    private suspend fun note(conversationId: Long, text: String, at: Long = System.currentTimeMillis()) {
        withContext(NonCancellable) {
            db.messages().insert(MessageEntity(conversationId = conversationId, role = "note", content = "", createdAt = at, note = text))
        }
    }

    companion object {
        const val DEFAULT_TITLE = "新对话"
        const val STOPPED = "已停止"

        /** Round trips with tools in one reply before it is cut off, against a model stuck calling. */
        const val MAX_TOOL_ROUNDS = 5

        /** Messages sent in one go: past this it is a flood, not a conversation. */
        const val MAX_MESSAGES = 8

        private const val HOLD_CHECK = 300L

        /** How far back to look for what the person said and wasn't answered, or what the TA quotes. */
        private const val UNANSWERED_LOOKBACK = 30

        /** How long a call to an outside service waits for the person to allow it. */
        private const val ASK_WAIT = 10 * 60_000L

        /**
         * How endpoints turn down tools or pictures a model can't take: 400 (SiliconFlow,
         * vLLM), 404 (OpenRouter finds no endpoint for it), 422 (strict validators).
         */
        private val REFUSED_STATUSES = setOf(400, 404, 422)

        /**
         * What a TA can do on the phone: what needs no picture, card or message of its own in the
         * chat, and leaves the sound alone (music_control would talk over the call).
         */
        private val CALL_TOOLS = setOf(
            ToolGroup.Todos, ToolGroup.Diary, ToolGroup.AiDiary, ToolGroup.Memory, ToolGroup.Letters, ToolGroup.Weather,
            ToolGroup.Location, ToolGroup.Later, ToolGroup.Alarm, ToolGroup.Calendar,
        )
        private const val TOOLS_REFUSED = "这个模型不接受工具调用，这次没带工具。想让 TA 记待办、查天气，换一个支持工具的模型。"
        private const val IMAGES_REFUSED = "这个模型看不了图片，这次只发了文字。想让 TA 看图，换一个能看图的模型。"
        private const val THINKING_REFUSED = "这个模型不认深度思考的开关，这次照常回复了，之后也不再带这个开关。"
        private const val TOO_MANY_ROUNDS = "连着用了太多次工具，先停在这里。"
    }
}
