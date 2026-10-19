package com.cleo.cleos.ai

import com.cleo.cleos.data.SettingsRepository
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.TodoEntity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When the person's days usually start and end, as the app can tell without asking or tracking
 * anything: from when they wrote to the TAs over the last two weeks. Null until there are enough
 * days to go on. [firstSaid] is when they first write in the morning, which is after they wake
 * (people look at other things first), so a greeting is looked into a little before it.
 */
data class Habits(val firstSaid: LocalTime?, val lastSaid: LocalTime?, val days: Int)

/** The two greetings of the day (RoutineRules). */
enum class Greeting { Morning, Night }

/**
 * A greeting at the start and at the end of the person's day. The person's earlier app had
 * settled on no scheduled messages at all: a greeting on the dot every morning gives away that it
 * is a timer, not the TA wanting to speak. Asked for all the same ("sometimes a message doesn't
 * need a reason, it's more like being greeted"), it is kept as far from a timer as it can be: the
 * time follows when the person's days actually start and end; in the morning it waits until the
 * phone is in use (screen on, past the lock screen), and not at all if they have already come to talk; at night only if
 * the phone is still in use and they aren't mid-conversation; and the TA, seeing the day's
 * calendar and todos, decides whether to say anything, and what. Once a day each, the TA being
 * talked to only.
 */
object RoutineRules {
    const val LOOKBACK_DAYS = 14L
    const val MIN_DAYS = 5

    /** A day runs from five in the morning: a message at half past one belongs to the night before. */
    private const val DAY_SHIFT_HOURS = 5L
    private val DAY_START = LocalTime.of(DAY_SHIFT_HOURS.toInt(), 0)

    /** When a greeting is looked into, relative to the usual time, and until when. */
    val MORNING_FROM: Duration = Duration.ofMinutes(-20)
    val MORNING_UNTIL: Duration = Duration.ofMinutes(150)
    val NIGHT_FROM: Duration = Duration.ofMinutes(-30)
    val NIGHT_UNTIL: Duration = Duration.ofMinutes(60)

    /** How often the screen is looked at while the person isn't up yet (or has put the phone down). */
    const val CHECK_MINUTES = 10L

    /** At night, a message this recent means the two are talking: no separate goodnight. */
    val TALKING: Duration = Duration.ofMinutes(30)

    /** Mornings that start later than this, or nights that end earlier, aren't what these greetings are for. */
    private val LATEST_MORNING = LocalTime.of(12, 0)
    private val EARLIEST_NIGHT = LocalTime.of(20, 0)

    /** The day [at] belongs to (see [DAY_SHIFT_HOURS]). */
    fun dayOf(at: ZonedDateTime): LocalDate = at.minusHours(DAY_SHIFT_HOURS).toLocalDate()

    /** When [day] begins: its five o'clock. */
    fun startOf(day: LocalDate, zone: ZoneId): ZonedDateTime = day.atTime(DAY_START).atZone(zone)

    fun habits(times: List<Long>, zone: ZoneId): Habits {
        val byDay = times.map { Instant.ofEpochMilli(it).atZone(zone) }.groupBy(::dayOf)
        if (byDay.size < MIN_DAYS) return Habits(null, null, byDay.size)
        // Counted from the day's start, so that 00:40 comes after 23:10 rather than before 08:00.
        fun sinceStart(t: ZonedDateTime) = Duration.between(startOf(dayOf(t), zone), t).toMinutes()
        val first = median(byDay.values.map { day -> day.minOf(::sinceStart) })
        val last = median(byDay.values.map { day -> day.maxOf(::sinceStart) })
        return Habits(DAY_START.plusMinutes(first), DAY_START.plusMinutes(last), byDay.size)
    }

    private fun median(values: List<Long>): Long = values.sorted().let { it[it.size / 2] }

    fun window(greeting: Greeting, h: Habits, day: LocalDate, zone: ZoneId): ClosedRange<ZonedDateTime>? = when (greeting) {
        Greeting.Morning -> morning(h, day, zone)
        Greeting.Night -> night(h, day, zone)
    }

    /**
     * Today's window for the morning greeting, from [MORNING_FROM] before the usual first message to
     * [MORNING_UNTIL] after; null when there is no usual time, or it isn't a morning. Never starting
     * before the day does: a look at 04:50 would count as the night before's, find that one over,
     * and set up the next at 04:50 again.
     */
    fun morning(h: Habits, day: LocalDate, zone: ZoneId): ClosedRange<ZonedDateTime>? {
        val t = morningAt(h) ?: return null
        val at = day.atTime(t).atZone(zone)
        return maxOf(at.plus(MORNING_FROM), startOf(day, zone))..at.plus(MORNING_UNTIL)
    }

    /** The night's, around the usual last message (which can be after midnight, still the same day). */
    fun night(h: Habits, day: LocalDate, zone: ZoneId): ClosedRange<ZonedDateTime>? {
        val t = nightAt(h) ?: return null
        val at = (if (t.isBefore(DAY_START)) day.plusDays(1) else day).atTime(t).atZone(zone)
        return at.plus(NIGHT_FROM)..at.plus(NIGHT_UNTIL)
    }

    /** The usual first message, if it makes a morning. */
    private fun morningAt(h: Habits): LocalTime? = h.firstSaid?.takeIf { !it.isBefore(DAY_START) && it.isBefore(LATEST_MORNING) }

    /** The usual last one, if it makes a night: late in the evening, or after midnight. */
    private fun nightAt(h: Habits): LocalTime? = h.lastSaid?.takeIf { it.isBefore(DAY_START) || !it.isBefore(EARLIEST_NIGHT) }

    /** The line in settings: what the greetings go by, and which of them there will be. */
    fun describe(h: Habits): String {
        val first = h.firstSaid
        val last = h.lastSaid
        if (first == null || last == null) return "再多聊几天，TA 就知道你一般几点起、几点睡，到时候可能会来打个招呼。"
        val saidWhen = "你一般 ${first.format()} 左右开始找 TA 说话，${last.format()} 左右说最后一句"
        val morning = morningAt(h) != null
        val night = nightAt(h) != null
        return saidWhen + when {
            morning && night -> "。早上醒了、晚上睡前，TA 可能会来打个招呼。"
            morning -> "。早上醒了，TA 可能会来打个招呼。"
            night -> "。晚上睡前，TA 可能会来打个招呼。"
            else -> "。"
        }
    }

    private fun LocalTime.format() = "%d:%02d".format(hour, minute)

    private fun open(glance: String?) = if (glance == null) "" else "下面是可能用得上的，挑一件真有用的带上就好，别念清单：\n$glance\n"

    /** What the TA reads when the person's day seems to have begun. */
    fun morningText(now: ZonedDateTime, glance: String?): String =
        "（这条不是对方发的，对方看不到。）\n" +
            Prompt.timeLine(now) + "。对方平时差不多这个时候起来，看样子已经醒了；今天你们还没说过话。\n" +
            "想的话可以打个招呼，像平常那样一两句。" + open(glance) +
            "不想说就直接结束，不输出文字，也不调用发送工具。别每天都说得差不多；别问「怎么还不起」「在干嘛」，也别提对方多久没理你。"

    /** And when it seems to be ending. */
    fun nightText(now: ZonedDateTime, lastTalked: ZonedDateTime?, glance: String?): String =
        "（这条不是对方发的，对方看不到。）\n" +
            Prompt.timeLine(now) + "。对方平时差不多这个时候睡，这会儿还醒着" +
            (lastTalked?.let { "；你们上次说话是" + LaterRules.at(it, now) } ?: "") + "。\n" +
            "想的话可以道个晚安，或者提一句明天要记得的，一两句就好。" + open(glance) +
            "不想说就直接结束，不输出文字，也不调用发送工具。别说教、别催对方睡，也别提对方多久没理你。"
}

/**
 * What a TA waking on its own may find useful: the calendar for the hours ahead and the todos not
 * done, each only when its switch is on (and, for the calendar, allowed). Offered, not handed as
 * an agenda: the wake texts ask for one thing worth bringing, not a list read out.
 */
class Glance(
    private val db: AppDatabase,
    private val calendar: CalendarSource?,
    private val settings: SettingsRepository,
    private val calendarAllowed: () -> Boolean,
) {
    suspend fun of(from: ZonedDateTime, to: ZonedDateTime, now: ZonedDateTime): String? {
        val s = settings.current()
        val lines = ArrayList<String>(2)
        val cal = calendar
        if (cal != null && ToolGroup.Calendar in s.tools && calendarAllowed()) {
            val events = runCatching { cal.events(from.toInstant().toEpochMilli(), to.toInstant().toEpochMilli()) }.getOrDefault(emptyList())
            if (events.isNotEmpty()) {
                lines += "日程：" + events.take(SHOWN).joinToString("；") { CalendarText.whenText(it, now.zone, now.toLocalDate()) + " " + it.title.trim() }
            }
        }
        if (ToolGroup.Todos in s.tools) todos(db.todos().all(), now.toLocalDate())?.let(lines::add)
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    companion object {
        private const val SHOWN = 5

        /** The unfinished ones, what is due or overdue first, then the newest without a date. */
        fun todos(all: List<TodoEntity>, today: LocalDate): String? {
            val open = all.filter { !it.done }
            if (open.isEmpty()) return null
            val t = today.toEpochDay()
            val ordered = open.filter { it.dueDay != null }.sortedBy { it.dueDay } + open.filter { it.dueDay == null }.sortedByDescending { it.createdAt }
            return "没做完的待办：" + ordered.take(SHOWN).joinToString("；") { todo ->
                val due = todo.dueDay?.let { d ->
                    when {
                        d < t -> "（过期 ${t - d} 天）"
                        d == t -> "（今天）"
                        d == t + 1 -> "（明天）"
                        else -> "（" + LocalDate.ofEpochDay(d).let { "${it.monthValue}月${it.dayOfMonth}日" } + "）"
                    }
                }.orEmpty()
                todo.title.trim() + due
            }
        }
    }
}
