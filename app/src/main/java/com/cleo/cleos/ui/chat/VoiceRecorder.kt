package com.cleo.cleos.ui.chat

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.cleo.cleos.ai.Voice
import com.cleo.cleos.data.MessageAudio
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Records the microphone into a WAV file: 16 kHz mono 16-bit, the one format every
 * transcription service takes. It is written while recording, into ImageStore's folder, and
 * the header's lengths are filled in when it stops.
 */
class VoiceRecorder(private val dir: File, private val prefix: String = "voice_") {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var file: File? = null

    @Volatile private var running = false

    @Volatile private var bytes = 0L

    /** How loud it is right now, 0 to 1, for the indicator. */
    @Volatile var level = 0f
        private set

    /** Starts recording; false when the microphone can't be opened. The permission is asked for before. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return false
        val min = AudioRecord.getMinBufferSize(Voice.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Voice.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Voice.RATE))
        }.getOrNull() ?: return false
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        val f = File(dir, "${prefix}${System.currentTimeMillis()}_${(1000..9999).random()}.wav")
        val out = runCatching { RandomAccessFile(f, "rw") }.getOrNull()
        if (out == null || runCatching { r.startRecording() }.isFailure) {
            out?.close()
            f.delete()
            r.release()
            return false
        }
        record = r
        file = f
        bytes = 0
        level = 0f
        running = true
        worker = thread(name = "voice") {
            val buf = ShortArray(Voice.RATE / 20)
            val pcm = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            out.use {
                // The header goes in last, once the length is known.
                it.write(ByteArray(44))
                while (running) {
                    val received = r.read(buf, 0, buf.size)
                    val n = minOf(received, ((Voice.RATE * 2L * Voice.MAX_MS / 1000 - bytes) / 2).toInt())
                    if (n < 0) break
                    if (n == 0) continue
                    pcm.clear()
                    var sum = 0.0
                    for (i in 0 until n) {
                        pcm.putShort(buf[i])
                        sum += buf[i].toDouble() * buf[i]
                    }
                    it.write(pcm.array(), 0, n * 2)
                    bytes += n * 2
                    level = (sqrt(sum / n) / 5000.0).coerceIn(0.0, 1.0).toFloat()
                    if (bytes >= Voice.RATE * 2L * Voice.MAX_MS / 1000) running = false
                }
                it.seek(0)
                it.write(Voice.wavHeader(bytes.toInt()))
            }
        }
        return true
    }

    /** Stops: the recording, or null when it was too short to be anything and has been thrown away. */
    fun stop(): MessageAudio? {
        val f = finish() ?: return null
        val ms = bytes * 1000 / (Voice.RATE * 2)
        if (ms < Voice.MIN_MS) {
            f.delete()
            return null
        }
        return MessageAudio(f.name, ms)
    }

    /** Stops and throws the recording away. */
    fun cancel() {
        finish()?.delete()
    }

    private fun finish(): File? {
        if (worker == null) return null
        running = false
        worker?.join(1000)
        worker = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        return file.also { file = null }
    }
}
