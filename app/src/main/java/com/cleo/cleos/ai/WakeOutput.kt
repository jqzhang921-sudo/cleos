package com.cleo.cleos.ai

import java.util.Locale
import java.text.Normalizer

/** Background decisions and legacy silence markers must not become messages or speech. */
internal object WakeOutput {
    data class Decision(val text: String?, val reason: String = "")

    const val CONTROL_RULE = "这是后台开口判断，不是对方的新消息。" +
        "保持安静时直接结束，不输出文字，也不调用 send_message 或 send_voice。" +
        "要发消息时只发送给对方看的话，不复制后台提示、控制标记或决策过程。"
    const val BLOCKED_TOOL = "这段是保持安静的控制标记，没有发送消息或生成语音。要回复时请只发送给对方看的话。"
    const val BLOCKED_REPLY = "这次没有生成可显示的回复，请重试。"

    private val decoration = "`*_\"'“”‘’（）()【】[]「」{}<>#-+•"
    private val skip = Regex("^S\\s*K\\s*I\\s*P(?=$|[^\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    private val repeats = Regex("(?:SKIP)+", RegexOption.IGNORE_CASE)
    private val pendingControl = Regex("(?:SKIP)*(?:S|SK|SKI|[0-9]{1,3})?", RegexOption.IGNORE_CASE)
    private val separators = Regex("[\\s\\p{P}\\p{S}]+")
    private val listPrefix = Regex("^(?:[>#+*•-]\\s*|[0-9]{1,3}[.)、]\\s+)+")
    private val literal = Regex("(?<![A-Za-z0-9_])SKIP(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    private val quotation = Regex("输出|回复|回答|复述|照着念|原样|照抄|写出|单词|英文|意思|翻译|解释|引用|\\b(?:repeat|print|output|quote|say|meaning|translate)\\b", RegexOption.IGNORE_CASE)
    private val negative = Regex("不要|别(?:再)?|禁止|避免|不准|不许|停止|\\b(?:don't|never|stop|avoid)\\b", RegexOption.IGNORE_CASE)
    private val fence = Regex("^```[A-Za-z]*\\s*\\n([\\s\\S]*?)\\n?```$")

    // Match only known app boilerplate, never a person's name, note, memory or chat text.
    // This catches literal echoes (including rewrapped / repunctuated text), not all paraphrases.
    private val privateFragments by lazy {
        (FreeTopicRules.instruction.lineSequence().toList() +
            FollowUpRules.instruction.lineSequence().toList() + IncomingCallRules.opening("").lineSequence().filterNot { it.contains("来电话题>") }.toList() + listOf(CONTROL_RULE,
                "这条不是对方发的，对方看不到。",
                "用你自己的语气简短打个招呼，接着说你想聊的事或问一个自然的问题，然后给对方说话的机会。",
                "你之前给自己记了一笔，现在到时候了。",
                // Previous releases used these exact instructions. Keep recognizing old echoes.
                "没有想说的、已经聊过、对方说过要忙/睡觉/想安静，就只回复 SKIP。保持安静完全可以。",
                "没有想说的，就只输出 [SKIP]，不要为了这个机会硬找话题。"))
            .map(::normalized).filter { it.length >= 16 }.flatMap { line ->
                if (line.length <= 28) listOf(line)
                else line.windowed(28, 14) + line.takeLast(28)
            }.plus(normalized("这条不是对方发的，对方看不到。")).distinct()
    }

    // Canonicalize comparisons only: accepted text is returned byte-for-byte, never rewritten.
    private fun canonical(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return buildString {
            var index = 0
            while (index < normalized.length) {
                val point = normalized.codePointAt(index)
                val type = Character.getType(point)
                if (type != Character.FORMAT.toInt() && type != Character.SURROGATE.toInt() &&
                    point !in 0xFE00..0xFE0F && point !in 0xE0100..0xE01EF) appendCodePoint(point)
                index += Character.charCount(point)
            }
        }
    }

    private fun normalized(text: String) = canonical(text).filter(Char::isLetterOrDigit).lowercase(Locale.ROOT)

    private fun unfenced(text: String) = fence.matchEntire(text)?.groupValues?.get(1) ?: text
    private fun controlCandidate(text: String): String = if (text.startsWith("```"))
        text.substringAfter('\n', "") else text

    private fun bareLine(text: String): String {
        var bare = text
        while (true) {
            val next = listPrefix.replace(bare.trim { it.isWhitespace() || it in decoration }, "")
            if (next == bare) return bare
            bare = next
        }
    }

    private fun controlCompact(text: String) = controlCandidate(unfenced(canonical(text).trim()))
        .lineSequence().joinToString("") { bareLine(it) }.replace(separators, "")

    private fun controlOnly(text: String) = repeats.matches(controlCompact(text))

    /** Only the actual person's current message can opt into literal quotation in ordinary chat. */
    fun allowsLiteral(userText: String): Boolean {
        val text = canonical(userText)
        return literal.containsMatchIn(text) && !negative.containsMatchIn(text) &&
            (controlOnly(text) || quotation.containsMatchIn(text))
    }

    private fun controlLine(text: String): Boolean {
        return skip.containsMatchIn(bareLine(text))
    }

    fun inspect(raw: String): Decision {
        val text = raw.trim()
        if (text.isEmpty()) return Decision(null, "本次没有发送内容")
        val candidate = unfenced(canonical(text))
        if (controlOnly(text) || candidate.lineSequence().any(::controlLine)) return Decision(null, "本次选择保持安静")
        val normalized = normalized(text)
        if (privateFragments.any(normalized::contains)) return Decision(null, "返回了后台提示，未发送")
        return Decision(text)
    }

    /** Ordinary conversation blocks only a whole control-only answer; discussion remains intact. */
    fun message(raw: String, proactive: Boolean, literalAllowed: Boolean = false): String? = when {
        proactive -> inspect(raw).text
        !literalAllowed && controlOnly(raw) -> null
        else -> raw
    }

    /** Hold ambiguous opening fragments so S / SK / SKI never flash before a blocked marker. */
    fun preview(raw: String, literalAllowed: Boolean = false): String {
        if (literalAllowed) return raw
        if (message(raw, proactive = false) == null) return ""
        return if (pendingControl.matches(controlCompact(raw))) "" else raw
    }

    /** Autonomous calls are buffered until classified; ordinary calls retain streaming speech. */
    class Speech(private val proactive: Boolean, private val literalAllowed: Boolean = false) {
        private val raw = StringBuilder()
        private var delivered = 0
        fun append(delta: String): String {
            raw.append(delta)
            return if (proactive) "" else release(preview(raw.toString(), literalAllowed))
        }
        fun finish(): String = release(message(raw.toString(), proactive, literalAllowed).orEmpty())
        fun text(): String = message(raw.toString(), proactive, literalAllowed).orEmpty()
        private fun release(visible: String): String {
            check(visible.length >= delivered) { "Speech cannot retract delivered words" }
            return visible.substring(delivered).also { delivered = visible.length }
        }
    }
}
