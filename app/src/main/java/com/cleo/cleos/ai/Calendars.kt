package com.cleo.cleos.ai

import android.Manifest
import android.content.ContentUris
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import androidx.core.content.ContextCompat
import com.cleo.cleos.data.CourseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One event on the phone's calendar as the tools show it. [ours]: in Cleos's own calendar, so a TA may change it. */
data class CalEvent(
    val id: Long,
    val title: String,
    /** For an all-day event, midnight UTC of its first day, the way the calendar stores it. */
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String = "",
    val notes: String = "",
    val calendar: String = "",
    val ours: Boolean = false,
)

/**
 * What add_event and update_event ask for; null leaves a field as it is (or empty, adding).
 * [remind]: minutes before; [NO_REMINDER] takes the reminder away.
 */
data class EventDraft(
    val title: String? = null,
    val begin: Long? = null,
    val end: Long? = null,
    val allDay: Boolean? = null,
    val location: String? = null,
    val notes: String? = null,
    val remind: Int? = null,
) {
    companion object {
        const val NO_REMINDER = -1
    }
}

/**
 * The phone's calendar. Everything on it can be read; what a TA adds goes into a calendar of
 * Cleos's own, and only that can be changed or deleted. The person's own events are theirs: a
 * model that misreads "move it to four" must not be able to shift a real meeting, and a TA's
 * events in a calendar of their own can be hidden, or removed all at once, in the calendar app.
 */
interface CalendarSource {
    /** Each event (each time a repeating one comes round) overlapping [from, to), soonest first. Throws [ToolFailure]. */
    suspend fun events(from: Long, to: Long): List<CalEvent>

    suspend fun event(id: Long): CalEvent?

    /** Into Cleos's calendar, made the first time; the new event's id. */
    suspend fun add(draft: EventDraft): Long

    /** Only one of Cleos's; throws [ToolFailure] for anything else. */
    suspend fun update(id: Long, draft: EventDraft)

    suspend fun delete(id: Long)
}

object CalendarText {
    /** A start or end as the model wrote it: a time, or a whole day. */
    data class When(val day: LocalDate, val time: LocalTime?) {
        val allDay: Boolean get() = time == null
    }

    /** "2026-10-03 15:00", "2026-10-03T15:00", "2026/10/3 9:05", "10-3 15:00" (this year); a date alone is the whole day. */
    fun parse(s: String, today: LocalDate): When {
        val t = s.trim().replace('T', ' ')
        val parts = t.split(Regex("\\s+"), limit = 2)
        val day = ToolArgs.day(parts[0], today)
        if (parts.size == 1) return When(day, null)
        return When(day, AlarmText.time(parts[1]))
    }

    /**
     * Start and end as the calendar stores them. A timed event without an end lasts an hour; an
     * all-day one covers its days, midnight to midnight in UTC, which is how calendars keep them
     * whatever the phone's zone. An end before the start is sent back rather than swapped.
     */
    fun span(start: When, end: When?, zone: ZoneId): Pair<Long, Long> {
        if (start.allDay) {
            val last = end?.day ?: start.day
            if (last.isBefore(start.day)) throw ToolFailure("结束那天在开始之前了，改一下再加。", "结束早于开始")
            return utcMidnight(start.day) to utcMidnight(last.plusDays(1))
        }
        val begin = ZonedDateTime.of(start.day, start.time, zone).toInstant().toEpochMilli()
        val finish = when {
            end == null -> begin + HOUR
            end.allDay -> ZonedDateTime.of(end.day, LocalTime.of(23, 59), zone).toInstant().toEpochMilli()
            else -> ZonedDateTime.of(end.day, end.time, zone).toInstant().toEpochMilli()
        }
        if (finish <= begin) throw ToolFailure("结束时间不在开始之后，改一下再加。", "结束早于开始")
        return begin to finish
    }

    private fun utcMidnight(day: LocalDate) = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val clockTime = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val monthDay = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINA)

    /** "今天", "明天", "后天", else "10月3日 周六". */
    fun dayName(day: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, day)) {
        0L -> "今天"
        1L -> "明天"
        2L -> "后天"
        else -> day.format(monthDay)
    }

    /** "明天 15:00–16:00", "10月3日 周六 全天", "今天 22:00–明天 01:00". */
    fun whenText(e: CalEvent, zone: ZoneId, today: LocalDate): String {
        if (e.allDay) {
            val first = Instant.ofEpochMilli(e.begin).atZone(ZoneOffset.UTC).toLocalDate()
            val last = Instant.ofEpochMilli(e.end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
            return if (!last.isAfter(first)) "${dayName(first, today)} 全天" else "${dayName(first, today)}–${dayName(last, today)} 全天"
        }
        val b = Instant.ofEpochMilli(e.begin).atZone(zone)
        val f = Instant.ofEpochMilli(e.end).atZone(zone)
        val end = if (f.toLocalDate() == b.toLocalDate()) f.format(clockTime) else dayName(f.toLocalDate(), today) + " " + f.format(clockTime)
        return dayName(b.toLocalDate(), today) + " " + b.format(clockTime) + "–" + end
    }

    /** One line per event, with its id, what, where, and whose. */
    fun line(e: CalEvent, zone: ZoneId, today: LocalDate): String = buildString {
        append("#").append(e.id).append(" ").append(whenText(e, zone, today)).append(" ").append(e.title.ifBlank { "（没有标题）" })
        if (e.location.isNotBlank()) append("（地点：").append(e.location.trim()).append("）")
        if (e.notes.isNotBlank()) append("\n  备注：").append(e.notes.trim().replace('\n', ' ').take(NOTES_SHOWN))
        append(if (e.ours) " [你加的]" else " [对方的：${e.calendar.ifBlank { "日历" }}]")
    }

    /** What read_calendar answers: the days asked for, the events in them, or that there are none. */
    fun list(events: List<CalEvent>, first: LocalDate, last: LocalDate, zone: ZoneId, today: LocalDate): String {
        val range = if (first == last) dayName(first, today) else "${dayName(first, today)}到${dayName(last, today)}"
        if (events.isEmpty()) return "$range 日历上没有安排。"
        return "$range 日历上的安排（[你加的] 可以改、删；对方的只能看）：\n" + events.joinToString("\n") { line(it, zone, today) }
    }

    private const val HOUR = 3_600_000L
    private const val NOTES_SHOWN = 80
}

/** [CalendarSource] on the phone's calendar provider. Reading and writing both need the calendar permission. */
class PhoneCalendar(private val context: Context) : CalendarSource {
    private val resolver get() = context.contentResolver

    private fun check() {
        if (!allowed(context)) {
            throw ToolFailure("对方没给 App 日历权限，现在看不了也加不了。请对方在设置里把「日历」开关关掉再打开，会重新问。", "没有日历权限")
        }
    }

    override suspend fun events(from: Long, to: Long): List<CalEvent> = withContext(Dispatchers.IO) {
        check()
        readEvents(from, to)
    }

    /** Opening the agenda only requires reading the Android calendar. */
    internal suspend fun agenda(from: Long, to: Long): List<CalEvent> = withContext(Dispatchers.IO) {
        check(canRead(context)) { "请允许读取手机日历" }
        readEvents(from, to)
    }

    private fun readEvents(from: Long, to: Long): List<CalEvent> {
        val ours = ourCalendar(create = false)
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val out = ArrayList<CalEvent>()
        resolver.query(uri, INSTANCE_COLUMNS, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                out += CalEvent(
                    id = c.getLong(0),
                    title = c.getString(1).orEmpty(),
                    begin = c.getLong(2),
                    end = c.getLong(3),
                    allDay = c.getInt(4) == 1,
                    location = c.getString(5).orEmpty(),
                    notes = c.getString(6).orEmpty(),
                    calendar = c.getString(7).orEmpty(),
                    ours = ours != null && c.getLong(8) == ours,
                )
            }
        }
        return out
    }

    internal data class ImportedCourses(val added: Int, val skipped: Int)

    internal suspend fun importCourses(events: List<CourseEvent>): ImportedCourses = withContext(Dispatchers.IO) {
        check()
        courseImport.withLock {
            val calendar = localCalendar(create = true, name = COURSE_CALENDAR, displayName = "Cleos 课表")
                ?: error("手机日历没有创建课表，请检查日历权限")
            val keys = mutableSetOf<String>()
            resolver.query(asOwner(Events.CONTENT_URI), arrayOf(Events.SYNC_DATA1),
                "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0", arrayOf(calendar.toString()), null)
                ?.use { cursor -> while (cursor.moveToNext()) cursor.getString(0)?.let(keys::add) }
            val pending = events.mapNotNull { event ->
                val key = courseKey(event)
                if (!keys.add(key)) return@mapNotNull null
                val values = ContentValues().apply {
                    put(Events.CALENDAR_ID, calendar)
                    put(Events.TITLE, event.title)
                    put(Events.DTSTART, event.begin)
                    put(Events.EVENT_TIMEZONE, event.zone)
                    put(Events.ALL_DAY, if (event.allDay) 1 else 0)
                    put(Events.EVENT_LOCATION, event.location)
                    put(Events.DESCRIPTION, event.notes)
                    put(Events.SYNC_DATA1, key)
                    if (event.recurrence == null) {
                        put(Events.DTEND, event.end)
                    } else {
                        val seconds = (event.end - event.begin) / 1000
                        put(Events.DURATION, if (event.allDay) "P${seconds / 86_400}D" else "PT${seconds}S")
                        put(Events.RRULE, event.recurrence)
                        event.excluded?.let { put(Events.EXDATE, it) }
                    }
                }
                ContentProviderOperation.newInsert(asOwner(Events.CONTENT_URI)).withValues(values).build()
            }
            if (pending.isNotEmpty()) resolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(pending))
            ImportedCourses(pending.size, events.size - pending.size)
        }
    }

    private fun courseKey(event: CourseEvent): String {
        val values = listOf(event.uid, event.title, event.begin.toString(), event.end.toString(),
            event.zone, event.allDay.toString(), event.location, event.notes, event.recurrence.orEmpty(), event.excluded.orEmpty())
        val bytes = values.joinToString("") { "${it.length}:$it" }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    override suspend fun event(id: Long): CalEvent? = withContext(Dispatchers.IO) {
        check()
        val ours = ourCalendar(create = false)
        resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), EVENT_COLUMNS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            CalEvent(
                id = c.getLong(0),
                title = c.getString(1).orEmpty(),
                begin = c.getLong(2),
                end = if (c.isNull(3)) c.getLong(2) + 3_600_000L else c.getLong(3),
                allDay = c.getInt(4) == 1,
                location = c.getString(5).orEmpty(),
                notes = c.getString(6).orEmpty(),
                calendar = c.getString(7).orEmpty(),
                ours = ours != null && c.getLong(8) == ours,
            )
        }
    }

    override suspend fun add(draft: EventDraft): Long = withContext(Dispatchers.IO) {
        check()
        val calendar = ourCalendar(create = true) ?: throw ToolFailure("手机日历里建不了 Cleos 的日历，这次没加上。照实告诉对方。", "建不了日历")
        val allDay = draft.allDay == true
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar)
            put(Events.TITLE, draft.title.orEmpty())
            put(Events.DTSTART, draft.begin)
            put(Events.DTEND, draft.end)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else ZoneId.systemDefault().id)
            draft.location?.let { put(Events.EVENT_LOCATION, it) }
            draft.notes?.let { put(Events.DESCRIPTION, it) }
        }
        val id = resolver.insert(Events.CONTENT_URI, values)?.let(ContentUris::parseId)
            ?: throw ToolFailure("日历没收下这条，这次没加上。照实告诉对方。", "日历没收下")
        draft.remind?.takeIf { it >= 0 }?.let { remind(id, it) }
        id
    }

    override suspend fun update(id: Long, draft: EventDraft): Unit = withContext(Dispatchers.IO) {
        mine(id)
        val values = ContentValues().apply {
            draft.title?.let { put(Events.TITLE, it) }
            draft.begin?.let { put(Events.DTSTART, it) }
            draft.end?.let { put(Events.DTEND, it) }
            draft.allDay?.let {
                put(Events.ALL_DAY, if (it) 1 else 0)
                put(Events.EVENT_TIMEZONE, if (it) "UTC" else ZoneId.systemDefault().id)
            }
            draft.location?.let { put(Events.EVENT_LOCATION, it) }
            draft.notes?.let { put(Events.DESCRIPTION, it) }
        }
        if (values.size() > 0) resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), values, null, null)
        when (val r = draft.remind) {
            null -> Unit
            EventDraft.NO_REMINDER -> resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(id.toString()))
            else -> remind(id, r)
        }
        Unit
    }

    override suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) {
        mine(id)
        // As the calendar's own sync adapter: a local calendar has no other, and without it the
        // row would stay behind, only marked deleted.
        resolver.delete(asOwner(ContentUris.withAppendedId(Events.CONTENT_URI, id)), null, null)
        Unit
    }

    /** Throws unless [id] is in Cleos's calendar. */
    private fun mine(id: Long) {
        check()
        val ours = ourCalendar(create = false)
        val calendar = resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), arrayOf(Events.CALENDAR_ID, Events.CALENDAR_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) to c.getString(1).orEmpty() else null }
            ?: throw ToolFailure("日历里没有 #$id 这条，先用 read_calendar 看看编号。", "没有这条日程")
        if (ours == null || calendar.first != ours) {
            throw ToolFailure(
                "#$id 是对方自己的日程（在「${calendar.second.ifBlank { "日历" }}」里），你只能看，不能改也不能删。需要的话请对方自己改。",
                "那条是对方自己的",
            )
        }
    }

    private fun remind(eventId: Long, minutes: Int) {
        resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        resolver.insert(
            Reminders.CONTENT_URI,
            ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.MINUTES, minutes)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            },
        )
    }

    /** Cleos's calendar: a local one, on the phone only, made the first time something goes in. */
    private fun ourCalendar(create: Boolean): Long? = localCalendar(create, "cleos", "Cleos")

    private fun localCalendar(create: Boolean, name: String, displayName: String): Long? {
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.NAME} = ?",
            arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL, name),
            null,
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        if (!create) return null
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, name)
            put(Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(Calendars.CALENDAR_COLOR, COLOR)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }
        return resolver.insert(asOwner(Calendars.CONTENT_URI), values)?.let(ContentUris::parseId)
    }

    private fun asOwner(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    companion object {
        private const val ACCOUNT = "Cleos"
        private const val COURSE_CALENDAR = "cleos-courses"
        private val courseImport = Mutex()

        /** The app's violet. */
        private const val COLOR = 0xFF6D5BD0.toInt()

        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        fun allowed(context: Context) =
            PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

        internal fun canRead(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

        private val INSTANCE_COLUMNS = arrayOf(
            Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
            Instances.EVENT_LOCATION, Instances.DESCRIPTION, Instances.CALENDAR_DISPLAY_NAME, Instances.CALENDAR_ID,
        )
        private val EVENT_COLUMNS = arrayOf(
            Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY,
            Events.EVENT_LOCATION, Events.DESCRIPTION, Events.CALENDAR_DISPLAY_NAME, Events.CALENDAR_ID,
        )
    }
}
