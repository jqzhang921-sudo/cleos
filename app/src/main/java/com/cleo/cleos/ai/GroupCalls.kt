package com.cleo.cleos.ai

import android.content.Context
import com.cleo.cleos.CallService
import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GroupCallState(val session: Long, val groupId: Long, val title: String = "一起通话",
    val members: List<GroupMemberEntity> = emptyList(), val phase: CallPhase = CallPhase.Ringing,
    val answeredAt: Long? = null, val endedAt: Long? = null, val speakerId: Long? = null,
    val muted: Boolean = false, val held: Boolean = false, val heard: String? = null,
    val saying: String? = null, val problem: String? = null, val preparingVoice: Boolean = false,
    /** The person hung up: leave the call page even while durable writes are finishing. */
    val hangUpRequested: Boolean = false)

private class GroupCallSetupException(message: String) : Exception(message)

/** Half-duplex group calls: one microphone, sequential models and one playback owner. */
class GroupCalls(context: Context, private val db: AppDatabase, private val groups: GroupChatRepository,
    private val chat: ChatRepository, private val settings: SettingsRepository, private val secrets: SecretStore,
    private val transcriber: Transcriber, private val speaker: Speaker, private val scope: CoroutineScope,
    private val diagnostic: (String) -> Unit = {}) {
    private val context = context.applicationContext
    private val dir = File(context.cacheDir, "group-call")
    private val _state = MutableStateFlow<GroupCallState?>(null)
    val state = _state.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level = _level.asStateFlow()
    private var job: Job? = null
    @Volatile private var turn: Job? = null
    @Volatile private var mic: CallMic? = null
    @Volatile private var player: CallPlayer? = null

    fun start(groupId: Long, participants: List<Long>, expectedCreatedAt: Long): Boolean = synchronized(this) {
        if (job?.isActive == true || !CallAudioOwnership.slot.take(this)) return false
        val session = System.nanoTime()
        _state.value = GroupCallState(session, groupId)
        val next = ownedCallJob(scope, CallAudioOwnership.slot, this, finished = {
                _state.update { if (it?.session != session) it else if (it.hangUpRequested) null else it.copy(phase = CallPhase.Ended,
                    endedAt = it.endedAt ?: System.currentTimeMillis(), speakerId = null, saying = null) }
                if (_state.value?.session == session) scope.launch {
                    delay(if (_state.value?.problem == null) 1800 else 5000)
                    _state.update { if (it?.session == session && it.phase == CallPhase.Ended) null else it }
                }
        }) {
            try { run(groupId, participants.toList(), expectedCreatedAt) }
            catch (e: CancellationException) { throw e }
            catch (e: GroupCallSetupException) { problem(e.message ?: "请先配置声音服务") }
            catch (_: Exception) { problem("这次通话没有接通，请检查声音、转写和成员模型设置") }
        }
        job = next
        next.start()
        true
    }

    fun hangUp() = end(requested = true)

    private fun end(requested: Boolean = false) {
        _state.update { it?.copy(phase = CallPhase.Ended, endedAt = it.endedAt ?: System.currentTimeMillis(), saying = null,
            hangUpRequested = requested || it.hangUpRequested) }
        mic?.muted = true
        turn?.cancel()
        player?.held = true
        player?.stop()
        job?.cancel()
    }
    fun interrupt() { turn?.cancel(); player?.stop() }
    fun mute(value: Boolean) { mic?.muted = value; _state.update { it?.copy(muted = value) } }
    private fun problem(text: String) { _state.update { it?.copy(problem = text) } }
    private fun setup(ok: Boolean, message: String) { if (!ok) throw GroupCallSetupException(message) }
    private fun phase(value: CallPhase, target: Long? = null, words: String? = null) {
        _state.update { if (it?.phase == CallPhase.Ended) it else it?.copy(phase = value, speakerId = target, saying = words, preparingVoice = false) }
    }

    private suspend fun run(id: Long, participants: List<Long>, expectedCreatedAt: Long) {
        var lease: GroupChatRepository.CallLease? = null
        var privateHeld = false
        var audio: CallPlayer? = null
        var microphone: CallMic? = null
        var focus: Focus? = null
        val heard = Channel<MicHeard>(1)
        try {
            val held = groups.reserveCall(id, participants, currentCoroutineContext().job, expectedCreatedAt).also { lease = it }
            val conversations = held.tas.flatMap { db.conversations().idsFor(it.id) }.toSet()
            check(chat.reserveGroupCall(conversations)) { "有一位 TA 正在私聊，请稍后再试" }
            privateHeld = true
            val common = settings.current()
            setup(common.voiceBaseUrl.isNotBlank() && common.voiceModel.isNotBlank() && !secrets.key(common.voiceBaseUrl).isNullOrBlank(),
                "请先在设置 › 发语音里配好转写服务与 Key")
            val voices = held.tas.associate { ta ->
                val voice = TaVoices.effective(common, ta)
                setup(Speech.ready(voice), "请先在 TA 的声音里为${ta.name}配好音色")
                val service = requireNotNull(Speech.service(voice))
                setup(!secrets.key(Speech.keyAddress(voice, service)).isNullOrBlank(), "请先配置声音平台的 Key")
                val model = ta.modelFor(heard = true)
                setup(model.model.isNotBlank() && !secrets.key(model.baseUrl).isNullOrBlank(), "请先为${ta.name}配置模型与 Key")
                ta.id to voice
            }
            currentCoroutineContext().ensureActive()
            _state.update { it?.copy(title = held.group.title, members = held.members) }
            val sound = CallPlayer().also { audio = it; player = it }
            val input = CallMic(PhoneMic()) { if (it.heard is TurnDetector.Ended) heard.trySend(it) }.also {
                microphone = it; mic = it; it.muted = _state.value?.muted == true
            }
            focus = Focus(context) { focus ->
                val paused = focus != CallFocusState.Ready
                input.held = paused
                sound.held = paused
                _state.update { it?.copy(held = paused) }
                if (paused) sound.stop()
                if (focus == CallFocusState.Lost) {
                    problem("声音已被其他应用接管，通话结束了")
                    end()
                } else if (paused) interrupt()
            }
            // Started from the foreground after the user's permission and confirmation.
            CallService.start(context)
            setup(focus.take(), "暂时拿不到声音，请结束其他通话后再试")
            currentCoroutineContext().ensureActive()
            setup(input.start(), "话筒打不开，可能正被别的应用占用")
            _state.update { it?.copy(answeredAt = System.currentTimeMillis()) }
            CallService.refresh(context)
            coroutineScope {
                val watching = launch {
                    while (isActive) {
                        if (input.failed) {
                            problem("话筒连接中断，通话已结束，请重新拨打")
                            end()
                            return@launch
                        }
                        val state = _state.value
                        _level.value = if (state?.phase == CallPhase.Speaking) sound.level else input.level
                        if (state?.phase in setOf(CallPhase.Listening, CallPhase.Hearing))
                            phase(if (input.hearing) CallPhase.Hearing else CallPhase.Listening)
                        delay(50)
                    }
                }
                try {
                    val rounds = GroupCallRounds(participants)
                    var quiet = 0
                    while (isActive) {
                        while (heard.tryReceive().isSuccess) { /* Discard stale detection before reopening input. */ }
                        phase(CallPhase.Listening)
                        input.listen()
                        var speech: MicHeard? = null
                        while (speech == null) {
                            speech = withTimeoutOrNull(15_000) { heard.receive() }
                            if (speech != null && !input.accepts(speech)) {
                                speech = null
                                input.listen()
                                continue
                            }
                            if (speech == null) {
                                if (input.muted || input.held || input.hearing) quiet = 0 else quiet += 15
                                if (quiet >= 180) { problem("好一会儿没有说话，通话先结束了"); return@coroutineScope }
                            }
                        }
                        val clip = speech
                        quiet = 0
                        // This child owns ASR, generation and playback; tapping cancels the entire round.
                        val current = launch {
                            val trace = GroupCallTrace(diagnostic)
                            try {
                                if (!input.accepts(clip)) return@launch
                                phase(CallPhase.Thinking)
                                val samples = (clip.heard as TurnDetector.Ended).pcm
                                trace.audio(samples.size * 1000L / Voice.RATE)
                                var position = 0
                                rounds.run({ trace.measure(GroupCallStep.TRANSCRIBE) { transcribe(samples, common) } },
                                    { words, order -> trace.measure(GroupCallStep.STORE) { groups.callRound(held, words, order) } },
                                    { slot -> speak(held, slot, voices.getValue(requireNotNull(slot.speakerId)), sound, trace.member(++position)) },
                                    { slots -> trace.measure(GroupCallStep.STORE) { groups.finishCallRound(held, slots) } },
                                    { words -> _state.update { it?.copy(heard = words, problem = null) } })
                                trace.complete()
                            } catch (e: GroupCallTranscriptionTimeoutException) {
                                trace.failure(GroupCallStep.TRANSCRIBE, e); problem(e.message!!)
                            } catch (e: GroupCallInputException) { problem(e.message!!) }
                            catch (e: GroupCallPersistenceException) { trace.failure(GroupCallStep.STORE, e); problem(e.message!!); end() }
                            catch (e: CancellationException) { trace.cancelled(); throw e }
                            catch (e: GroupCallStepException) { problem(GroupCallTrace.message(e.step, e.cause as Exception)) }
                            catch (e: Exception) {
                                trace.failure(GroupCallStep.ROUND, e)
                                problem("这段话没能处理完，请再说一次；已说完的内容保留在群里")
                            }
                        }
                        turn = current
                        if (input.held) current.cancel()
                        try { current.join() } finally { if (turn === current) turn = null; sound.stop() }
                    }
                } finally { watching.cancel() }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                mic = null; player = null
                runCatching { microphone?.stop() }; runCatching { audio?.release() }; runCatching { focus?.give() }; heard.close()
                CallService.stop(context)
                _level.value = 0f
                lease?.let { groups.releaseCall(it) }
                if (privateHeld) chat.releaseGroupCall()
            }
        }
    }

    private suspend fun speak(lease: GroupChatRepository.CallLease, slot: GroupMessageEntity, voice: AppSettings, audio: CallPlayer,
        trace: GroupCallTrace) {
        val completed = StringBuilder()
        var unfinished = ""
        var success = false
        var cancelled = false
        try {
            phase(CallPhase.Thinking, slot.speakerId)
            val ok = withTimeoutOrNull(GroupCallRules.MEMBER_TIMEOUT_MS) {
                val events = trace.model { groups.callReply(lease, slot) }
                GroupCallSpeech.play(events, { text ->
                    _state.update { state ->
                        if (state?.phase == CallPhase.Thinking && state.speakerId == slot.speakerId) state.copy(preparingVoice = true) else state
                    }
                    val (bytes, mime) = trace.measure(GroupCallStep.SYNTHESIZE) { speaker.sound(voice, text) }
                    trace.measure(GroupCallStep.DECODE) { withContext(Dispatchers.IO) {
                        val decoding = currentCoroutineContext()
                        dir.mkdirs()
                        val file = File(dir, "say_${System.nanoTime()}.${Speech.extension(mime)}")
                        try { file.writeBytes(bytes); Pcm.mono(file) { decoding.ensureActive() } } finally { file.delete() }
                    } }
                }, { pcm -> trace.measure(GroupCallStep.PLAY) { check(audio.play(pcm.samples, pcm.rate)) { "声音没有完整播放" } } },
                    { text -> trace.firstPlayback(); unfinished = text; phase(CallPhase.Speaking, slot.speakerId, text) },
                    { text ->
                        CallSpeech.join(completed, text); unfinished = ""
                        withContext(NonCancellable) {
                            try { trace.measure(GroupCallStep.STORE) { groups.callSpoken(lease, slot, GroupCallRules.transcript(completed.toString(), "")) } }
                            catch (e: Exception) { throw GroupCallPersistenceException(e) }
                        }
                    })
                true
            } ?: false
            success = ok
            if (!ok) {
                trace.failure(GroupCallStep.REPLY, java.util.concurrent.TimeoutException())
                problem("${slot.speakerName}这边等待太久，先请下一位接话")
            }
        } catch (e: CancellationException) { trace.cancelled(); cancelled = true; throw e }
        catch (e: GroupCallPersistenceException) { throw e }
        catch (e: GroupCallStepException) { problem("${slot.speakerName}：${GroupCallTrace.message(e.step, e.cause as Exception)}") }
        catch (e: Exception) {
            trace.failure(GroupCallStep.REPLY, e)
            problem("${slot.speakerName}这段声音没能说完，先继续其他成员")
        }
        finally {
            withContext(NonCancellable) {
                audio.stop()
                try {
                    trace.measure(GroupCallStep.STORE) { groups.saveCallReply(lease, slot, GroupCallRules.transcript(completed.toString(), unfinished), success, cancelled) }
                    if (success) trace.complete()
                }
                catch (e: Exception) { throw GroupCallPersistenceException(e) }
            }
        }
    }

    private suspend fun transcribe(samples: ShortArray, s: AppSettings): String {
        val file = File(dir, "heard_${System.nanoTime()}.wav")
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                samples.forEach(data::putShort)
                file.outputStream().use { it.write(Voice.wavHeader(data.array().size)); it.write(data.array()) }
            }
            return GroupCallRules.userWords(transcriber.transcribe(s.voiceBaseUrl, s.voiceModel, file)).substringAfter('\n')
        } finally { withContext(NonCancellable + Dispatchers.IO) { file.delete() } }
    }
}
