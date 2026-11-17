package com.cleo.cleos.ai

import com.cleo.cleos.data.db.LetterDispatchEntity
import com.cleo.cleos.data.db.MessageEntity
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object InstantLetterRules {
    const val AUTO = "auto"
    const val REQUESTED = "requested"
    const val OFF = "off"
    const val COOLDOWN = 6 * 60 * 60_000L
    const val DAY = 24 * 60 * 60_000L
    const val DAILY_MAX = 2
    fun mode(raw: String) = raw.takeIf { it in setOf(AUTO, REQUESTED, OFF) } ?: REQUESTED

    data class LetterIntent(val title: String, val body: String, val themeId: String?)
    fun storedCalls(calls: List<ToolCall>, letterId: Long? = null, pending: Boolean = false): List<ToolCall> = calls.map { call ->
        call.copy(arguments = buildJsonObject {
            if (call.name == ToolSpecs.writeInstantLetter.name) put("content", when {
                letterId != null -> "（正文见独立信件 #$letterId）"
                pending -> "（信件待校验，尚未寄出）"
                else -> "（信件未寄出，没有保存正文）"
            }) else put("skipped",true)
        }.toString())
    }
    fun arguments(raw: String): LetterIntent {
        val args = ToolArgs.parse(raw) ?: error("信件参数不是有效 JSON，请按工具格式重试。")
        require(args.keys.all { it in setOf("content", "title", "theme_id") }) { "信件参数包含不支持的字段，请只提供标题、正文和主题。" }
        fun string(key: String, required: Boolean = false): String? {
            val v = args[key] ?: if (required) error("缺少信件正文，没有寄出。") else return null
            require(v is JsonPrimitive && v.isString) { "信件字段需要是文字，没有寄出。" }
            return v.content
        }
        val body = string("content",true)!!
        val title = string("title") ?: ""
        require(body.isNotBlank() && body.length <= com.cleo.cleos.data.InstantLetterData.MAX_BODY && title.length <= com.cleo.cleos.data.InstantLetterData.MAX_TITLE) { "信件正文为空或过长，没有寄出。" }
        require(!protocolPayload(body)) { "正文包含未解析的信件结构，没有寄出。" }
        return LetterIntent(title,body,string("theme_id"))
    }

    /** Positive, direct user requests only. Ambiguous reported/quoted instructions get no exemption. */
    fun requested(message: MessageEntity?): Long? {
        val m = message?.takeIf { it.id > 0 && it.role == "user" && it.note == null && it.letterId == null && it.feedShare == null } ?: return null
        if (m.letterRequested) return m.id
        val text = m.content.trim().lowercase(Locale.ROOT)
        if (text.length !in 2..2000 || text.contains("```") || Regex("[“「『\"]").containsMatchIn(text)) return null
        if (Regex("有没有|是否已经|之前|昨天|前天|上次|过去|写过|写的信|寄的信|那封信|(?:did you|have you|yesterday|last time|previous)\\b").containsMatchIn(text)) return null
        if (Regex("不要|不用|不需要|不想|别写|别给|禁止|停止|不准|怎么|如何|教程|什么意思|比如|例如|她说|他说|有人说|引用|翻译|改写|修改|(?:don't|do not|never|no need|how to|example|translate|rewrite|she said|he said)\\b").containsMatchIn(text)) return null
        val direct = Regex("(?:给我|给我们|帮我|为我|想让你|想请你|你能|你可以|能不能|可不可以|请你|麻烦你).{0,24}(?:写|寄|发|回).{0,10}信|(?:写|寄|发|回).{0,8}信.{0,12}(?:给我|给我们|好吗|好不好|吧)|^(?:请|你|现在|再|可以|能否|能不能|可不可以|帮忙|帮我|给我|想要|来|我想要|我想收到|我想看){0,5}(?:写|寄|发|回|收到|一封|封).{0,6}信[吧好吗呀呢嘛！!？?。\\s]*$")
        val english = Regex("(?:^|\\b(?:please|can you|could you|would you)\\s+)(?:write|send|compose|draft|reply).{0,35}\\bletter\\b")
        // In direct requests, 封 already identifies a letter: “你也给我写一封”.
        // Keep this anchored and bounded; reported/negated/quoted text was rejected above.
        val implicit = Regex("^(?:那|好|嗯|你|也|再|现在|请|请你|麻烦你|能不能|可不可以|能|可以|就|，|,|\\s)*(?:给我|帮我|为我)?(?:也|再|先)?(?:写|寄|回)(?:一|1)?封(?:信)?(?:吧|嘛|呀|呗|好吗|好不好|可以吗|行吗|呢|[！!？?。\\s])*$")
        return m.id.takeIf { direct.containsMatchIn(text) || english.containsMatchIn(text) || implicit.matches(text) }
    }

    fun available(mode: String, request: Long?, wake: Boolean = false): Boolean = when (mode(mode)) {
        OFF -> false
        REQUESTED -> !wake && request != null
        else -> true
    }

    /** Discovery is stable across private-chat turns; delivery still needs [available]. */
    fun offered(mode: String): Boolean = mode(mode) != OFF

    /** Quota is checked again in the sending transaction using current settings and durable receipts. */
    fun held(mode: String, request: Long?, receipts: List<LetterDispatchEntity>, now: Long): String? {
        if (!available(mode, request)) return if (mode(mode) == OFF) "这位 TA 的即时写信已关闭。" else "只在用户明确请求时写信；没有确认到这次请求。"
        if (request != null) {
            if (receipts.any { it.requestMessageId == request }) return "这次请求已经收到过一封信，不再重复寄出。"
            return null
        }
        if (receipts.any { it.createdAt > now || now - it.createdAt < COOLDOWN }) return "最近已经写过信，自动写信至少间隔 6 小时。"
        if (receipts.count { it.requestMessageId == null && it.createdAt >= now - DAY } >= DAILY_MAX) return "最近 24 小时的自动写信次数已用完。"
        return null
    }

    /** Raw protocol output is never treated as an already-sent letter. */
    fun protocolPayload(raw: String): Boolean = Regex("[\"']message_type[\"']\\s*:\\s*[\"']letter[\"']", RegexOption.IGNORE_CASE).containsMatchIn(raw)
    fun preview(raw: String, letterToolOffered: Boolean): String =
        if (protocolPayload(raw) || letterToolOffered && (raw.trimStart().startsWith("{") || raw.trimStart().startsWith("```"))) "" else raw

    fun validateReceipts(receipts: List<LetterDispatchEntity>, letters: List<com.cleo.cleos.data.db.LetterEntity>, owners: Set<Long>) {
        require(receipts.map { it.origin }.distinct().size == receipts.size) { "信件投递记录重复" }
        require(receipts.map { it.letterId }.distinct().size == receipts.size) { "信件投递编号重复" }
        val byId = letters.associateBy { it.id }
        receipts.forEach { r ->
            require(r.origin.length in 1..200 && r.origin.startsWith("${r.companionId}:") && r.origin.none { Character.isISOControl(it) } &&
                r.companionId in owners && r.letterId > 0 && r.createdAt >= 0 && (r.requestMessageId == null || r.requestMessageId > 0)) { "信件投递记录无效" }
            byId[r.letterId]?.let { require(it.companionId == r.companionId && it.author == "ai" && it.sourceType == "instant" && !it.draft) { "投递记录与信件不匹配" } }
        }
    }
}
