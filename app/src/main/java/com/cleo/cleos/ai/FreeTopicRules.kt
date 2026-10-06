package com.cleo.cleos.ai

import java.time.ZonedDateTime

/** Frequency controls opportunities to think, not a quota of messages to send. */
object FreeTopicRules {
    const val IDLE_MS = 15 * 60_000L
    data class Level(val id: Int, val label: String, val minHours: Int, val maxHours: Int, val dailyMax: Int)
    val LEVELS = listOf(Level(0, "偶尔", 4, 6, 3), Level(1, "自然", 2, 3, 6), Level(2, "比较主动", 1, 2, 10))
    fun level(id: Int) = LEVELS.firstOrNull { it.id == id } ?: LEVELS[1]
    fun minute(value: Int, fallback: Int) = value.takeIf { it in 0..1439 } ?: fallback
    fun time(value: Int) = "%02d:%02d".format(value / 60, value % 60)

    fun quiet(now: ZonedDateTime, on: Boolean, start: Int, end: Int): Boolean {
        if (!on) return false
        val m = now.hour * 60 + now.minute
        return if (start == end) true else if (start < end) m >= start && m < end else m >= start || m < end
    }

    fun outsideQuiet(at: ZonedDateTime, on: Boolean, start: Int, end: Int): ZonedDateTime {
        if (!quiet(at, on, start, end)) return at
        if (start == end) return at.plusDays(1)
        var resume = at.withHour(end / 60).withMinute(end % 60).withSecond(0).withNano(0)
        if (!resume.isAfter(at)) resume = resume.plusDays(1)
        return resume
    }

    fun next(now: ZonedDateTime, level: Level, on: Boolean, start: Int, end: Int, fraction: Double): Long {
        val minutes = (level.minHours * 60 + (level.maxHours - level.minHours) * 60 * fraction.coerceIn(0.0, 1.0)).toLong()
        return outsideQuiet(now.plusMinutes(minutes), on, start, end).toInstant().toEpochMilli()
    }

    fun held(enabled: Boolean, quiet: Boolean, occupied: Boolean, lastActivity: Long?, now: Long,
             unanswered: Int, attemptsToday: Int, maximum: Int): String? = when {
        !enabled -> "自由找话题已关闭"
        quiet -> "免打扰时段，先保持安静"
        occupied -> "正在聊天或输入，先不打断"
        lastActivity == null -> "还没有聊过，等第一次对话后再来"
        now - lastActivity < IDLE_MS -> "刚刚聊过，先留一点空闲"
        unanswered >= LaterRules.UNANSWERED_MAX -> "前面主动说的还没回，先等你回来"
        attemptsToday >= maximum -> "今天的考虑次数已经用完"
        else -> null
    }

    val instruction = """
        （这是一次你自己决定要不要开口的机会，不是对方发来的消息，对方看不到这段提示。）
        你现在可以自由寻找话题，不必先有预约或提醒，也不必继续上次的话题。
        结合自己的性格、已经知道的记忆和共同经历，决定此刻有没有自然想分享、想聊的内容。
        可以聊一个你想展开的想法、问一个有内容的问题，或者自然地接起一个旧话题。
        没有想说的、已经聊过、对方说过要忙/睡觉/想安静，就只回复 SKIP。保持安静完全可以。
        不要因对方没回而催促、埋怨、索要关注，也不要说“你很久没理我了”。
        不要编造自己刚刚在现实中做了什么、看到什么新闻或对方正在做什么；未知的事不要当事实。
        想说就像平常一样发消息，简短自然，别重复最近的主动消息，也别提定时器、频率或系统提示。
        这是一次机会，不要为了占满机会硬找话题，也不要给自己预约下一次例行找话题。
    """.trimIndent()
}
