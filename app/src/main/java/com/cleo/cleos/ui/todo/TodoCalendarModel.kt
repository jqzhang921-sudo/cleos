package com.cleo.cleos.ui.todo

import com.cleo.cleos.data.db.TodoEntity
import java.time.LocalDate
import java.time.YearMonth

internal object TodoCalendarModel {
    const val ALL = Long.MIN_VALUE
    const val UNDATED = Long.MIN_VALUE + 1

    /** Sunday first; complete weeks, with blank cells outside the displayed month. */
    fun weeks(month: YearMonth): List<List<LocalDate?>> {
        val leading = month.atDay(1).dayOfWeek.value % 7
        val count = ((leading + month.lengthOfMonth() + 6) / 7) * 7
        return List(count) { index ->
            val day = index - leading + 1
            if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
        }.chunked(7)
    }

    fun matches(todo: TodoEntity, filter: Long): Boolean = when (filter) {
        ALL -> true
        UNDATED -> todo.dueDay == null
        else -> todo.dueDay == filter
    }

    fun byDay(todos: List<TodoEntity>): Map<Long, List<TodoEntity>> = todos
        .filter { it.dueDay != null }
        .groupBy { it.dueDay!! }
        .mapValues { (_, rows) -> rows.sortedWith(compareBy<TodoEntity> { it.done }.thenBy { it.createdAt }.thenBy { it.id }) }

    fun addDay(filter: Long): Long? = filter.takeUnless { it == ALL || it == UNDATED }
}
