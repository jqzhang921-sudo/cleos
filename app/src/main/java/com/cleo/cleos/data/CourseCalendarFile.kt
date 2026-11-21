package com.cleo.cleos.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

internal data class CourseEvent(
    val uid: String,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val zone: String,
    val location: String,
    val notes: String,
    val recurrence: String?,
    val excluded: String?,
)

/** Local iCalendar input. Only VEVENTs produce records; no URLs, alarms or invitations are used. */
internal object CourseCalendarFile {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_EVENTS = 500
    private val localClock = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val utcClock = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val ruleCharacters = Regex("[A-Z0-9=;,+-]+")
    private val supportedRuleParts = setOf("FREQ", "COUNT", "UNTIL", "INTERVAL", "BYDAY", "BYMONTH", "BYMONTHDAY", "BYSETPOS", "WKST")
    private val supportedFrequencies = setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")

    fun read(input: InputStream, zone: ZoneId): List<CourseEvent> {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= MAX_BYTES) { "课表文件超过 2 MB，请分开导出" }
            out.write(buffer, 0, count)
        }
        return parse(out.toString(Charsets.UTF_8.name()), zone)
    }

    fun parse(source: String, zone: ZoneId): List<CourseEvent> {
        // Folded lines lose exactly one leading space or tab when joined.
        val lines = source.removePrefix("\uFEFF").replace(Regex("\\r?\\n[ \\t]"), "")
            .lineSequence().filter { it.isNotBlank() }
        val stack = mutableListOf<String>()
        var current = mutableListOf<Property>()
        var calendars = 0
        val events = mutableListOf<CourseEvent>()
        for (line in lines) {
            val separator = separator(line)
            require(separator > 0) { "课表文件有无法读取的行" }
            val head = line.substring(0, separator)
            val type = head.substringBefore(';').uppercase(Locale.ROOT)
            val value = line.substring(separator + 1)
            when (type) {
                "BEGIN" -> {
                    val component = value.trim().uppercase(Locale.ROOT)
                    if (stack.isEmpty()) {
                        require(component == "VCALENDAR") { "请选择 .ics 日历文件" }
                        calendars++
                    }
                    require(stack.size < 12) { "课表文件的结构不支持" }
                    if (component == "VEVENT") {
                        require(stack == listOf("VCALENDAR")) { "课表日程结构不完整" }
                        current = mutableListOf()
                    }
                    stack += component
                }
                "END" -> {
                    val component = value.trim().uppercase(Locale.ROOT)
                    require(stack.lastOrNull() == component) { "课表文件的结构不完整" }
                    if (component == "VEVENT") {
                        require(events.size < MAX_EVENTS) { "一次最多导入 $MAX_EVENTS 条日程，请分开导出" }
                        val event = try { event(current, zone) }
                        catch (e: ImportException) { throw e }
                        catch (_: RuntimeException) { throw ImportException("第 ${events.size + 1} 条日程的日期或时间无法读取") }
                        if (event != null) events += event
                    }
                    stack.removeAt(stack.lastIndex)
                }
                else -> if (stack.lastOrNull() == "VEVENT") {
                    val timezone = Regex("""(?:^|;)TZID=(?:"([^"]*)"|([^;]*))""", RegexOption.IGNORE_CASE)
                        .find(head)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                    current += Property(type, value, timezone)
                }
            }
        }
        require(calendars > 0 && stack.isEmpty()) { "请选择完整的 .ics 日历文件" }
        require(events.isNotEmpty()) { "文件里没有可导入的日程" }
        return events
    }

    private fun event(fields: List<Property>, zone: ZoneId): CourseEvent? {
        fun get(key: String) = fields.firstOrNull { it.name == key }
        if (fields.any { it.name in setOf("RECURRENCE-ID", "RDATE", "EXRULE") }) {
            throw ImportException("这个课表包含单次调课或额外重复日期，请另导为逐节日程后再试")
        }
        if (get("STATUS")?.value.equals("CANCELLED", ignoreCase = true)) return null
        val start = date(requireNotNull(get("DTSTART")), zone)
        val allDay = start.allDay
        val recurrence = get("RRULE")?.value?.trim()?.uppercase(Locale.ROOT)?.also(::validateRule)
        val end = get("DTEND")?.let { date(it, start.zone).also { end -> require(end.allDay == allDay) }.stamp }
            ?: get("DURATION")?.let {
                require(!allDay || Regex("P[0-9]+[DW]").matches(it.value))
                Math.addExact(start.stamp, Duration.parse(it.value).toMillis())
            }
            ?: if (allDay) start.stamp + 86_400_000 else start.stamp
        require(end >= start.stamp && (!allDay || end > start.stamp))
        val excluded = fields.filter { it.name == "EXDATE" }.flatMap { field ->
            field.value.split(',').map {
                val exception = date(field.copy(value = it), start.zone)
                require(exception.allDay == allDay)
                utcClock.format(Instant.ofEpochMilli(exception.stamp))
            }
        }.distinct().joinToString(",").ifBlank { null }
        return CourseEvent(
            get("UID")?.value.orEmpty().take(512),
            unescape(get("SUMMARY")?.value.orEmpty()).take(500).ifBlank { "未命名日程" },
            start.stamp, end, allDay, start.zone.id,
            unescape(get("LOCATION")?.value.orEmpty()).take(500),
            unescape(get("DESCRIPTION")?.value.orEmpty()).take(4_000),
            recurrence, excluded,
        )
    }

    private fun validateRule(rule: String) {
        if (rule.length > 500 || !ruleCharacters.matches(rule)) throw ImportException("课表包含无法读取的重复规则")
        val pairs = rule.split(';').map { it.split('=', limit = 2) }
        if (pairs.any { it.size != 2 || it[0] !in supportedRuleParts || it[1].isEmpty() } ||
            pairs.map { it[0] }.distinct().size != pairs.size ||
            pairs.firstOrNull { it[0] == "FREQ" }?.get(1) !in supportedFrequencies) {
            throw ImportException("课表包含不支持的重复规则，请另导为逐节日程后再试")
        }
        val count = pairs.firstOrNull { it[0] == "COUNT" }
        if (count != null && (count[1].toIntOrNull() ?: 0) !in 1..5000)
            throw ImportException("课表的重复次数超出支持范围")
    }

    private data class Property(val name: String, val value: String, val timezone: String?)
    private data class DateValue(val stamp: Long, val allDay: Boolean, val zone: ZoneId)

    private fun date(property: Property, defaultZone: ZoneId): DateValue {
        val value = property.value.trim()
        val allDay = value.length == 8
        val zone = when {
            allDay || value.endsWith('Z') -> ZoneId.of("UTC")
            property.timezone != null -> ZoneId.of(property.timezone)
            else -> defaultZone
        }
        val instant = if (allDay) LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone).toInstant()
        else LocalDateTime.parse(value.removeSuffix("Z"), localClock).atZone(zone).toInstant()
        return DateValue(instant.toEpochMilli(), allDay, zone)
    }

    private fun separator(line: String): Int {
        var quoted = false
        for ((index, char) in line.withIndex()) {
            if (char == '"') quoted = !quoted
            if (char == ':' && !quoted) return index
        }
        return -1
    }

    private fun unescape(value: String): String = Regex("""\\([nN,;\\])""").replace(value) {
        when (val char = it.groupValues[1]) {
            "n", "N" -> "\n"
            else -> char
        }
    }.trim()
}
