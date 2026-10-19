package com.cleo.cleos.ai

import com.cleo.cleos.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** A service that turns voice into text, as the settings screen offers it. */
data class VoicePreset(val name: String, val baseUrl: String, val model: String)

/** Voice messages: the recording's format, and what a transcription service answers. */
object Voice {
    val presets = listOf(
        VoicePreset("硅基流动", "https://api.siliconflow.cn/v1", "FunAudioLLM/SenseVoiceSmall"),
        VoicePreset("智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-asr-2512"),
        VoicePreset("OpenAI", "https://api.openai.com/v1", "whisper-1"),
        VoicePreset("Mossland", "https://api.mosi.cn/v1", "moss-transcribe-1.0"),
    )

    /** 16 kHz mono 16-bit: what speech models are trained on, and small enough to send. */
    const val RATE = 16_000

    /** A recording stops by itself here; a minute is a long voice message already. */
    const val MAX_MS = 60_000L

    /** Shorter than this was a tap, not something said. */
    const val MIN_MS = 800L

    private val json = Json { ignoreUnknownKeys = true }

    /** The transcription endpoint for a service's address, given down to /v1 or to the chat URL. */
    fun url(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions").let { if (it.endsWith("/audio/transcriptions")) it else "$it/audio/transcriptions" }

    /** The text in a transcription answer, `{"text": …}`; null when the answer isn't one. */
    fun text(body: String): String? =
        runCatching { ((json.parseToJsonElement(body) as JsonObject)["text"] as? JsonPrimitive)?.contentOrNull }.getOrNull()

    /** The 44-byte header of a WAV file holding [dataBytes] of 16-bit PCM at [rate], mono unless [channels] says. */
    fun wavHeader(dataBytes: Int, rate: Int = RATE, channels: Int = 1): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(rate)
            putInt(rate * 2 * channels) // bytes a second
            putShort((2 * channels).toShort()) // bytes a frame
            putShort(16) // bits a sample
            put("data".toByteArray())
            putInt(dataBytes)
        }.array()

    /** [ms] of silence as a WAV file: what the settings screen sends to try a service. */
    fun silence(ms: Int = 1000): ByteArray {
        val bytes = RATE * 2 * ms / 1000
        return wavHeader(bytes) + ByteArray(bytes)
    }

    /** How a voice bubble shows its length: 7″, 1′05″. */
    fun duration(ms: Long): String {
        val s = ((ms + 500) / 1000).coerceAtLeast(1)
        return if (s < 60) "$s″" else "${s / 60}′%02d″".format(s % 60)
    }
}

/**
 * Voice to text over /audio/transcriptions, the shape OpenAI's Whisper set and most services
 * follow (SiliconFlow's SenseVoice, Zhipu's GLM-ASR). The key is the one filed for the
 * address, the same way chat keys are: a service used for chat too needs no second key.
 */
class Transcriber(http: OkHttpClient, private val secrets: SecretStore) {
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    /** Given up at once when the caller is cancelled (fetch): a call hung up mid-transcription ends then. */
    suspend fun transcribe(baseUrl: String, model: String, file: File): String = withContext(Dispatchers.IO) {
        val key = secrets.key(baseUrl)?.takeIf { it.isNotBlank() }
            ?: throw ChatException("语音转文字的服务还没有填 Key，去设置「发语音」里填上。")
        // The same second try the chat makes, for the same reason: an address copied off a
        // relay's front page can be missing the version segment its API hangs off, and it is
        // the same address the chat is set to (see atRightAddress). The key stays the one
        // filed for the address as typed — that is where it is filed.
        atRightAddress(ApiEndpoint(baseUrl, key, model)) { to -> sendVoiceTo(to, file) }
    }

    private suspend fun sendVoiceTo(to: ApiEndpoint, file: File): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", to.model.trim())
            .addFormDataPart("file", file.name, file.asRequestBody(WAV))
            .build()
        val request = Request.Builder()
            .url(Voice.url(to.baseUrl))
            .header("Authorization", "Bearer ${to.apiKey}")
            .post(body)
            .build()
        return try {
            http.fetch(request) { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw ChatException(describe(response.code, text), response.code, wrongEndpoint = noSuchPath(response.code))
                }
                // A page where a transcription should be: the address, not the recording.
                Voice.text(text) ?: throw ChatException("服务回的不是转写结果：${text.take(80)}", wrongEndpoint = true)
            }
        } catch (e: IOException) {
            throw ChatException(
                when (e) {
                    is UnknownHostException -> "找不到服务器：检查地址，或者网络是不是断了"
                    is SocketTimeoutException -> "等太久没有回应"
                    else -> "网络出错：${e.message ?: e.javaClass.simpleName}"
                },
            ).apply { initCause(e) }
        }
    }

    private fun describe(code: Int, body: String): String {
        val hint = when (code) {
            401, 403 -> "Key 不对，或者已经失效了"
            402 -> "账户余额不足"
            404 -> "地址或模型名不对（404）"
            429 -> "请求太频繁，或者额度用完了"
            in 500..599 -> "服务那边出错了（$code）"
            else -> "请求失败（$code）"
        }
        val detail = body.trim().take(160)
        return if (detail.isEmpty()) hint else "$hint\n$detail"
    }

    private companion object {
        val WAV = "audio/wav".toMediaType()
    }
}
