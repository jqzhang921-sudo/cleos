package com.cleo.cleos.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** close must wake a blocked read; one source belongs to just one call. */
internal interface MicSource {
    fun open(): Boolean
    fun read(buffer: ShortArray): Int
    fun rewind() {}
    fun close()
}

/** A detection belongs to one uninterrupted listening interval, including queued detections. */
internal class MicHeard internal constructor(val interval: Long, val heard: TurnDetector.Heard)

internal sealed interface CallUtterance {
    data class Audio(val event: MicHeard) : CallUtterance
    data class Text(val words: String) : CallUtterance
}

/** One input stream per call. Only microphone input needs transcription. */
internal class CallInput(source: MicSource, keepPauses: Boolean) {
    private val audio = Channel<MicHeard>(if (keepPauses) Channel.UNLIMITED else 1)
    private val text = Channel<String>(1)
    val mic = CallMic(source) {
        if (keepPauses || it.heard is TurnDetector.Ended) audio.trySend(it)
    }

    fun send(words: String): Boolean = text.trySend(words).isSuccess

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun receive(timeoutMs: Long): CallUtterance? = select {
        text.onReceive { CallUtterance.Text(it) }
        audio.onReceive { CallUtterance.Audio(it) }
        onTimeout(timeoutMs) { null }
    }

    fun close() {
        text.cancel()
        audio.cancel()
    }

    companion object {
        const val MAX_TEXT = 2000
    }
}

internal class CallMic(private val source: MicSource, private val onHeard: (MicHeard) -> Unit) {
    private val gate = Any()
    private val closed = AtomicBoolean(false)
    private var started = false
    private var interval = 0L
    private var rewind = false
    private var listening = false
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile var failed = false
        private set
    @Volatile var level = 0f
        private set
    @Volatile var hearing = false
        private set

    @Volatile var muted = false
        set(value) = synchronized(gate) {
            if (field != value) { field = value; invalidate() }
        }
    @Volatile var held = false
        set(value) = synchronized(gate) {
            if (field != value) { field = value; invalidate() }
        }
    @Volatile var typing = false
        set(value) = synchronized(gate) {
            if (field != value) { field = value; invalidate() }
        }

    fun start(): Boolean = synchronized(gate) {
        if (started || closed.get()) return false
        started = true
        if (!runCatching { source.open() }.getOrDefault(false)) {
            closeSource()
            return false
        }
        running = true
        worker = thread(start = false, isDaemon = true, name = "call-mic") { loop() }
        worker!!.start()
        true
    }

    fun listen() = synchronized(gate) {
        invalidate()
        rewind = true
        listening = true
    }

    fun accepts(event: MicHeard): Boolean = synchronized(gate) {
        running && !muted && !held && !typing && event.interval == interval
    }

    fun pause() = synchronized(gate) {
        listening = false
        invalidate()
    }

    fun stop() {
        val reader = synchronized(gate) {
            running = false
            listening = false
            invalidate()
            worker
        }
        // Stop/close first: read may be blocked waiting for the very microphone we are closing.
        closeSource()
        if (reader !== Thread.currentThread()) reader?.join(1000)
    }

    private fun invalidate() {
        interval++
        level = 0f
        hearing = false
    }

    private fun closeSource() {
        if (closed.compareAndSet(false, true)) runCatching { source.close() }
    }

    private fun loop() {
        val detector = TurnDetector()
        val frame = ShortArray(TurnDetector.FRAME)
        var judged = -1L
        try {
            while (running) {
                val reading = synchronized(gate) {
                    if (judged != interval) {
                        detector.reset()
                        judged = interval
                    }
                    if (rewind) { source.rewind(); rewind = false }
                    interval
                }
                val n = source.read(frame)
                if (n < 0 || n > frame.size) error("Microphone read failed")
                if (n == 0) continue
                synchronized(gate) {
                    // A frame already in read when mute/focus/stop changed must not cross the boundary.
                    if (!running || reading != interval || !listening || muted || held || typing) return@synchronized
                    val heard = detector.feed(frame, n)
                    level = detector.level
                    hearing = detector.speaking
                    if (heard is TurnDetector.Ended) { listening = false; hearing = false }
                    // Only a nonblocking queue callback; stop cannot return before this callback finishes.
                    if (heard != null) onHeard(MicHeard(interval, heard))
                }
            }
        } catch (_: Exception) {
            // Do not throw on an uncaught native reader thread, or expose audio / exception details.
            synchronized(gate) { if (running) failed = true }
        } finally {
            synchronized(gate) { running = false; listening = false; invalidate() }
            closeSource()
        }
    }
}

internal enum class CallFocusState { Ready, Paused, Lost }

/** Ignores callbacks after giving up focus, including a late gain from the previous call. */
internal class CallFocusLease(private val changed: (CallFocusState) -> Unit) {
    private var active = false
    @Synchronized fun take(request: () -> Boolean): Boolean {
        if (active) return false
        active = true
        val granted = runCatching(request).getOrDefault(false)
        if (!granted) active = false
        return granted
    }
    @Synchronized fun change(state: CallFocusState) {
        if (!active) return
        if (state == CallFocusState.Lost) active = false
        changed(state)
    }
    @Synchronized fun give(abandon: () -> Unit) {
        active = false
        runCatching(abandon)
    }
}
