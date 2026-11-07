package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.*

data class TranslationKey(val group: Boolean, val id: Long)

object MessageTranslationRules {
    const val MAX_SOURCE = 12_000
    const val MAX_TRANSLATION = 24_000
    val languages = linkedMapOf("zh-CN" to "简体中文", "zh-TW" to "繁體中文", "en" to "英语", "ja" to "日语", "ko" to "韩语",
        "fr" to "法语", "es" to "西班牙语", "de" to "德语", "ru" to "俄语", "pt" to "葡萄牙语")
    fun source(message: MessageEntity): String? = if (message.id > 0 && message.role == "assistant" && message.error == null &&
        message.call == null && message.feedShare == null) words(message.content) else null
    fun source(message: GroupMessageEntity): String? = if (message.id > 0 && message.role == "assistant" && message.status == "sent" &&
        message.error == null && message.speakerId != null && !message.content.startsWith("[群通话")) words(message.content) else null
    private fun words(raw: String): String? = StickerText.without(raw).trim().takeIf { it.isNotBlank() && it.length <= MAX_SOURCE }
    fun stamp(key: TranslationKey, parent: Long, createdAt: Long, source: String): String = WorldReading.fingerprint(buildJsonArray {
        add(key.group); add(key.id); add(parent); add(createdAt); add(source)
    }.toString())
    fun stamp(message: MessageEntity) = source(message)?.let { stamp(TranslationKey(false, message.id), message.conversationId, message.createdAt, it) }
    fun stamp(message: GroupMessageEntity) = source(message)?.let { stamp(TranslationKey(true, message.id), message.groupId, message.createdAt, it) }
}
