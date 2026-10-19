package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.CompanionEntity
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Only voice IDs are per TA. Service, endpoint, model and encrypted credentials remain shared. */
object TaVoices {
    const val MAX_CHOICES = 16
    const val MAX_VOICE = 512
    private const val MAX_RAW = 12_000
    private val keyPattern = Regex("v1_[a-f0-9]{32}")

    fun scope(s: AppSettings): String? {
        val service = Speech.service(s) ?: return null
        if (service == VoiceService.Other && (s.speechBaseUrl.isBlank() || s.speechModel.isBlank())) return null
        val parts = listOf(service.key, Speech.keyAddress(s, service).trim().trimEnd('/'),
            if (service == VoiceService.Other) s.speechModel.trim() else "")
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        return "v1_$digest"
    }

    private fun voiceValid(value: String) = value.isNotBlank() && value == value.trim() &&
        value.length <= MAX_VOICE && value.none { it.isISOControl() }

    fun checked(raw: String): Map<String, String> {
        require(raw.length <= MAX_RAW) { "专属音色配置太大，未保存" }
        val choices = runCatching { Json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
        require(choices != null && choices.size <= MAX_CHOICES && choices.all { (k, v) -> keyPattern.matches(k) && voiceValid(v) }) {
            "专属音色配置不完整，未保存"
        }
        return choices
    }

    fun validateBackup(tas: List<CompanionEntity>) {
        require(tas.all { runCatching { checked(it.voiceChoices) }.isSuccess }) { "备份中的专属音色配置不完整，未恢复" }
    }

    fun choice(raw: String, s: AppSettings): String? = scope(s)?.let { key ->
        runCatching { checked(raw)[key] }.getOrNull()
    }

    fun saved(raw: String, scope: String, voice: String?): String {
        require(keyPattern.matches(scope)) { "声音平台已变化，请重新打开" }
        val before = checked(raw)
        val value = voice?.trim()?.also { require(voiceValid(it)) { "音色 ID 需要 1–$MAX_VOICE 字，不能包含换行" } }
        val next = if (value == null) before - scope else before + (scope to value)
        require(next.size <= MAX_CHOICES) { "专属音色配置已满，请先切回一个旧平台并使用共用音色" }
        return Json.encodeToString(next).also { checked(it) }
    }

    fun effective(s: AppSettings, ta: CompanionEntity): AppSettings = withVoice(s, choice(ta.voiceChoices, s))

    /** Preview and saved choices go through the same provider-specific voice field. Null inherits. */
    fun withVoice(s: AppSettings, voice: String?): AppSettings {
        if (voice == null) return s
        require(voiceValid(voice))
        return when (val service = Speech.service(s)) {
            null -> s
            VoiceService.Other -> s.copy(speechVoice = voice)
            VoiceService.ElevenLabs -> s.copy(elevenVoice = voice)
            else -> s.copy(speechVoices = s.speechVoices + (service.key to voice))
        }
    }
}
