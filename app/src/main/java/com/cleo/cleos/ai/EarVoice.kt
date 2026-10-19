package com.cleo.cleos.ai

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.cleo.cleos.data.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The TA's voice messages close to the ear (Binaural), on headphones only: on a speaker the two
 * ears aren't apart, and a voice kept to one side would only sound lopsided. The message stays
 * the recording as it came; what plays is made from it the first time it is played and kept in
 * the cache, so one message goes round the head the same way each time it is played.
 */
class EarVoice(context: Context, private val images: ImageStore) {
    private val appContext = context.applicationContext
    private val dir = File(context.cacheDir, "ear")
    private val hrirs by lazy { appContext.assets.open(ASSET).use { Hrirs.parse(it.readBytes()) } }
    private val byRate = ConcurrentHashMap<Int, Hrirs>()
    private val making = Mutex()

    /** Whether what the phone plays goes to something on the ears (wired, USB or Bluetooth). */
    fun headphones(): Boolean {
        // The emulator has no headphones to plug in: on a debuggable build a file in the cache stands in for them.
        if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && File(appContext.cacheDir, "pretend-headphones").exists()) {
            return true
        }
        val audio = appContext.getSystemService(AudioManager::class.java) ?: return false
        val outputs = if (Build.VERSION.SDK_INT >= 33) {
            audio.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        } else {
            // Before 13 there is no asking where media goes: anything on the ears being connected
            // is taken to mean it goes there, which is what phones do.
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        return outputs.any { it.type in EARS }
    }

    /** [name] (a voice message's file in ImageStore) made to play beside the ear; null when it can't be, and it plays as it is. */
    suspend fun prepared(name: String): File? = withContext(Dispatchers.Default) {
        val out = File(dir, "$name.ear.wav")
        if (out.exists()) return@withContext out
        making.withLock {
            if (out.exists()) return@withLock out
            try {
                val started = System.currentTimeMillis()
                val pcm = Pcm.mono(images.file(name))
                val h = byRate.getOrPut(pcm.rate) { hrirs.resampled(pcm.rate) }
                val stereo = Binaural.render(pcm.samples, h, Random.Default)
                dir.mkdirs()
                val tmp = File(dir, "$name.tmp")
                writeWav(tmp, stereo, pcm.rate)
                tmp.renameTo(out)
                Log.i(TAG, "made ${pcm.samples.size / pcm.rate.toFloat()}s at ${pcm.rate} Hz in ${System.currentTimeMillis() - started} ms")
                out
            } catch (e: Exception) {
                Log.w(TAG, "can't make it: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }

    /** What [prepared] made from [name], once the recording itself is gone. */
    fun forget(name: String) {
        runCatching { File(dir, "$name.ear.wav").delete() }
    }

    private fun writeWav(file: File, stereo: FloatArray, rate: Int) {
        val data = ByteBuffer.allocate(stereo.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in stereo) data.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        file.outputStream().use {
            it.write(Voice.wavHeader(stereo.size * 2, rate, channels = 2))
            it.write(data.array())
        }
    }

    private companion object {
        const val TAG = "EarVoice"
        const val ASSET = "hrir/ku100_near.bin"

        /** Outputs worn on the ears. A Bluetooth speaker looks the same as Bluetooth headphones; nothing tells them apart. */
        val EARS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}

/** A recording as mono samples (-1 to 1) and their rate, whatever the voice service sent (MP3, WAV, Opus…), through the phone's decoders. */
class Pcm(val samples: FloatArray, val rate: Int) {
    companion object {
        /** A decoder that doesn't finish in this long is given up on. */
        private const val LIMIT_MS = 20_000L

        fun mono(file: File, checkActive: () -> Unit = {}): Pcm {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("no audio in it")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var float = false
                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                var out = FloatArray(rate * 4)
                var size = 0
                fun add(v: Float) {
                    if (size == out.size) out = out.copyOf(out.size * 2)
                    out[size++] = v
                }
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    val until = System.currentTimeMillis() + LIMIT_MS
                    while (System.currentTimeMillis() < until) {
                        checkActive()
                        if (!inputDone) {
                            val i = codec.dequeueInputBuffer(10_000)
                            if (i >= 0) {
                                val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                                if (n < 0) {
                                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val o = codec.dequeueOutputBuffer(info, 10_000)
                        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        } else if (o >= 0) {
                            val buffer = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            // Channels averaged into one.
                            if (float) {
                                val b = buffer.asFloatBuffer()
                                while (b.remaining() >= channels) {
                                    checkActive()
                                    var v = 0f
                                    repeat(channels) { v += b.get() }
                                    add(v / channels)
                                }
                            } else {
                                val b = buffer.asShortBuffer()
                                while (b.remaining() >= channels) {
                                    checkActive()
                                    var v = 0f
                                    repeat(channels) { v += b.get() / 32768f }
                                    add(v / channels)
                                }
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                return Pcm(out.copyOf(size), rate)
                            }
                        }
                    }
                    error("the decoder took too long")
                } finally {
                    runCatching { codec.stop() }
                    codec.release()
                }
            } finally {
                extractor.release()
            }
        }
    }
}
