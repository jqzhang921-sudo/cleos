package com.cleo.cleos.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

internal enum class GroupCallStep(val label: String) {
    INPUT("input"), TRANSCRIBE("transcribe"), MODEL("model"), SYNTHESIZE("synthesize"),
    DECODE("decode"), PLAY("play"), STORE("store"), REPLY("reply"), ROUND("round")
}

internal class GroupCallInputException(val empty: Boolean) : IllegalArgumentException(
    if (empty) "刚才没听清，可以再说一句" else "这段话太长，请分短一点再说")

internal class GroupCallStepException(val step: GroupCallStep, cause: Exception) : Exception("群通话阶段未完成", cause)

/** One random round ID, ordinal members, closed result labels and monotonic durations only. */
internal class GroupCallTrace(private val diagnostic: (String) -> Unit,
    private val clock: () -> Long = System::nanoTime,
    private val requestId: String = UUID.randomUUID().toString().take(8),
    private val member: Int = 0) {
    private val started = clock()
    private val firstAudio = AtomicBoolean()
    init { require(requestId.matches(Regex("[a-f0-9]{8}")) && member in 0..2) }
    fun member(position: Int) = GroupCallTrace(diagnostic, clock, requestId, position.also { require(it in 1..2) })
    private fun ms(start: Long) = ((clock() - start).coerceAtLeast(0) / 1_000_000).coerceAtMost(86_400_000)
    private fun record(event: String, step: String, result: String, elapsed: Long, category: String? = null, status: Int? = null) {
        val line = "event=$event route=group_call requestId=$requestId member=$member stage=$step result=$result elapsedMs=$elapsed" +
            category?.let { " category=$it" }.orEmpty() + status?.let { " status=$it" }.orEmpty()
        runCatching { diagnostic(line) } // Diagnostics cannot cancel speech or storage.
    }
    fun audio(ms: Long) { runCatching { diagnostic("event=group_call_audio route=group_call requestId=$requestId audioMs=${ms.coerceIn(0, 60_000)}") } }
    fun complete() = record("group_call_stage", if (member == 0) "round" else "reply", "ok", ms(started))
    fun cancelled() = record("group_call_stage", if (member == 0) "round" else "reply", "cancelled", ms(started))
    fun firstPlayback() { if (firstAudio.compareAndSet(false, true)) record("group_call_stage", "first_audio", "ok", ms(started)) }
    fun failure(step: GroupCallStep, error: Exception, elapsed: Long = ms(started)) {
        val cause = if (error is GroupCallStepException) error.cause as? Exception ?: error else error
        val event = if (cause is GroupCallInputException) "group_call_input" else "group_call_failure"
        val category = when (step) {
            GroupCallStep.STORE -> "storage"
            GroupCallStep.DECODE -> "decode_error"
            GroupCallStep.PLAY -> "playback_error"
            else -> category(cause)
        }
        record(event, step.label, "failed", elapsed, category, (cause as? ChatException)?.status?.takeIf { it in 100..599 })
    }
    suspend fun <T> measure(step: GroupCallStep, block: suspend () -> T): T {
        val start = clock()
        record("group_call_stage", step.label, "start", 0)
        try { return block().also { record("group_call_stage", step.label, "ok", ms(start)) } }
        catch (e: CancellationException) { record("group_call_stage", step.label, "cancelled", ms(start)); throw e }
        catch (e: Exception) {
            failure(step, e, ms(start))
            if (e is GroupCallInputException || e is GroupCallPersistenceException || e is GroupCallStepException) throw e
            throw GroupCallStepException(step, e)
        }
    }
    fun model(source: suspend () -> Flow<ChatEvent>): Flow<ChatEvent> = flow {
        val start = clock()
        var first = true
        measure(GroupCallStep.MODEL) {
            source().collect { event ->
                if (first && event is ChatEvent.Delta && event.text.isNotBlank()) {
                    first = false
                    record("group_call_stage", "first_text", "ok", ms(start))
                }
                emit(event)
            }
        }
    }

    companion object {
        fun category(error: Exception): String {
            if (error is GroupCallInputException) return if (error.empty) "empty_transcript" else "transcript_too_long"
            if (error is GroupCallPersistenceException) return "storage"
            if (error is GroupCallTranscriptionTimeoutException || error is TimeoutException) return "timeout"
            if (error is ChatException && error.status?.let { it in 100..599 } == true) return "http_${error.status}"
            when (error.cause ?: error) {
                is UnknownHostException -> return "dns"
                is SocketTimeoutException -> return "timeout"
                is SSLException -> return "tls"
                is IOException -> return "network"
            }
            if (error is SpeechException) return when {
                error.category.matches(Regex("http_[1-5][0-9]{2}")) -> error.category
                error.category in setOf("empty_audio", "not_audio", "network_timeout", "network_cancelled", "network_closed", "network_eof", "network_reset") -> error.category
                error.category.startsWith("network_") -> "network"
                else -> "speech_error"
            }
            if (error is ChatException) return if (error.wrongEndpoint) "invalid_response" else "chat_error"
            return "unexpected"
        }
        fun message(step: GroupCallStep, error: Exception): String {
            if (error is GroupCallInputException) return error.message.orEmpty()
            val category = category(error)
            if (step == GroupCallStep.TRANSCRIBE) return when (category) {
                "http_401", "http_403" -> "转写服务的 Key 不可用，请检查「发语音」设置"
                "http_402" -> "转写服务的余额不足，请检查账户"
                "http_429" -> "转写服务暂时繁忙或额度不足，请稍后再说一次"
                "timeout" -> "转写等得太久，请再说一次"
                "invalid_response" -> "服务没有返回转写结果，请检查转写接口和模型设置"
                else -> "这段语音没能转成文字，请再说一次；若反复出现，请检查转写服务与网络"
            }
            return when (step) {
                GroupCallStep.SYNTHESIZE -> "声音暂时没能生成，先继续其他成员"
                GroupCallStep.DECODE -> "返回的声音暂时无法读取，先继续其他成员"
                GroupCallStep.PLAY -> "这段声音没能完整播放，先继续其他成员"
                GroupCallStep.STORE -> "通话记录没能保存，请结束后重试"
                else -> "模型暂时没能回复，先继续其他成员；可以检查通话模型与网络"
            }
        }
    }
}
