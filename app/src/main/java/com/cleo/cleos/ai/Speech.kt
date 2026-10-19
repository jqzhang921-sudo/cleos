package com.cleo.cleos.ai

import android.media.MediaMetadataRetriever
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.data.MessageAudio
import com.cleo.cleos.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Where the TA's voice comes from. Each service is a choice of its own in settings with only what
 * it needs: its key, and a voice from its own list. (There was a general way through an MCP tool;
 * nobody could tell how to fill it in, and the services people use have their own APIs anyway.)
 * [Other] is any service with OpenAI's /audio/speech, filled in by hand: relays mostly.
 */
enum class VoiceService(val key: String, val label: String) {
    SiliconFlow("siliconflow", "硅基流动"),
    MiniMax("minimax", "MiniMax"),
    Mossland("mossland", "Mossland"),
    ElevenLabs("elevenlabs", "ElevenLabs"),
    OpenAI("openai", "OpenAI"),
    Other("api", "其他接口"),
    ;

    companion object {
        fun of(key: String?): VoiceService? = entries.firstOrNull { it.key == key }
    }
}

/** One voice a service offers: what goes in the request, and what the person reads. */
data class VoiceOption(val id: String, val label: String)

/**
 * A failure with a message already worded for the person using the app. [wrongEndpoint] is the
 * same flag the chat's [ChatException] carries: a page came back, or nothing is at that path,
 * so the address is worth one more try a level down (see [atRightAddress]).
 */
class SpeechException(
    message: String,
    override val wrongEndpoint: Boolean = false,
    /** Controlled structural label, never a service's response prose. */
    val category: String = "unknown",
) : Exception(message), EndpointFailure

/** The TA's voice: each service's request and answer. Nothing here touches the network. */
object Speech {
    const val SILICONFLOW_BASE = "https://api.siliconflow.cn/v1"
    const val SILICONFLOW_MODEL = "FunAudioLLM/CosyVoice2-0.5B"
    const val OPENAI_BASE = "https://api.openai.com/v1"
    const val OPENAI_MODEL = "gpt-4o-mini-tts"
    const val MOSSLAND_BASE = "https://api.mosi.cn/v1"
    const val MOSSLAND_MODEL = "moss-tts-1.5-flash"
    const val ELEVENLABS_BASE = "https://api.elevenlabs.io/v1"
    const val ELEVENLABS_MODEL = "eleven_multilingual_v2"

    /** MiniMax's two sites; a key from one doesn't work on the other. The mainland's moved here from api.minimaxi.com. */
    const val MINIMAX_CN = "https://api.minimax.cn"
    const val MINIMAX_GLOBAL = "https://api.minimax.io"
    const val MINIMAX_MODEL = "speech-2.8-hd"

    /** CosyVoice's own eight, as SiliconFlow names them. */
    val siliconFlowVoices = listOf(
        "anna" to "沉稳女声", "bella" to "激情女声", "claire" to "温柔女声", "diana" to "欢快女声",
        "alex" to "沉稳男声", "benjamin" to "低沉男声", "charles" to "磁性男声", "david" to "欢快男声",
    ).map { (id, label) -> VoiceOption("$SILICONFLOW_MODEL:$id", "$label $id") }

    /** MiniMax's long-standing Chinese voices; the rest come from its list (Speaker.voices). */
    val miniMaxVoices = listOf(
        VoiceOption("female-shaonv", "少女"),
        VoiceOption("female-yujie", "御姐"),
        VoiceOption("female-chengshu", "成熟女性"),
        VoiceOption("female-tianmei", "甜美女性"),
        VoiceOption("male-qn-qingse", "青涩青年"),
        VoiceOption("male-qn-jingying", "精英青年"),
        VoiceOption("male-qn-badao", "霸道青年"),
        VoiceOption("male-qn-daxuesheng", "青年大学生"),
    )

    /** Mossland's public voices (its docs, voices list); the library has more, by id. */
    val mosslandVoices = listOf(
        VoiceOption("806c9695-6160-404e-8722-4f788d935af3", "轻快灵动女声"),
        VoiceOption("fe85a513-9bf3-4ef7-aa0b-8b2d11e4db93", "少年感人声（男）"),
        VoiceOption("19411508-8731-4b68-901d-7e4b8a98e23f", "忧伤的秋"),
        VoiceOption("faf7f550-0627-4fc6-8db0-d3bfdad49358", "经验女教师"),
        VoiceOption("c6c0a40a-ea82-4468-9a21-333d3c4a76f6", "曼波有口音版"),
        VoiceOption("26838557-6890-4505-bc7c-e8198443a141", "东北虎哥"),
        VoiceOption("f80b6698-0066-430b-88a0-f0fb8796db34", "明太祖"),
        VoiceOption("7662a8a1-700c-466a-b66b-57ece9e2e231", "李白"),
        VoiceOption("0804710c-8e5e-4b67-acda-5785ef13c309", "历史解说男声"),
        VoiceOption("2fdf194e-c16e-4587-9027-0d3464e09b4e", "诗词朗读"),
        VoiceOption("133bd03b-d717-4a55-8974-7ffc9afc1b51", "故宫纪录片"),
        VoiceOption("944eb93b-3820-49f3-b2c0-4e37a31d1161", "三农农业旁白"),
        VoiceOption("9d1e88e9-3b9c-4992-a414-7a1cb3ff7ab5", "优雅英国女士"),
        VoiceOption("f9a1416b-d006-4b77-9581-8f0e8ec1e401", "旁白 Jake"),
        VoiceOption("ddc6e38b-6f55-4415-b21b-a88cad2cc1d9", "VOX AKUMA"),
    )

    val openAiVoices = listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse")
        .map { VoiceOption(it, it) }

    /** The voices offered to pick from without asking the service; none for those where it is typed. */
    fun builtIn(service: VoiceService): List<VoiceOption> = when (service) {
        VoiceService.SiliconFlow -> siliconFlowVoices
        VoiceService.MiniMax -> miniMaxVoices
        VoiceService.Mossland -> mosslandVoices
        VoiceService.OpenAI -> openAiVoices
        VoiceService.ElevenLabs, VoiceService.Other -> emptyList()
    }

    /** What the settings screen plays to try a voice. */
    const val SAMPLE = "你好呀，这是我的声音。"

    /** A voice message says a sentence or two; longer is cut here rather than billed. */
    const val MAX_CHARS = 300

    /** A message read aloud (朗读): in pieces of one voice message's length, and no more than this many. */
    const val READ_PARTS_MAX = 4

    /** What [readParts] makes of a message: the pieces to speak in order, and whether the rest was left out. */
    data class ReadParts(val pieces: List<String>, val cut: Boolean)

    private val sentenceEnd = Regex("(?<=[。！？!?；;…\\n])")

    /**
     * A message cut into what a voice can say at a time: at the ends of sentences, each at most
     * [MAX_CHARS], at most [READ_PARTS_MAX] of them. Marks that are only there for the eye (the
     * asterisks round an action, a heading's #, a quote's >) are not said.
     */
    fun readParts(text: String): ReadParts {
        val clean = text.lineSequence()
            .map { it.trim().removePrefix(">").trim() }
            .joinToString("\n")
            .replace(Regex("[*#`_~]+"), "")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
        if (clean.isEmpty()) return ReadParts(emptyList(), false)
        val pieces = ArrayList<String>()
        val now = StringBuilder()
        fun flush() {
            now.toString().trim().takeIf { it.isNotEmpty() }?.let { pieces += it }
            now.setLength(0)
        }
        for (sentence in clean.split(sentenceEnd)) {
            // One sentence longer than a piece is cut where it must be.
            for (chunk in sentence.chunked(MAX_CHARS)) {
                if (now.length + chunk.length > MAX_CHARS) flush()
                now.append(chunk)
            }
        }
        flush()
        return ReadParts(pieces.take(READ_PARTS_MAX), pieces.size > READ_PARTS_MAX)
    }

    /**
     * What a piece read aloud is kept under: the words, and everything that changes how they sound
     * (which service, voice and model), so the same piece in the same voice is made once, and a
     * changed voice makes it again.
     */
    fun readKey(s: AppSettings, service: VoiceService, text: String): String {
        val of = listOf(service.key, voice(s, service), s.speechBaseUrl.trim(), s.speechModel.trim(), s.elevenModel.trim(), s.minimaxGlobal.toString(), text)
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-1").digest(of.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun service(s: AppSettings): VoiceService? = VoiceService.of(s.speechEngine)

    /** The voice picked for [service], or its first. ElevenLabs' and the hand-filled one's are their own fields. */
    fun voice(s: AppSettings, service: VoiceService): String = when (service) {
        VoiceService.ElevenLabs -> s.elevenVoice.trim()
        VoiceService.Other -> s.speechVoice.trim()
        else -> s.speechVoices[service.key]?.trim()?.takeIf { it.isNotEmpty() } ?: builtIn(service).first().id
    }

    /** Where [service]'s key is filed (keys go by address): with the chat's and voice-to-text's, when they share one. */
    fun keyAddress(s: AppSettings, service: VoiceService): String = when (service) {
        VoiceService.SiliconFlow -> SILICONFLOW_BASE
        VoiceService.MiniMax -> if (s.minimaxGlobal) MINIMAX_GLOBAL else MINIMAX_CN
        VoiceService.Mossland -> MOSSLAND_BASE
        VoiceService.ElevenLabs -> ELEVENLABS_BASE
        VoiceService.OpenAI -> OPENAI_BASE
        VoiceService.Other -> s.speechBaseUrl.trim()
    }

    /** Whether there is a voice to speak with, so send_voice can be offered. */
    fun ready(s: AppSettings): Boolean = when (service(s)) {
        null -> false
        VoiceService.Other -> s.speechBaseUrl.isNotBlank() && s.speechModel.isNotBlank()
        VoiceService.ElevenLabs -> s.elevenVoice.isNotBlank()
        else -> true
    }

    /**
     * The service as settings from before stored it: "api" was every OpenAI-shaped service, told
     * apart by address, and "mcp" (an MCP tool) is gone. Returns the service key and the voices.
     */
    fun migrate(engine: String, baseUrl: String, voice: String, voices: Map<String, String>): Pair<String, Map<String, String>> {
        if (engine == "mcp") return "" to voices
        if (engine != VoiceService.Other.key) return engine to voices
        val host = baseUrl.trim().substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        if (host.isEmpty()) return "" to voices
        val known = when {
            host == "api.siliconflow.cn" -> VoiceService.SiliconFlow
            host == "mosi.cn" || host.endsWith(".mosi.cn") -> VoiceService.Mossland
            host == "api.openai.com" -> VoiceService.OpenAI
            else -> return engine to voices
        }
        val kept = if (voice.isNotBlank() && known.key !in voices) voices + (known.key to voice.trim()) else voices
        return known.key to kept
    }

    fun speechUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions").let { if (it.endsWith("/audio/speech")) it else "$it/audio/speech" }

    /**
     * The audio format to ask a hand-filled OpenAI-shaped service for: mp3 almost everywhere, but
     * Zhipu's GLM-TTS (open.bigmodel.cn, api.z.ai) only has wav and pcm, and answers 400 / 1214
     * ("不支持当前response_format值") to mp3. pcm has no header and can't be played as a file, so wav.
     */
    fun speechFormat(baseUrl: String, model: String): String {
        val host = baseUrl.trim().substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        val zhipu = host == "bigmodel.cn" || host.endsWith(".bigmodel.cn") || host == "z.ai" || host.endsWith(".z.ai")
        return if (zhipu || model.trim().lowercase().startsWith("glm-tts")) "wav" else "mp3"
    }

    /** OpenAI's /audio/speech, which SiliconFlow and Mossland take too: Mossland wants the voice as voice_id. */
    fun openAiBody(
        model: String,
        voice: String,
        text: String,
        voiceField: String = "voice",
        format: String = "mp3",
    ): String = buildJsonObject {
        put("model", model.trim())
        put("input", text)
        if (voice.isNotBlank()) put(voiceField, voice.trim())
        put("response_format", format)
    }.toString()

    fun elevenLabsBody(model: String, text: String): String = buildJsonObject {
        put("text", text)
        put("model_id", model.trim().ifEmpty { ELEVENLABS_MODEL })
        putJsonObject("voice_settings") {
            put("stability", 0.5)
            put("similarity_boost", 0.75)
        }
    }.toString()

    /** MiniMax's /v1/t2a_v2, as its own CLI and MCP server send it: the audio comes back hex in JSON. */
    fun miniMaxBody(voice: String, text: String): String = buildJsonObject {
        put("model", MINIMAX_MODEL)
        put("text", text)
        put("stream", false)
        putJsonObject("voice_setting") {
            put("voice_id", voice.trim())
            put("speed", 1.0)
            put("vol", 1.0)
            put("pitch", 0)
        }
        putJsonObject("audio_setting") {
            put("sample_rate", 32000)
            put("bitrate", 128000)
            put("format", "mp3")
            put("channel", 1)
        }
        put("language_boost", "auto")
        put("output_format", "hex")
    }.toString()

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    /** MiniMax answers 200 even when it refuses; the refusal is in base_resp. Null when it didn't. */
    fun miniMaxProblem(answer: JsonObject): String? {
        val base = answer.obj("base_resp") ?: return null
        val code = (base["status_code"] as? JsonPrimitive)?.intOrNull ?: return null
        if (code == 0) return null
        val said = base.str("status_msg").orEmpty().take(120)
        return when (code) {
            1004 -> "Key 不对：国内版和国际版的 Key 不通用，看看上面选对了没有（$said）"
            1008 -> "MiniMax 账户余额不足"
            2038 -> "要先在 MiniMax 开放平台完成实名认证"
            1002, 1039 -> "请求太频繁，等一下再试"
            else -> "MiniMax 说：$code $said"
        }
    }

    /** The audio in MiniMax's answer, from hex. */
    fun miniMaxAudio(body: String): ByteArray {
        val answer = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: throw SpeechException("MiniMax 回的不是 JSON：${body.take(80)}")
        miniMaxProblem(answer)?.let { throw SpeechException(it) }
        val hex = answer.obj("data")?.str("audio")?.trim().orEmpty()
        if (hex.isEmpty()) throw SpeechException("MiniMax 没回声音")
        if (hex.length % 2 != 0 || hex.any { Character.digit(it, 16) < 0 }) throw SpeechException("MiniMax 回的声音读不出来")
        return ByteArray(hex.length / 2) { i -> ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte() }
    }

    /** MiniMax's voices from /v1/get_voice: its own, then any cloned or designed on the account. */
    fun miniMaxVoiceList(body: String): List<VoiceOption> {
        val answer = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return emptyList()
        miniMaxProblem(answer)?.let { throw SpeechException(it) }
        return listOf("voice_cloning", "voice_generation", "system_voice").flatMap { group ->
            (answer[group] as? JsonArray).orEmpty().mapNotNull { v ->
                val o = v as? JsonObject ?: return@mapNotNull null
                val id = o.str("voice_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = o.str("voice_name")?.takeIf { it.isNotBlank() } ?: if (group == "system_voice") id else "我的音色"
                VoiceOption(id, name)
            }
        }
    }

    /** Mossland's /v1/audio/voices: the public ones and the account's own. */
    fun mosslandVoiceList(body: String): List<VoiceOption> {
        val answer = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return emptyList()
        return (answer["data"] as? JsonArray).orEmpty().mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VoiceOption(id, o.str("name")?.takeIf { it.isNotBlank() } ?: id)
        }
    }

    /** ElevenLabs' /v1/voices: what the account has, its premade voices among them. */
    fun elevenLabsVoiceList(body: String): List<VoiceOption> {
        val answer = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return emptyList()
        return (answer["voices"] as? JsonArray).orEmpty().mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val id = o.str("voice_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VoiceOption(id, o.str("name")?.takeIf { it.isNotBlank() } ?: id)
        }
    }

    /** A file extension for an audio type. */
    fun extension(mime: String?): String = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/aac" -> "aac"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/flac", "audio/x-flac" -> "flac"
        else -> "mp3"
    }
}

/**
 * Makes the TA's voice messages with the service the person picked: into a file among the
 * pictures (voice_…, so backups and deletes take it along, like the person's own), with how
 * long it plays.
 */
class Speaker(
    private val images: ImageStore,
    http: OkHttpClient,
    private val secrets: SecretStore,
) {
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    /** [text] spoken, into a voice message; throws [SpeechException] with what went wrong, worded for the person. */
    suspend fun speak(s: AppSettings, text: String): MessageAudio {
        val words = text.trim().take(Speech.MAX_CHARS)
        val (bytes, mime) = sound(s, words)
        return store(words, bytes, mime)
    }

    /** Store already obtained sound; group delivery controls cancellation and ownership around this write. */
    suspend fun store(words: String, bytes: ByteArray, mime: String?, prefix: String = "voice_"): MessageAudio {
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        return withContext(Dispatchers.IO) {
            val file = images.file("${prefix}${System.currentTimeMillis()}_${(1000..9999).random()}.${Speech.extension(mime)}")
            try { file.writeBytes(bytes) } catch (e: Exception) { file.delete(); throw e }
            val ms = runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(file.path)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                }
            }.getOrNull()
            // Some files don't say how long they are: about four characters a second, then.
            MessageAudio(file.name, ms ?: (words.length * 250L).coerceAtLeast(1000))
        }
    }

    /**
     * One piece of a message read aloud (see [Speech.readParts]), as a file in [dir]: made the
     * first time, and found there after, by what it says and the voice that says it. Old ones
     * go, so it stays small. Throws [SpeechException].
     */
    suspend fun reading(s: AppSettings, text: String, dir: File): File {
        val service = Speech.service(s) ?: throw SpeechException("还没选 TA 的声音：在设置「TA 的声音」里选一个")
        val name = "tts_" + Speech.readKey(s, service, text)
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            dir.listFiles { f -> f.name.startsWith("$name.") && f.length() > 0 }?.firstOrNull()
        }?.let { return it.also { f -> f.setLastModified(System.currentTimeMillis()) } }
        val (bytes, mime) = sound(s, text)
        return withContext(Dispatchers.IO) {
            File(dir, "$name.${Speech.extension(mime)}").also { it.writeBytes(bytes) }.also { prune(dir) }
        }
    }

    private fun prune(dir: File) {
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(READ_KEPT)?.forEach { it.delete() }
    }

    /**
     * [text] spoken, as the service sent it: the bytes and their type (MP3 mostly). Kept nowhere: a
     * phone call plays it and lets it go. Throws [SpeechException].
     */
    suspend fun sound(s: AppSettings, text: String): Pair<ByteArray, String?> {
        val words = text.trim().take(Speech.MAX_CHARS)
        if (words.isEmpty()) throw SpeechException("没有要说的话")
        val service = Speech.service(s) ?: throw SpeechException("还没选 TA 的声音：在设置「TA 的声音」里选一个")
        val key = secrets.key(Speech.keyAddress(s, service))?.takeIf { it.isNotBlank() }
            ?: throw SpeechException("${service.label}还没填 Key")
        val voice = Speech.voice(s, service)
        val (bytes, mime) = when (service) {
            VoiceService.SiliconFlow -> openAi(Speech.SILICONFLOW_BASE, key, Speech.openAiBody(Speech.SILICONFLOW_MODEL, voice, words))
            VoiceService.OpenAI -> openAi(Speech.OPENAI_BASE, key, Speech.openAiBody(Speech.OPENAI_MODEL, voice, words))
            VoiceService.Mossland -> openAi(Speech.MOSSLAND_BASE, key, Speech.openAiBody(Speech.MOSSLAND_MODEL, voice, words, voiceField = "voice_id"))
            // A hand-filled address is the same address the chat uses, so it gets the same
            // second try when what came back says the path is wrong (see atRightAddress): the
            // relay that needed /v1 for the chat needs it for the voice too.
            VoiceService.Other -> atRightAddress(ApiEndpoint(s.speechBaseUrl, key, s.speechModel)) { to ->
                openAi(
                    to.baseUrl,
                    key,
                    Speech.openAiBody(to.model, voice, words, format = Speech.speechFormat(to.baseUrl, to.model)),
                )
            }
            VoiceService.ElevenLabs -> fetch(
                Request.Builder()
                    .url("${Speech.ELEVENLABS_BASE}/text-to-speech/$voice")
                    .header("xi-api-key", key)
                    .header("Accept", "audio/mpeg")
                    .post(Speech.elevenLabsBody(s.elevenModel, words).toRequestBody(JSON))
                    .build(),
            )
            VoiceService.MiniMax -> {
                val body = text(
                    Request.Builder()
                        .url("${Speech.keyAddress(s, service)}/v1/t2a_v2")
                        .header("Authorization", "Bearer $key")
                        .post(Speech.miniMaxBody(voice, words).toRequestBody(JSON))
                        .build(),
                )
                Speech.miniMaxAudio(body) to "audio/mpeg"
            }
        }
        if (bytes.size < 64) throw SpeechException("回来的声音是空的", category = "empty_audio")
        return bytes to mime
    }

    /**
     * Every voice [service] has for this account, asked of it: MiniMax's few hundred, Mossland's
     * public ones and the account's own, ElevenLabs' on the account. Empty for those that list none.
     */
    suspend fun voices(s: AppSettings, service: VoiceService): List<VoiceOption> {
        val key = secrets.key(Speech.keyAddress(s, service))?.takeIf { it.isNotBlank() }
            ?: throw SpeechException("先填 ${service.label} 的 Key，才能列出音色")
        return when (service) {
            VoiceService.MiniMax -> Speech.miniMaxVoiceList(
                text(
                    Request.Builder()
                        .url("${Speech.keyAddress(s, service)}/v1/get_voice")
                        .header("Authorization", "Bearer $key")
                        .post("""{"voice_type":"all"}""".toRequestBody(JSON))
                        .build(),
                ),
            )
            VoiceService.Mossland -> Speech.mosslandVoiceList(
                text(Request.Builder().url("${Speech.MOSSLAND_BASE}/audio/voices?limit=150").header("Authorization", "Bearer $key").build()),
            )
            VoiceService.ElevenLabs -> Speech.elevenLabsVoiceList(
                text(Request.Builder().url("${Speech.ELEVENLABS_BASE}/voices").header("xi-api-key", key).build()),
            )
            else -> emptyList()
        }
    }

    private suspend fun openAi(baseUrl: String, key: String, body: String): Pair<ByteArray, String?> = fetch(
        Request.Builder()
            .url(Speech.speechUrl(baseUrl))
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody(JSON))
            .build(),
    )

    /**
     * One request whose answer is the audio; an answer that isn't says why, where it can. Given up at
     * once when the caller is cancelled (fetch): a call is hung up while a sentence is being made.
     */
    private suspend fun fetch(request: Request): Pair<ByteArray, String?> =
        try {
            http.fetch(request) { r ->
                val type = r.header("Content-Type")
                if (!r.isSuccessful) {
                    throw SpeechException(describe(r.code, r.body.string()), wrongEndpoint = noSuchPath(r.code), category = "http_${r.code}")
                }
                if (type != null && (type.startsWith("application/json") || type.startsWith("text/"))) {
                    // A page or an API's complaint where audio should be: whatever was asked
                    // for is not at this address.
                    throw SpeechException("回来的不是声音：${r.body.string().take(120)}", wrongEndpoint = true, category = "not_audio")
                }
                r.body.bytes() to type
            }
        } catch (e: IOException) {
            val reason = when {
                e is java.net.SocketTimeoutException -> "network_timeout"
                e.message == "Canceled" -> "network_cancelled"
                e.message.orEmpty().contains("closed", ignoreCase = true) -> "network_closed"
                e.message.orEmpty().contains("unexpected end", ignoreCase = true) -> "network_eof"
                e.message.orEmpty().contains("reset", ignoreCase = true) -> "network_reset"
                else -> "network_${e.javaClass.simpleName}"
            }
            throw SpeechException("网络出错：${e.message ?: e.javaClass.simpleName}", category = reason)
        }

    /** One request whose answer is JSON, as text; given up likewise. */
    private suspend fun text(request: Request): String =
        try {
            http.fetch(request) { r ->
                val body = r.body.string()
                if (!r.isSuccessful) throw SpeechException(describe(r.code, body))
                body
            }
        } catch (e: IOException) {
            throw SpeechException("网络出错：${e.message ?: e.javaClass.simpleName}")
        }

    private fun describe(code: Int, body: String): String {
        val hint = when (code) {
            401, 403 -> "Key 不对，或者已经失效了"
            402 -> "账户余额不足"
            404 -> "地址、模型或音色不对（404）"
            429 -> "请求太频繁，或者额度用完了"
            in 500..599 -> "服务那边出错了（$code）"
            else -> "请求失败（$code）"
        }
        val detail = body.trim().take(120)
        return if (detail.isEmpty()) hint else "$hint：$detail"
    }

    private companion object {
        val JSON = "application/json".toMediaType()

        /** How many pieces read aloud are kept (a message is a few of them). */
        const val READ_KEPT = 60
    }
}
