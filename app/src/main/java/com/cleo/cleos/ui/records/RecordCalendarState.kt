package com.cleo.cleos.ui.records

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import com.cleo.cleos.ui.todo.TodoCalendarModel
import java.time.LocalDate
import java.time.YearMonth

/** One date shared by both sections; undated is a todo-only scope, never a diary date. */
internal class RecordCalendarState(today: LocalDate) {
    var dayEpoch by mutableLongStateOf(TodoCalendarModel.ALL)
    var monthEpoch by mutableLongStateOf(today.withDayOfMonth(1).toEpochDay())
    var focusEpoch by mutableLongStateOf(today.toEpochDay())
    var collapsed by mutableStateOf(false)
    var undated by mutableStateOf(false)
    // Keep the unsent composer with the explicit records saver, rather than only
    // inside a section's transient content tree.
    var todoDraft by mutableStateOf("")
    val day: LocalDate? get() = dayEpoch.takeUnless { it == TodoCalendarModel.ALL }?.let(LocalDate::ofEpochDay)
    var filterEpoch: Long
        get() = if (undated) TodoCalendarModel.UNDATED else dayEpoch
        set(value) {
            undated = value == TodoCalendarModel.UNDATED
            if (!undated) {
                dayEpoch = value
                day?.let { monthEpoch = it.withDayOfMonth(1).toEpochDay(); focusEpoch = value }
            }
        }
    fun pick(date: LocalDate) {
        dayEpoch = date.toEpochDay(); focusEpoch = dayEpoch
        monthEpoch = date.withDayOfMonth(1).toEpochDay(); undated = false
    }
    fun step(direction: Int) {
        if (collapsed) pick((day ?: LocalDate.ofEpochDay(focusEpoch)).plusWeeks(direction.toLong()))
        else {
            val month = YearMonth.from(LocalDate.ofEpochDay(monthEpoch)).plusMonths(direction.toLong())
            monthEpoch = month.atDay(1).toEpochDay()
            val selected = day
            if (selected != null) pick(month.atDay(selected.dayOfMonth.coerceAtMost(month.lengthOfMonth())))
            else focusEpoch = monthEpoch
        }
    }
    fun week(): List<LocalDate> {
        val anchor = day ?: LocalDate.ofEpochDay(focusEpoch)
        val start = anchor.minusDays((anchor.dayOfWeek.value % 7).toLong())
        return List(7) { start.plusDays(it.toLong()) }
    }
    companion object {
        val Saver = listSaver<RecordCalendarState, Any>(
            save = { listOf(it.dayEpoch, it.monthEpoch, it.focusEpoch, it.collapsed, it.undated, it.todoDraft) },
            restore = { values -> RecordCalendarState(LocalDate.ofEpochDay(values[2] as Long)).apply {
                dayEpoch = values[0] as Long; monthEpoch = values[1] as Long
                focusEpoch = values[2] as Long; collapsed = values[3] as Boolean; undated = values[4] as Boolean
                todoDraft = (values.getOrNull(5) as? String).orEmpty()
            } },
        )
    }
}
internal data class RecordCalendarController(val dates: RecordCalendarState, val calendar: Boolean?, val onMode: (Boolean) -> Unit)
internal val LocalRecordCalendar = staticCompositionLocalOf<RecordCalendarController> { error("Records calendar is not provided") }
