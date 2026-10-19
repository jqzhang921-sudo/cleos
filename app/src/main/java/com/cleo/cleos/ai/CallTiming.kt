package com.cleo.cleos.ai

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** Closed stage names and monotonic durations only; no TA names, words, URLs or keys. */
internal enum class CallTimingStage(val label: String) {
    MODEL("model"), FIRST_TEXT("first_text"), FIRST_SEGMENT("first_segment"),
    SYNTHESIZE("synthesize"), DECODE("decode"), AUDIO_READY("audio_ready"), PLAYBACK_START("playback_start")
}

internal class CallTiming(private val diagnostic: (String) -> Unit, incoming: Boolean,
    private val since: Long, private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val id: String = UUID.randomUUID().toString().take(8)) {
    private val route=if(incoming) "incoming_call" else "private_call"
    private val first=mutableSetOf<CallTimingStage>()
    init { require(id.matches(Regex("[a-f0-9]{8}"))) }
    private fun ms(start: Long)=(clock()-start).coerceIn(0,86_400_000)
    private fun record(stage: CallTimingStage, result: String, elapsed: Long, category: String? = null) {
        runCatching { diagnostic("event=call_stage route=$route requestId=$id stage=${stage.label} result=$result elapsedMs=$elapsed" +
            category?.let { " category=$it" }.orEmpty()) }
    }
    fun first(stage: CallTimingStage) {
        val fresh=synchronized(first) { first.add(stage) }
        if(fresh) record(stage,"ok",ms(since))
    }
    suspend fun <T> measure(stage: CallTimingStage, block: suspend () -> T): T {
        val start=clock()
        record(stage,"start",0)
        try { return block().also { record(stage,"ok",ms(start)) } }
        catch(e:CancellationException){record(stage,"cancelled",ms(start));throw e}
        catch(e:Exception){record(stage,"failed",ms(start),GroupCallTrace.category(e));throw e}
    }
}
