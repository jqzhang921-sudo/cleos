package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.GroupMessageEntity
import com.cleo.cleos.data.db.ChatGroupEntity
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Serializable
data class GroupVoiceDraft(val file: String, val ms: Long, val text: String = "", val error: String? = null) {
    fun audio() = MessageAudio(file, ms)
}

object GroupVoiceRules {
    private val json = Json { ignoreUnknownKeys = true }
    fun prefix(groupId: Long) = "voice_group_${groupId}_"
    fun valid(audio: MessageAudio, groupId: Long): Boolean = groupId > 0 &&
        audio.file.startsWith(prefix(groupId)) && audio.file.matches(Regex("[A-Za-z0-9_-]+\\.wav")) &&
        audio.ms in Voice.MIN_MS..Voice.MAX_MS
    fun audio(raw: String?, groupId: Long): MessageAudio? = MessageAudios.decode(raw)?.takeIf { valid(it, groupId) }
    fun validAudio(raw: String?, groupId: Long) = raw == null || raw.length <= 2000 && audio(raw, groupId) != null
    fun draft(raw: String?, groupId: Long): GroupVoiceDraft? = if (raw == null || raw.length > 30000) null else
        runCatching { json.decodeFromString<GroupVoiceDraft>(raw) }.getOrNull()?.takeIf {
            valid(it.audio(), groupId) && it.text.length <= GroupChatRules.MAX_TEXT && (it.error?.length ?: 0) <= 220
        }
    fun validDraft(raw: String?, groupId: Long) = raw == null || draft(raw, groupId) != null
    fun checkedDraft(raw: String?, groupId: Long): GroupVoiceDraft? {
        require(validDraft(raw, groupId)) { "群语音草稿不完整，请重新录制" }
        return draft(raw, groupId)
    }
    fun encode(draft: GroupVoiceDraft) = json.encodeToString(draft)

    fun validWav(audio: MessageAudio, length: Long, header: ByteArray): Boolean {
        if (header.size != 44 || length !in 44L..(44 + Voice.RATE * 2L * Voice.MAX_MS / 1000)) return false
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int, value: String) = header.copyOfRange(at, at + 4).contentEquals(value.toByteArray(Charsets.US_ASCII))
        val bytes = length - 44
        return tag(0,"RIFF") && tag(8,"WAVE") && tag(12,"fmt ") && tag(36,"data") &&
            b.getInt(4).toLong() == length - 8 && b.getInt(16) == 16 && b.getShort(20).toInt() == 1 &&
            b.getShort(22).toInt() == 1 && b.getInt(24) == Voice.RATE && b.getInt(28) == Voice.RATE * 2 &&
            b.getShort(32).toInt() == 2 && b.getShort(34).toInt() == 16 && b.getInt(40).toLong() == bytes &&
            bytes % 2 == 0L && audio.ms == bytes * 1000 / (Voice.RATE * 2) && audio.ms in Voice.MIN_MS..Voice.MAX_MS
    }
    fun requireFile(audio: MessageAudio, groupId: Long, file: File) {
        require(valid(audio, groupId) && file.name == audio.file && file.isFile) { "这段群语音读不到，请重新录制" }
        val header = ByteArray(44)
        runCatching { DataInputStream(file.inputStream()).use { it.readFully(header) } }.getOrElse {
            throw IllegalArgumentException("这段群语音不完整，请重新录制")
        }
        require(validWav(audio, file.length(), header)) { "这段群语音格式不完整，请重新录制" }
    }
    fun context(raw: String?, groupId: Long) = if (audio(raw, groupId) == null) "" else
        "\n[这是用户语音的转写文字，本次没有提供原始声音；不推断声音、语调或情绪，疑似识别错误可向用户核对。]"
    fun messageAudio(row: GroupMessageEntity): MessageAudio? = if (row.role == "user") audio(row.audio, row.groupId)
        else GroupSpeechRules.clip(row)?.audio()
    fun context(row: GroupMessageEntity) = if (row.role == "user") context(row.audio, row.groupId)
        else if (GroupSpeechRules.clip(row) != null) "\n[这位 TA 已保存群语音；此处是语音对应的文字，没有提供原始声音，不推断音色、语调或情绪。]" else ""
    fun restored(row: GroupMessageEntity, exists: (String) -> Boolean): GroupMessageEntity =
        if (row.audio != null && messageAudio(row)?.let { !exists(it.file) } == true)
            row.copy(audio = null, error = "备份中的语音文件缺失，转写文字仍保留") else row
    fun restoredDraft(group: ChatGroupEntity, exists: (String) -> Boolean): ChatGroupEntity {
        val voice = draft(group.draftAudio, group.id) ?: return group
        return if (exists(voice.file)) group else group.copy(draftAudio = null,
            draft = voice.text.takeIf { it.isNotBlank() } ?: group.draft)
    }
    fun failure(status: Int?) = when (status) {
        401, 403 -> "转文字的 Key 不可用，请检查「发语音」配置后重试"
        402, 429 -> "转文字的额度不足或请求太频繁，可以稍后重试"
        else -> "这段语音没转成文字，可以重试，也可以自己填写文字后发送"
    }
}

object GroupVoiceTranscription {
    const val TIMEOUT_MS = 75_000L
    suspend fun run(timeoutMillis: Long = TIMEOUT_MS, request: suspend () -> String): String {
        require(timeoutMillis > 0)
        val text = withTimeoutOrNull(timeoutMillis) { request().trim() }
            ?: error("转文字等待太久了，可以重试")
        require(text.isNotBlank()) { "没听清，可以重新转文字或自己填写" }
        require(text.length <= GroupChatRules.MAX_TEXT) { "转写太长了，请录短一点的语音" }
        return text
    }
}
