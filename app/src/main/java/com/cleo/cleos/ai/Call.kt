package com.cleo.cleos.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.cleo.cleos.CallService
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.SettingsRepository
import com.cleo.cleos.data.StickerText
import com.cleo.cleos.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a call is at, for its screen. */
enum class CallPhase {
    /** Waiting for the TA to pick up. */
    Ringing,

    /** Picked up, and the person's turn to talk. */
    Listening,

    /** The person is talking. */
    Hearing,

    /** What they said is being made out, and the TA's answer written. */
    Thinking,

    /** The TA is talking. */
    Speaking,
    Ended,
}

data class CallState(
    val conversationId: Long,
    val name: String = "",
    val avatar: String? = null,
    val avatarEmoji: String? = null,
    val phase: CallPhase = CallPhase.Ringing,
    /** When the TA picked up; null while it rings. */
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val muted: Boolean = false,
    /** Another app has the sound (a phone call coming in, a video): the call waits until it is given back. */
    val held: Boolean = false,
    /** What the person said last, as it was made out. */
    val heard: String? = null,
    /** What the TA is saying right now. */
    val saying: String? = null,
    /** Something that went wrong, in a line, for a few seconds. */
    val problem: String? = null,
    val session: Long = 0,
    val incoming: Boolean = false,
)

/**
 * Phone calls with a TA: the person talks, the TA answers in its voice, back and forth until one
 * of them hangs up. One call at a time.
 *
 * The two take turns, the way a call on speaker goes: while the TA talks the microphone isn't
 * listened to, or the TA would hear itself from the speaker and answer that. The person's turn
 * ends when they have been quiet for a moment (TurnDetector), and a tap cuts the TA off. What
 * they said goes through the voice-to-text service of 发语音; the TA's answer is written like a
 * reply (ChatRepository.callReply) and spoken in pieces with the voice from TA 的声音, each piece
 * made while the one before plays, so the first words come long before the answer is done.
 *
 * What both said goes into the conversation (ChatRepository.callLine): the TA remembers a call
 * like anything typed. The sound itself is not kept.
 */
class Calls(
    context: Context,
    private val db: AppDatabase,
    private val chat: ChatRepository,
    private val companions: Companions,
    private val settings: SettingsRepository,
    private val transcriber: Transcriber,
    private val speaker: Speaker,
    private val scope: CoroutineScope,
    private val diagnostic: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val dir = File(context.cacheDir, "call")

    private val _state = MutableStateFlow<CallState?>(null)
    val state: StateFlow<CallState?> = _state.asStateFlow()

    /** How loud whoever is talking is, 0 to 1: the rings round the TA's picture. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var job: Job? = null

    /** The TA's turn under way, which a tap cuts off. */
    @Volatile
    private var turn: Job? = null

    @Volatile
    private var mic: CallMic? = null

    @Volatile
    private var player: CallPlayer? = null
    private var clearing: Job? = null

    /** Rings the TA of [conversationId]; false while a call is on already. */
    fun start(conversationId: Long): Boolean = start(conversationId, null)

    fun startIncoming(invitation: com.cleo.cleos.data.db.IncomingCallEntity): Boolean = start(invitation.conversationId,invitation)

    suspend fun stopIncoming() {
        if (_state.value?.incoming == true) { hangUp(); job?.join() }
    }

    private fun start(conversationId: Long, invitation: com.cleo.cleos.data.db.IncomingCallEntity?): Boolean {
        synchronized(this) {
            if (job?.isActive == true) return false
            if (!CallAudioOwnership.slot.take(this)) return false
            val session = System.nanoTime()
            _state.value = CallState(conversationId, session = session, incoming = invitation != null)
            job = ownedCallJob(scope, CallAudioOwnership.slot, this, finished = {
                _state.update { if (it?.session == session) it.copy(phase = CallPhase.Ended,
                    endedAt = it.endedAt ?: System.currentTimeMillis(), saying = null) else it }
                scope.launch {
                    delay(ENDED_SHOWN_MS)
                    _state.update { if (it?.session == session && it.phase == CallPhase.Ended) null else it }
                }
            }) {
                try {
                    run(conversationId,invitation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Whatever it was, the call is over; the screen shouldn't stay up ringing.
                    Log.w(TAG, "call failed", e)
                    _state.update { if (it?.phase == CallPhase.Ended) it else null }
                } finally { if(invitation!=null) chat.finishIncoming(invitation) }
            }
            job?.start()
            return true
        }
    }

    /**
     * Quiet, and the screen says the call has ended, at once; putting it away (the row, the service,
     * a voice request still on its way back) finishes behind that.
     */
    fun hangUp() {
        val j = job ?: return
        if (!j.isActive) return
        Log.i(TAG, "hanging up")
        player?.held = true
        player?.stop()
        mic?.muted = true
        _state.update {
            if (it == null || it.phase == CallPhase.Ended) it else it.copy(phase = CallPhase.Ended, endedAt = System.currentTimeMillis(), saying = null)
        }
        j.cancel()
    }

    /** Cuts the TA off, or stops it before it begins: what it was saying stops, and it is the person's turn. */
    fun interrupt() {
        val t = turn ?: return
        t.cancel()
        player?.stop()
    }

    fun mute(on: Boolean) {
        mic?.muted = on
        _state.update { it?.copy(muted = on) }
    }

    private suspend fun run(conversationId: Long, invitation: com.cleo.cleos.data.db.IncomingCallEntity?) {
        val ta = db.conversations().get(conversationId)?.companionId?.let { companions.get(it) }
        if (ta == null) {
            _state.value = null
            return
        }
        if (invitation != null) {
            val conversation=db.conversations().get(conversationId)
            check(ta.id==invitation.companionId && ta.createdAt==invitation.companionCreatedAt && ta.incomingCallsEnabled && ta.freeTopicEnabled &&
                System.currentTimeMillis() < invitation.expiresAt && !IncomingCallRules.quiet(ta,ZonedDateTime.now()) &&
                conversation?.createdAt==invitation.conversationCreatedAt &&
                db.incomingCalls().get(invitation.id)?.status=="connecting") { "这通来电已失效" }
        }
        val s = TaVoices.effective(settings.current(), ta)
        val name = ta.name.trim().ifEmpty { "TA" }
        _state.update { it?.copy(name = name, avatar = ta.avatar, avatarEmoji = ta.avatarEmoji) }
        // Whole or not at all: a call row left half made would keep the conversation busy.
        val callId = withContext(NonCancellable) { chat.beginCall(conversationId,invitation?.recordId) }
        CallService.start(appContext)
        val heard = Channel<MicHeard>(Channel.UNLIMITED)
        val mic = CallMic(micSource()) { heard.trySend(it) }.also { it.muted = _state.value?.muted == true }
        val player = CallPlayer()
        this.mic = mic
        this.player = player
        val focus = Focus(appContext) { focus ->
            val held = focus != CallFocusState.Ready
            mic.held = held
            player.held = held
            _state.update { it?.copy(held = held) }
            if (held) player.stop()
            if (focus == CallFocusState.Lost) {
                problem("声音已被其他应用接管，通话结束了")
                hangUp()
            } else if (held) interrupt()
        }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            coroutineScope {
                val watching = launch { watch(mic, player) }
                if (!focus.take()) {
                    problem("暂时拿不到声音，请结束其他通话后再试")
                    delay(PROBLEM_SHOWN_MS)
                    watching.cancel()
                    return@coroutineScope
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!mic.start()) {
                    problem("话筒打不开，可能正被别的应用用着")
                    delay(PROBLEM_SHOWN_MS)
                    watching.cancel()
                    return@coroutineScope
                }
                if (invitation == null) {
                    val ringing = launch { player.ring() }
                    talk(conversationId, callId, s, name, player, ringing, startedAt)
                } else {
                    answered(callId)
                    talk(conversationId, callId, s, name, player, null, startedAt,IncomingCallRules.opening(invitation.reason),greeting=true)
                }
                // Whether the TA has asked if the person is still there, since they last said anything.
                var asked = false
                while (true) {
                    phase(CallPhase.Listening)
                    mic.listen()
                    var quietSince = SystemClock.elapsedRealtime()
                    // What a pause was made out as, while it may still turn out to be the end.
                    var early: Deferred<Words>? = null
                    var words: Words? = null
                    var turnEnded = 0L
                    while (words == null) {
                        val event = withTimeoutOrNull(QUIET_CHECK_MS) { heard.receive() }
                        if (event != null && !mic.accepts(event)) {
                            early?.cancel()
                            early = null
                            while (heard.tryReceive().isSuccess) { /* Drop this abandoned interval before listening again. */ }
                            mic.listen()
                            continue
                        }
                        when (val e = event?.heard) {
                            null -> {
                                // Muted, someone else's sound, or talking: not quiet on the line.
                                if (mic.muted || mic.held || mic.hearing) quietSince = SystemClock.elapsedRealtime()
                                val quiet = SystemClock.elapsedRealtime() - quietSince
                                if (!asked && quiet >= QUIET_ASK_MS) {
                                    asked = true
                                    talk(conversationId, callId, s, name, player, null, SystemClock.elapsedRealtime(), Prompt.CALL_QUIET)
                                    phase(CallPhase.Listening)
                                    mic.listen()
                                    quietSince = SystemClock.elapsedRealtime()
                                } else if (asked && quiet >= QUIET_HANG_UP_MS) {
                                    problem("好一会儿没声音，电话先挂了")
                                    hangUp()
                                    awaitCancellation()
                                }
                            }
                            is TurnDetector.Paused -> {
                                early?.cancel()
                                early = async { makeOut(e.pcm, s) }
                            }
                            is TurnDetector.Ended -> {
                                turnEnded = SystemClock.elapsedRealtime()
                                phase(CallPhase.Thinking)
                                val reuse = early?.takeIf { e.asPaused }
                                if (reuse == null) early?.cancel()
                                words = reuse?.await() ?: makeOut(e.pcm, s)
                                Log.i(TAG, "timing: words at +${SystemClock.elapsedRealtime() - turnEnded} ms (made out in the pause: ${reuse != null})")
                            }
                        }
                    }
                    words.problem?.let(::problem)
                    val text = words.text ?: continue
                    asked = false
                    _state.update { it?.copy(heard = text) }
                    chat.callLine(conversationId, callId, "user", text)
                    talk(conversationId, callId, s, name, player, null, turnEnded)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                this@Calls.mic = null
                this@Calls.player = null
                runCatching { mic.stop() }
                runCatching { player.release() }
                focus.give()
                heard.close()
                try { chat.endCall(conversationId, callId) }
                finally {
                    CallService.stop(appContext)
                    _level.value = 0f
                    _state.update { it?.copy(phase = CallPhase.Ended, endedAt = it.endedAt ?: System.currentTimeMillis(), saying = null) }
                    Log.i(TAG, "call put away")
                }
            }
        }
    }

    /**
     * The TA's turn, as a job of its own that a tap can cut off. [ringing]: the first turn, the TA
     * picking up: the ringing goes on until its first words are ready. It has picked up once the
     * turn is over, whatever came of it: the person can talk now. [since]: when the call began
     * (picking up), else when the person's turn ended; how long things took is logged from then.
     * [instruction]: a turn nobody asked for (the line has gone quiet), told the TA unseen.
     */
    private suspend fun talk(
        conversationId: Long,
        callId: Long,
        s: AppSettings,
        name: String,
        player: CallPlayer,
        ringing: Job?,
        since: Long,
        instruction: String? = null,
        greeting: Boolean = false,
    ) {
        if (ringing == null) phase(CallPhase.Thinking)
        while (mic?.held == true) delay(50)
        coroutineScope {
            val t = launch { speak(conversationId, callId, s, name, player, ringing, since, instruction, greeting) }
            turn = t
            if (mic?.held == true) t.cancel()
            t.join()
            turn = null
        }
        if (ringing != null) {
            ringing.cancelAndJoin()
            player.stop()
            answered(callId)
        }
    }

    /**
     * What the TA writes, cut into pieces as it comes (Sentences), each made into sound and played
     * while the next is being made. What was played is stored as its line in the call, whether it
     * got to the end or was cut off; what never played was never said.
     */
    private suspend fun speak(
        conversationId: Long,
        callId: Long,
        s: AppSettings,
        name: String,
        player: CallPlayer,
        ringing: Job?,
        since: Long,
        instruction: String?,
        greeting: Boolean,
    ) {
        val said = StringBuilder()
        val timing=CallTiming(diagnostic,_state.value?.incoming==true,since)
        var whole = false
        fun mark(what: String) = Log.i(TAG, "timing: $what at +${SystemClock.elapsedRealtime() - since} ms")
        try {
            coroutineScope {
                val pieces = Channel<String>(Channel.UNLIMITED)
                val sounds = Channel<Piece>(1)
                val writing = launch {
                    val cut = Sentences()
                    var first = true
                    try {
                        timing.measure(CallTimingStage.MODEL) {
                            chat.callReply(conversationId, callId, instruction, greeting,
                                onFirstText={timing.first(CallTimingStage.FIRST_TEXT)}) { delta ->
                                if (first) {
                                    first = false
                                    mark("first words written")
                                }
                                cut.add(delta).forEach { pieces.trySend(it) }
                            }
                        }
                        cut.end()?.let { pieces.trySend(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        problem("${name}那边没声音了：${(e.message ?: e.javaClass.simpleName).lineSequence().first()}")
                    } finally {
                        pieces.close()
                    }
                }
                launch {
                    for (text in pieces) {
                        val words = CallSpeech.clean(text)
                        if (words.isNotEmpty()) {
                            timing.first(CallTimingStage.FIRST_SEGMENT)
                            sounds.send(voiced(s, words, timing))
                        }
                    }
                    sounds.close()
                }
                var heardYet = false
                for (piece in sounds) {
                    if (!heardYet) {
                        heardYet = true
                        mark("first sound ready")
                    }
                    if (ringing != null && ringing.isActive) {
                        // A ring at least: a phone picked up at once sounds like nobody was there yet.
                        delay((since + MIN_RING_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        ringing.cancelAndJoin()
                        player.stop()
                        answered(callId)
                    }
                    _state.update { it?.copy(phase = CallPhase.Speaking, saying = piece.words) }
                    CallSpeech.join(said, piece.words)
                    val pcm = piece.pcm
                    if (pcm != null) {
                        timing.first(CallTimingStage.AUDIO_READY)
                        timing.first(CallTimingStage.PLAYBACK_START)
                        player.play(pcm, piece.rate)
                    } else {
                        // Its words on the screen instead, long enough to read.
                        problem("这句没合成出声音：${piece.why}")
                        delay((piece.words.length * READ_MS_PER_CHAR).coerceAtLeast(1500).toLong())
                    }
                }
                writing.join()
                whole = true
            }
        } finally {
            withContext(NonCancellable) {
                player.stop()
                val line = said.toString().trim()
                if (line.isNotEmpty()) chat.callLine(conversationId, callId, "assistant", if (whole) line else "$line……")
                _state.update { it?.copy(saying = null) }
            }
        }
    }

    private class Piece(val words: String, val pcm: FloatArray?, val rate: Int, val why: String? = null)

    /** [words] in the TA's voice, as samples to play; what went wrong instead, when it can't be. */
    private suspend fun voiced(s: AppSettings, words: String, timing: CallTiming): Piece = try {
        val (bytes, mime) = timing.measure(CallTimingStage.SYNTHESIZE) { speaker.sound(s, words) }
        val pcm = timing.measure(CallTimingStage.DECODE) { withContext(Dispatchers.IO) {
            dir.mkdirs()
            val file = File(dir, "say_${System.nanoTime()}.${Speech.extension(mime)}")
            try {
                file.writeBytes(bytes)
                Pcm.mono(file)
            } finally {
                file.delete()
            }
        } }
        Piece(words, pcm.samples, pcm.rate)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Piece(words, null, 0, e.message ?: e.javaClass.simpleName)
    }

    /** What the person said: in words, or why nothing could be made of it (shown only once it is used). */
    private class Words(val text: String?, val problem: String? = null)

    private suspend fun makeOut(pcm: ShortArray, s: AppSettings): Words {
        val file = File(dir, "heard_${System.nanoTime()}.wav")
        return try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                writeWav(file, pcm)
            }
            val text = transcriber.transcribe(s.voiceBaseUrl, s.voiceModel, file).trim()
            if (text.none { it.isLetterOrDigit() }) Words(null, "没听清，再说一次？") else Words(text)
        } catch (e: ChatException) {
            Words(null, "没听清：${e.message.orEmpty().lineSequence().first()}")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in pcm) data.putShort(v)
        file.outputStream().use {
            it.write(Voice.wavHeader(pcm.size * 2))
            it.write(data.array())
        }
    }

    private suspend fun answered(callId: Long) {
        if (_state.value?.answeredAt != null) return
        val at = System.currentTimeMillis()
        _state.update { it?.copy(answeredAt = at) }
        chat.callAnswered(callId, at)
        CallService.refresh(appContext)
    }

    private fun phase(p: CallPhase) {
        _state.update { if (it?.phase == CallPhase.Ended) it else it?.copy(phase = p) }
    }

    private fun problem(text: String) {
        Log.i(TAG, "problem: $text")
        _state.update { it?.copy(problem = text) }
        clearing?.cancel()
        clearing = scope.launch {
            delay(PROBLEM_SHOWN_MS)
            _state.update { if (it?.problem == text) it.copy(problem = null) else it }
        }
    }

    /** The loudness for the screen, and whether the person has begun talking, while the call lasts. */
    private suspend fun watch(mic: CallMic, player: CallPlayer) {
        while (true) {
            if (mic.failed) {
                problem("话筒连接中断，通话已结束，请重新拨打")
                hangUp()
                return
            }
            val phase = _state.value?.phase ?: return
            _level.value = when (phase) {
                CallPhase.Listening, CallPhase.Hearing -> mic.level
                CallPhase.Speaking -> player.level
                else -> 0f
            }
            val talking = mic.hearing
            _state.update {
                when {
                    it?.phase == CallPhase.Listening && talking -> it.copy(phase = CallPhase.Hearing)
                    it?.phase == CallPhase.Hearing && !talking -> it.copy(phase = CallPhase.Listening)
                    else -> it
                }
            }
            delay(50)
        }
    }

    /** The microphone; on a debuggable build, a recording in the cache stands in for the person (the emulator has no one to talk). */
    private fun micSource(): MicSource {
        val pretend = File(appContext.cacheDir, "pretend-mic.wav")
        val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable && pretend.exists()) PretendMic(pretend) else PhoneMic()
    }

    private companion object {
        const val TAG = "Calls"
        const val MIN_RING_MS = 1800L
        const val PROBLEM_SHOWN_MS = 4000L
        const val ENDED_SHOWN_MS = 1500L
        const val READ_MS_PER_CHAR = 160

        /** How often a quiet line is looked at. */
        const val QUIET_CHECK_MS = 5_000L

        /** Nothing said for this long: the TA asks whether the person is still there. */
        const val QUIET_ASK_MS = 120_000L

        /** And nothing for this long after it asked: it hangs up. */
        const val QUIET_HANG_UP_MS = 60_000L
    }
}

/**
 * What the TA writes, cut into pieces to say as it comes. A piece ends where a sentence does; a
 * long one also at a comma, and the very first at its first comma once there are a few words,
 * to start talking sooner. A piece with hardly any words in it (嗯。) waits for the next one:
 * said on its own it comes out clipped, and costs a request of its own.
 */
class Sentences {
    private val buffer = StringBuilder()
    private var first = true

    /** [delta] added; the pieces it completed, in order. */
    fun add(delta: String): List<String> {
        buffer.append(delta)
        val out = ArrayList<String>()
        while (true) {
            val end = cut() ?: break
            val piece = buffer.substring(0, end).trim()
            buffer.delete(0, end)
            if (piece.isNotEmpty()) {
                out += piece
                first = false
            }
        }
        return out
    }

    /** What is left once the TA is done. */
    fun end(): String? = buffer.toString().trim().ifEmpty { null }.also { buffer.setLength(0) }

    /** Where the first piece in the buffer ends, if it is complete. */
    private fun cut(): Int? {
        var words = 0
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            if (c.isLetterOrDigit()) words++
            val ends = c in ENDS || (c == '.' && i + 1 < buffer.length && buffer[i + 1].isWhitespace())
            when {
                ends && words >= MIN_WORDS -> {
                    // Closing quotes and more of the same marks go with it: 「好。」 好！！ So it is cut
                    // only once something else has come after them (or the TA is done: end).
                    var j = i + 1
                    while (j < buffer.length && (buffer[j] in ENDS || buffer[j] in CLOSERS)) j++
                    return if (j < buffer.length) j else null
                }
                c in PAUSES && words >= (if (first) FIRST_PAUSE_WORDS else LONG_WORDS) -> return i + 1
            }
            i++
        }
        return null
    }

    private companion object {
        const val ENDS = "。！？!?；;…\n～~"
        const val PAUSES = "，,：:"
        const val CLOSERS = "」』”’）)】》\"'"
        const val MIN_WORDS = 4
        const val FIRST_PAUSE_WORDS = 4
        const val LONG_WORDS = 28
    }
}

/** What the TA says, made fit to be read aloud. */
object CallSpeech {
    private val ACTION = Regex("""[（(]([^（）()]{0,40})[）)]""")
    private val BOLD = Regex("""\*\*([^*\n]+)\*\*""")
    private val STARRED = Regex("""\*[^*\n]{1,20}\*""")
    private val MARKS = Regex("""[#*`_>|]+""")
    private val BULLET = Regex("""(?m)^\s*(?:[-•·]|\d+[.、)])\s+""")
    private val SPACES = Regex("""\s+""")

    /**
     * [text] as it can be said: no stickers, no emoji, no Markdown, and nothing in brackets that is
     * an action (（笑）, *摸摸头*). A longer bracket is something said in passing: its words stay.
     */
    fun clean(text: String): String {
        var t = StickerText.without(text)
        // Bold is only emphasis; a word between single stars is an action, like one in brackets.
        t = BOLD.replace(t, "$1")
        t = STARRED.replace(t, "")
        t = ACTION.replace(t) { m -> if (m.groupValues[1].length <= ACTION_MAX) "" else "，${m.groupValues[1]}，" }
        t = BULLET.replace(t, "")
        t = MARKS.replace(t, "")
        t = buildString {
            var i = 0
            while (i < t.length) {
                val cp = t.codePointAt(i)
                if (!emoji(cp)) appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }
        return SPACES.replace(t, " ").trim().trim('，', ',').trim()
    }

    /** Adds [piece] to what was said: pieces of Chinese run on, English ones get their space back. */
    fun join(said: StringBuilder, piece: String) {
        if (said.isNotEmpty() && said.last().code < 128 && !said.last().isWhitespace() && piece.first().code < 128) said.append(' ')
        said.append(piece)
    }

    /**
     * Emoji and their joiners. Symbols below the arrows (°, ©) and the letterlike ones (℃) are
     * read out, so they stay.
     */
    private fun emoji(cp: Int): Boolean =
        (Character.getType(cp) == Character.OTHER_SYMBOL.toInt() && cp >= 0x2190) ||
            cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F

    /** Brackets this short hold an action or a mood, not something said. */
    private const val ACTION_MAX = 8
}

/**
 * Where the person's turn ends, judged on 20 ms frames by how loud each is against the room's own
 * level (the floor, followed while nobody talks): speech is a run of frames well above it, and
 * [END_MS] of quiet after it ends the turn. A little from before the first loud frame is kept, so
 * the first syllable isn't clipped, and a turn with too little voice in it (a cough, a knock on
 * the table) is let go. Halfway there ([PAUSE_MS]) it says so, with what was said so far: making
 * it out can start then, and the wait for the end hides most of what that takes.
 *
 * The levels are relative because phones differ: Android asks for a 90 dB tone to come out of
 * the voice-recognition microphone at an RMS of 2500, which puts speech at arm's length near 150
 * and a quiet room near 10, but phones are only roughly there.
 */
class TurnDetector {
    sealed interface Heard

    /** Quiet long enough that the turn may be over: what was said so far, to start making out. */
    class Paused(val pcm: ShortArray) : Heard

    /** The turn is over. [asPaused]: nothing was said since the last [Paused], whose words these are too. */
    class Ended(val pcm: ShortArray, val asPaused: Boolean) : Heard

    private var floor = -1.0
    private var peak = 0.0
    /** The last second's loudness, frame by frame. */
    private val recent = ArrayDeque<Double>()
    private val before = ArrayDeque<ShortArray>()
    private val onset = ArrayDeque<Boolean>()
    private var turn: MutableList<ShortArray>? = null
    private var quiet = 0
    private var voiced = 0

    /** A [Paused] went out, and nothing has been said since. */
    private var paused = false

    /** How loud the last frame was, 0 to 1, on a scale that suits the eye. */
    var level = 0f
        private set

    /** Whether someone is talking: a turn is under way. */
    val speaking: Boolean get() = turn != null

    /** One frame ([n] samples of it); a pause or the end of the turn, when this frame made one. */
    fun feed(frame: ShortArray, n: Int = frame.size): Heard? {
        val rms = rms(frame, n)
        level = ((20 * log10(max(rms, 1.0)) - 20) / 50).coerceIn(0.0, 1.0).toFloat()
        if (floor < 0) floor = min(rms, FLOOR_START_MAX)
        recent.addLast(rms)
        if (recent.size > WINDOW_FRAMES) recent.removeFirst()
        val copy = frame.copyOf(n)
        val current = turn
        if (current == null) {
            val loud = rms > max(floor * ONSET_RATIO, ONSET_MIN)
            onset.addLast(loud)
            if (onset.size > ONSET_WINDOW) onset.removeFirst()
            before.addLast(copy)
            if (before.size > PRE_FRAMES) before.removeFirst()
            if (onset.count { it } >= ONSET_FRAMES) {
                turn = ArrayList(before)
                before.clear()
                onset.clear()
                quiet = 0
                voiced = ONSET_FRAMES
                paused = false
                peak = rms
            } else if (!loud) {
                // The room, while nobody talks: followed down quickly, up slowly. A loud frame is
                // someone starting to talk, not the room: counted, the room's level crept up with
                // every turn until speech no longer stood out from it.
                floor += (rms - floor) * (if (rms < floor) 0.2 else 0.01)
                floor = floor.coerceIn(FLOOR_MIN, FLOOR_MAX)
            }
            return null
        }
        current += copy
        peak = max(peak, rms)
        // A steady sound (a fan, a street) is the room getting louder, not someone talking: speech
        // rises and falls by far more than this within any second. Without this a noisy room would
        // never let a turn end.
        val low = recent.min()
        if (recent.size == WINDOW_FRAMES && low > floor && low > recent.max() * STEADY_SHARE) {
            floor = min(FLOOR_MAX, floor + (low - floor) * 0.05)
        }
        // Voice: above the room, and not far below how loud this person is talking.
        if (rms > maxOf(floor * VOICED_RATIO, VOICED_MIN, peak * PEAK_SHARE)) {
            voiced++
            quiet = 0
            paused = false
        } else {
            quiet++
            // The pauses show the room too: when someone starts talking the moment it is their
            // turn, they are where its level is learned.
            if (rms < floor) floor = max(FLOOR_MIN, floor + (rms - floor) * 0.2)
        }
        // The quiet at the end, but for a little of it: the same frames at the pause and at the end.
        fun said() = join(current.subList(0, current.size - max(0, quiet - TAIL_FRAMES)))
        if (quiet * FRAME_MS >= END_MS || current.size * FRAME_MS >= Voice.MAX_MS) {
            turn = null
            if (voiced * FRAME_MS < MIN_VOICED_MS) return null
            return Ended(said(), asPaused = paused)
        }
        if (quiet * FRAME_MS == PAUSE_MS && voiced * FRAME_MS >= MIN_VOICED_MS) {
            paused = true
            return Paused(said())
        }
        return null
    }

    /** A turn under way is dropped; what is known of the room stays. */
    fun reset() {
        turn = null
        recent.clear()
        before.clear()
        onset.clear()
        quiet = 0
        voiced = 0
    }

    private fun rms(frame: ShortArray, n: Int): Double {
        if (n <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
        return sqrt(sum / n)
    }

    private fun join(frames: List<ShortArray>): ShortArray {
        val out = ShortArray(frames.sumOf { it.size })
        var at = 0
        for (f in frames) {
            f.copyInto(out, at)
            at += f.size
        }
        return out
    }

    companion object {
        /** 20 ms at Voice.RATE. */
        const val FRAME = Voice.RATE / 50
        const val FRAME_MS = 20

        /** Quiet this long ends a turn: long enough for a pause to think, short enough not to wait on. */
        const val END_MS = 1200

        /** Quiet this long and the turn may be over: what was said starts being made out. */
        const val PAUSE_MS = 600
        private const val PRE_FRAMES = 15
        private const val ONSET_WINDOW = 6
        private const val ONSET_FRAMES = 4
        private const val ONSET_RATIO = 3.0
        private const val ONSET_MIN = 60.0
        private const val VOICED_RATIO = 2.0
        private const val VOICED_MIN = 40.0
        private const val PEAK_SHARE = 0.08
        private const val MIN_VOICED_MS = 300
        private const val TAIL_FRAMES = 15
        private const val FLOOR_MIN = 5.0
        private const val FLOOR_MAX = 3000.0
        private const val FLOOR_START_MAX = 300.0
        private const val WINDOW_FRAMES = 50

        /** Over a second, a sound whose quietest moment is this close to its loudest is steady. */
        private const val STEADY_SHARE = 0.4
    }
}

internal class PhoneMic : MicSource {
    @Volatile private var record: AudioRecord? = null

    /** The permission is asked for before a call can begin. */
    @SuppressLint("MissingPermission")
    override fun open(): Boolean {
        val min = AudioRecord.getMinBufferSize(Voice.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Voice.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, Voice.RATE))
        }.getOrNull() ?: return false
        if (r.state != AudioRecord.STATE_INITIALIZED || runCatching { r.startRecording() }.isFailure ||
            r.recordingState != AudioRecord.RECORDSTATE_RECORDING
        ) {
            r.release()
            return false
        }
        record = r
        return true
    }

    override fun read(buffer: ShortArray): Int = record?.read(buffer, 0, buffer.size) ?: -1

    @Synchronized override fun close() {
        val current = record
        record = null
        current?.let {
            runCatching { it.stop() }
            it.release()
        }
    }
}

/** A recording (16 kHz mono WAV) said once each time it is the person's turn, then quiet: for the emulator. */
private class PretendMic(private val file: File) : MicSource {
    private var samples = ShortArray(0)
    private var at = 0
    private var next = 0L

    override fun open(): Boolean {
        val bytes = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size > 44 } ?: return false
        val data = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        samples = ShortArray(data.remaining()).also { data.get(it) }
        at = samples.size
        return true
    }

    override fun read(buffer: ShortArray): Int {
        // As fast as a microphone would give it.
        val now = SystemClock.elapsedRealtime()
        if (next == 0L) next = now
        next += buffer.size * 1000L / Voice.RATE
        if (next > now) Thread.sleep(next - now)
        for (i in buffer.indices) buffer[i] = if (at < samples.size) samples[at++] else ((Math.random() * 16) - 8).toInt().toShort()
        return buffer.size
    }

    override fun rewind() {
        at = 0
    }

    override fun close() {}
}

/**
 * The TA's voice in a call, and the ringing before it picks up: one AudioTrack, played as media
 * (the speaker, or whatever is on the ears), opened again only for sound at another rate.
 */
internal class CallPlayer {
    @Volatile var held = false
    @Volatile
    private var track: AudioTrack? = null
    private var rate = 0

    /** How loud what is being played is, 0 to 1. */
    @Volatile
    var level = 0f
        private set

    /** [samples] (mono, -1 to 1, at [rate]) played to the end; cut off when the caller is cancelled. */
    suspend fun play(samples: FloatArray, rate: Int): Boolean = withContext(Dispatchers.IO) {
        if (held) throw CancellationException("Call audio paused")
        val t = open(rate) ?: return@withContext false
        val pcm = pcm16(samples, rate)
        val head = t.playbackHeadPosition
        val chunk = rate / 20
        try {
            var off = 0
            while (off < pcm.size) {
                ensureActive()
                if (held) throw CancellationException("Call audio paused")
                val n = min(chunk, pcm.size - off)
                level = loudness(pcm, off, n)
                val written = t.write(pcm, off, n)
                if (written <= 0) break
                off += written
            }
            level = 0f
            // Until it has been heard, not only handed over.
            val until = SystemClock.elapsedRealtime() + pcm.size * 1000L / rate + 1500
            while (t.playbackHeadPosition - head < pcm.size && SystemClock.elapsedRealtime() < until) {
                if (held) throw CancellationException("Call audio paused")
                delay(15)
            }
            off == pcm.size && t.playbackHeadPosition - head >= pcm.size
        } finally {
            level = 0f
        }
    }

    /** The ringing tone China's phones play: 450 Hz, a second on and four off, until cancelled. */
    suspend fun ring() = withContext(Dispatchers.IO) {
        val r = Voice.RATE
        val t = open(r) ?: return@withContext
        val edge = r / 50
        val tone = ShortArray(r) { i ->
            val fade = min(1.0, min(i, r - 1 - i).toDouble() / edge)
            (sin(2 * PI * 450 * i / r) * fade * 0.16 * Short.MAX_VALUE).toInt().toShort()
        }
        val gap = ShortArray(r / 10)
        while (isActive) {
            if (held) { delay(50); continue }
            var off = 0
            while (off < tone.size && isActive && !held) off += max(1, t.write(tone, off, min(r / 10, tone.size - off)))
            repeat(40) { if (isActive && !held) t.write(gap, 0, gap.size) }
        }
    }

    /** Quiet at once: what is queued is thrown away. */
    fun stop() {
        val t = track ?: return
        runCatching {
            t.pause()
            t.flush()
            t.play()
        }
    }

    fun release() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    private fun open(rate: Int): AudioTrack? {
        track?.takeIf { this.rate == rate }?.let { return it }
        release()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(ATTRIBUTES)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(min, rate / 5 * 2))
                .build()
        }.getOrNull() ?: return null
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return null
        }
        t.play()
        track = t
        this.rate = rate
        return t
    }

    /** As 16-bit samples, eased in and out against clicks, with a breath of quiet before and after. */
    private fun pcm16(samples: FloatArray, rate: Int): ShortArray {
        val lead = rate * LEAD_MS / 1000
        val tail = rate * TAIL_MS / 1000
        val edge = rate * FADE_MS / 1000
        val out = ShortArray(lead + samples.size + tail)
        for (i in samples.indices) {
            val fade = min(1f, min(i, samples.size - 1 - i).toFloat() / edge)
            out[lead + i] = (samples[i].coerceIn(-1f, 1f) * fade * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun loudness(pcm: ShortArray, off: Int, n: Int): Float {
        var sum = 0.0
        for (i in off until off + n) sum += pcm[i].toDouble() * pcm[i]
        val rms = sqrt(sum / max(1, n))
        return ((20 * log10(max(rms, 1.0)) - 40) / 45).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        const val LEAD_MS = 40
        const val TAIL_MS = 160
        const val FADE_MS = 8
    }
}

/**
 * The sound for the length of a call: other apps are asked to pause (music stops, and comes back
 * after). Another app taking it ([changed] true) holds the call until it is given back.
 */
internal class Focus(context: Context, changed: (CallFocusState) -> Unit) {
    private val lease = CallFocusLease(changed)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(CallPlayer.ATTRIBUTES)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> lease.change(CallFocusState.Ready)
                AudioManager.AUDIOFOCUS_LOSS -> lease.change(CallFocusState.Lost)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> lease.change(CallFocusState.Paused)
            }
        }
        .build()

    fun take(): Boolean = lease.take { audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }

    fun give() {
        lease.give { audio?.abandonAudioFocusRequest(request) }
    }
}
