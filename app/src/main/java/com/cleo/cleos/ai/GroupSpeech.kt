package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.GroupMessageEntity
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The words actually synthesized, separate from any text that follows the voice. */
@Serializable
data class GroupSpeechClip(val file: String, val ms: Long, val text: String) {
    fun audio() = MessageAudio(file, ms)
}

object GroupSpeechRules {
    const val MAX_MS = 180_000L
    const val MAX_BYTES = 12 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }
    fun prefix(groupId: Long, speakerId: Long) = "voice_group_reply_${groupId}_${speakerId}_"
    fun words(raw: String) = raw.trim().also {
        require(it.isNotEmpty() && it.length <= Speech.MAX_CHARS) { "群语音一次说 1–${Speech.MAX_CHARS} 字，这条没有发送" }
    }
    fun valid(clip: GroupSpeechClip, groupId: Long, speakerId: Long?) = groupId > 0 && speakerId != null && speakerId > 0 &&
        clip.file.startsWith(prefix(groupId, speakerId)) && clip.file.matches(Regex("[A-Za-z0-9_-]+\\.(mp3|wav|ogg|aac|m4a|flac)")) &&
        clip.ms in 1..MAX_MS && clip.text.isNotBlank() && clip.text.length <= Speech.MAX_CHARS && clip.text == clip.text.trim()
    fun decode(raw: String?, groupId: Long, speakerId: Long?): GroupSpeechClip? =
        if (raw == null || raw.length > 4000) null else runCatching { json.decodeFromString<GroupSpeechClip>(raw) }.getOrNull()
            ?.takeIf { valid(it, groupId, speakerId) }
    fun encode(clip: GroupSpeechClip) = json.encodeToString(clip)
    fun clip(row: GroupMessageEntity) = if (row.role == "assistant") decode(row.audio, row.groupId, row.speakerId) else null
    fun validRow(row: GroupMessageEntity) = if (row.audio == null) true else clip(row)?.let {
        row.content == it.text || row.content.startsWith(it.text + "\n\n")
    } == true
    fun merged(spoken: String, body: String): String {
        val plain = body.trim()
        val result = when { spoken.isEmpty() -> plain; plain.isEmpty() || plain == spoken -> spoken; else -> "$spoken\n\n$plain" }
        require(result.length <= GroupChatRules.MAX_REPLY) { "这段回复太长了，语音没有继续发送" }
        return result
    }
    fun remaining(content: String, spoken: String) = when {
        content == spoken -> ""
        content.startsWith(spoken + "\n\n") -> content.removePrefix(spoken + "\n\n")
        else -> content
    }
    fun sameVoice(before: AppSettings, now: AppSettings): Boolean {
        val service = Speech.service(before) ?: return false
        return ToolGroup.Speak in now.tools && Speech.ready(now) && Speech.service(now) == service &&
            Speech.readKey(before, service, "") == Speech.readKey(now, service, "")
    }
    /** Basic container checks before saving a provider response as a playable voice. */
    fun sound(bytes: ByteArray, mime: String?): Boolean {
        if (bytes.size !in 12..MAX_BYTES) return false
        fun tag(at: Int, text: String) = bytes.size >= at + text.length &&
            bytes.copyOfRange(at, at + text.length).contentEquals(text.toByteArray(Charsets.US_ASCII))
        fun sync() = bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 224 == 224
        return when (Speech.extension(mime)) {
            "wav" -> tag(0, "RIFF") && tag(8, "WAVE")
            "ogg" -> tag(0, "OggS")
            "flac" -> tag(0, "fLaC")
            "m4a" -> tag(4, "ftyp")
            "aac" -> sync()
            else -> tag(0, "ID3") || sync()
        }
    }
}

/** Cancellation leaves no unpublished file; a completed commit owns the audio, even if stopped immediately afterward. */
object GroupSpeechDelivery {
    const val TIMEOUT_MS = 45_000L
    suspend fun deliver(raw: String, groupId: Long, speakerId: Long,
        request: suspend (String) -> Pair<ByteArray, String?>,
        save: suspend (String, ByteArray, String?) -> MessageAudio,
        commit: suspend (String, GroupSpeechClip?) -> Unit,
        remove: suspend (String) -> Unit, timeoutMillis: Long = TIMEOUT_MS,
        onFailure: (String, String) -> Unit = { _, _ -> }): ToolOutcome {
        val words = GroupSpeechRules.words(raw)
        require(groupId > 0 && speakerId > 0 && timeoutMillis > 0)
        val parent = currentCoroutineContext().job
        suspend fun fallback(): ToolOutcome {
            withContext(NonCancellable) { parent.ensureActive(); commit(words, null) }
            return ToolOutcome("声音没有合成，原话已作为文字保存在本群；没有发送语音，不要说对方已经听到。", "语音未合成，已保留文字")
        }
        val sound = try { withTimeoutOrNull(timeoutMillis) { request(words) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onFailure("request", if (e is SpeechException) e.category else e.javaClass.simpleName); null }
        parent.ensureActive()
        if (sound == null || !GroupSpeechRules.sound(sound.first, sound.second)) {
            if (sound == null) onFailure("no_audio", "unavailable")
            else onFailure("container", Speech.extension(sound.second))
            return fallback()
        }
        var saved: MessageAudio? = null
        var committed = false
        var commitStarted = false
        try {
            withContext(NonCancellable) {
                parent.ensureActive()
                val audio = save(words, sound.first, sound.second).also { saved = it }
                val clip = GroupSpeechClip(audio.file, audio.ms, words)
                if (!GroupSpeechRules.valid(clip, groupId, speakerId)) onFailure("metadata", "ms_${audio.ms}")
                require(GroupSpeechRules.valid(clip, groupId, speakerId)) { "语音文件不完整，没有发送" }
                parent.ensureActive()
                commitStarted = true
                commit(words, clip)
                committed = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            parent.ensureActive()
            if (commitStarted) throw e
            onFailure("store", e.javaClass.simpleName)
            return fallback()
        } finally {
            if (!committed) saved?.let { withContext(NonCancellable) { remove(it.file) } }
        }
        return ToolOutcome("这条短语音及其文字已保存到本群，实际作者是成员 $speakerId；其他成员只读取文字，不声称听过声音。不要再重复发同一句文字。", "语音已保存")
    }
}
