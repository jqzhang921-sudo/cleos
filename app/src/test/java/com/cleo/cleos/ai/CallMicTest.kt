package com.cleo.cleos.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

class CallMicTest {
    private class Source(private val opening: () -> Boolean = { true }) : MicSource {
        val reads = LinkedBlockingQueue<(ShortArray) -> Int>()
        val closes = AtomicInteger()
        val closed = CountDownLatch(1)
        val reading = CountDownLatch(1)
        override fun open() = opening()
        override fun read(buffer: ShortArray): Int {
            reading.countDown()
            return reads.take()(buffer)
        }
        override fun close() {
            closes.incrementAndGet()
            closed.countDown()
            reads.offer { -1 }
        }
        fun frames(count: Int, amplitude: Int = 0) {
            repeat(count) {
                reads.put { buffer ->
                    buffer.indices.forEach { buffer[it] = (amplitude * sin(2 * PI * 180 * it / 16000)).toInt().toShort() }
                    buffer.size
                }
            }
        }
        fun drained() {
            val done = CountDownLatch(1)
            reads.put { done.countDown(); 0 }
            assertTrue("reader did not process synthetic frames", done.await(3, TimeUnit.SECONDS))
        }
    }

    private fun call(source: Source, test: (CallMic, LinkedBlockingQueue<MicHeard>) -> Unit) {
        val events = LinkedBlockingQueue<MicHeard>()
        val mic = CallMic(source) { events.offer(it) }
        try {
            mic.listen()
            assertTrue(mic.start())
            assertTrue(source.reading.await(3, TimeUnit.SECONDS))
            test(mic, events)
        } finally { mic.stop() }
    }

    @Test fun aSecondStartCannotOpenAnotherReader() {
        val opens = AtomicInteger()
        val source = Source { opens.incrementAndGet(); true }
        call(source) { mic, _ ->
            assertFalse(mic.start())
            assertEquals(1, opens.get())
        }
        assertEquals(1, source.closes.get())
    }

    @Test fun completeSpeechProducesPauseAndEndAndWaitsForListenAgain() {
        val source = Source()
        call(source) { mic, events ->
            source.frames(20)
            source.frames(50, 600)
            source.frames(100)
            source.drained()
            val all = events.toList()
            assertEquals(listOf(TurnDetector.Paused::class, TurnDetector.Ended::class), all.map { it.heard::class })
            val ended = all.last()
            assertTrue(mic.accepts(ended))
            assertFalse(mic.hearing)
            source.frames(50, 600); source.frames(100); source.drained()
            assertEquals(2, events.size)
            mic.listen()
            assertFalse(mic.accepts(ended))
        }
    }

    @Test fun quickMuteAndUnmuteDiscardsPartialSpeechEvenBetweenReads() = pauseBoundary { it.muted = true; it.muted = false }
    @Test fun quickFocusLossAndGainDiscardsPartialSpeechEvenBetweenReads() = pauseBoundary { it.held = true; it.held = false }

    private fun pauseBoundary(pause: (CallMic) -> Unit) {
        val source = Source()
        call(source) { mic, events ->
            source.frames(20); source.frames(30, 1800); source.drained()
            assertTrue(mic.hearing)
            pause(mic)
            assertFalse(mic.hearing)
            assertEquals(0f, mic.level)
            source.frames(110); source.drained()
            assertTrue("old partial turn crossed the pause", events.isEmpty())
            source.frames(50, 600); source.frames(100); source.drained()
            assertEquals(1, events.count { it.heard is TurnDetector.Ended })
        }
    }

    @Test fun muteInvalidatesAnAlreadyQueuedEnd() = queuedEnd { it.muted = true }
    @Test fun focusLossInvalidatesAnAlreadyQueuedEnd() = queuedEnd { it.held = true }
    private fun queuedEnd(pause: (CallMic) -> Unit) {
        val source = Source()
        call(source) { mic, events ->
            source.frames(20); source.frames(50, 600); source.frames(100); source.drained()
            val ended = events.last()
            assertTrue(mic.accepts(ended))
            pause(mic)
            assertFalse(mic.accepts(ended))
        }
    }

    @Test fun audioReadAcrossMuteBoundaryIsDiscardedEvenAfterUnmuting() {
        val source = Source()
        call(source) { mic, _ ->
            val inRead = CountDownLatch(1)
            val release = CountDownLatch(1)
            source.reads.put { buffer ->
                inRead.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                buffer.fill(2000)
                buffer.size
            }
            assertTrue(inRead.await(3, TimeUnit.SECONDS))
            mic.muted = true; mic.muted = false
            release.countDown()
            source.drained()
            assertEquals(0f, mic.level)
        }
    }

    @Test fun mutedOrHeldFramesNeverBecomeSpeech() {
        val source = Source()
        call(source) { mic, events ->
            mic.muted = true
            source.frames(60, 1500); source.frames(100); source.drained()
            mic.held = true; mic.muted = false
            source.frames(60, 1500); source.frames(100); source.drained()
            assertTrue(events.isEmpty())
            assertEquals(0f, mic.level)
            assertFalse(mic.hearing)
        }
    }

    @Test fun thrownReadEndsCaptureWithoutEscapingTheWorker() = readFailure { throw IllegalStateException("synthetic private detail") }
    @Test fun negativeReadEndsCaptureWithoutSilentlyWaitingForMoreSpeech() = readFailure { -6 }
    @Test fun invalidReadSizeIsAlsoAControlledFailure() = readFailure { it.size + 1 }
    private fun readFailure(read: (ShortArray) -> Int) {
        val source = Source()
        call(source) { mic, events ->
            source.frames(20); source.frames(25, 600); source.drained()
            assertTrue(mic.hearing)
            source.reads.put(read)
            assertTrue(source.closed.await(3, TimeUnit.SECONDS))
            assertTrue(mic.failed)
            assertFalse(mic.hearing)
            assertEquals(0f, mic.level)
            assertTrue(events.isEmpty())
        }
        assertEquals(1, source.closes.get())
    }

    @Test fun stopClosesABlockedSourceBeforeWaitingForItsReader() {
        val source = Source()
        call(source) { mic, events ->
            val stopping = thread { mic.stop() }
            assertTrue("close was held behind reader.join", source.closed.await(750, TimeUnit.MILLISECONDS))
            stopping.join(3000)
            assertFalse(stopping.isAlive)
            assertFalse(mic.failed)
            assertTrue(events.isEmpty())
        }
        assertEquals(1, source.closes.get())
    }

    @Test fun lateReadReturningAfterStopDoesNotDetectOrPublishAnything() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val closed = AtomicInteger()
        val source = object : MicSource {
            override fun open() = true
            override fun read(buffer: ShortArray): Int {
                reading.countDown()
                release.await()
                buffer.fill(2000)
                returned.countDown()
                return buffer.size
            }
            override fun close() { closed.incrementAndGet() }
        }
        val events = LinkedBlockingQueue<MicHeard>()
        val mic = CallMic(source) { events.offer(it) }
        try {
            mic.listen(); assertTrue(mic.start())
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            mic.stop() // A deliberately broken source cannot hold up shutdown forever.
            release.countDown()
            assertTrue(returned.await(3, TimeUnit.SECONDS))
            mic.stop()
            assertTrue(events.isEmpty())
            assertEquals(0f, mic.level)
            assertFalse(mic.failed)
            assertEquals(1, closed.get())
        } finally { release.countDown(); mic.stop() }
    }

    @Test fun openFailureOrExceptionClosesOnceAndCannotRestart() {
        for (open in listOf<() -> Boolean>({ false }, { throw SecurityException("synthetic permission loss") })) {
            val source = Source(open)
            val mic = CallMic(source) { fail("not opened") }
            assertFalse(mic.start())
            mic.stop(); mic.stop()
            assertFalse(mic.start())
            assertEquals(1, source.closes.get())
        }
    }
}
