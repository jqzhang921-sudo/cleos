package com.cleo.cleos.data

import java.time.ZonedDateTime

/** Advance from actual execution time, never from a backlog of missed ticks. */
internal object WorldScheduleRules {
    const val FIRST_MINUTES = 15L
    const val INTERVAL_HOURS = 4L
    fun first(now: ZonedDateTime): Long = now.plusMinutes(FIRST_MINUTES).toInstant().toEpochMilli()
    fun next(now: ZonedDateTime, exhausted: Boolean): Long {
        val regular = now.plusHours(INTERVAL_HOURS).toInstant().toEpochMilli()
        val tomorrow = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusMinutes(FIRST_MINUTES).toInstant().toEpochMilli()
        return if (exhausted) maxOf(regular, tomorrow) else regular
    }
}
