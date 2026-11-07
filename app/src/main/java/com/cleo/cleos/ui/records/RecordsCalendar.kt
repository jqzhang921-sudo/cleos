package com.cleo.cleos.ui.records

import androidx.compose.runtime.Composable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.data.db.TodoEntity
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.todo.TodoMonthCalendar
import com.cleo.cleos.ui.todo.TodoCalendarModel
import java.time.LocalDate
import java.time.YearMonth

@Composable internal fun RecordViewButton(backdrop: Backdrop) {
    val control = LocalRecordCalendar.current
    GlassIconButton(if (control.calendar == true) Icons.Rounded.ViewList else Icons.Rounded.Event,
        if (control.calendar == true) "切换到列表" else "切换到日历",
        { control.onMode(control.calendar != true) }, backdrop, size = 48.dp, enabled = control.calendar != null)
}

@Composable internal fun RecordsCalendar(tasks: List<TodoEntity> = emptyList(), diaryCounts: Map<Long, Int>? = null, onEdit: (TodoEntity) -> Unit = {}) {
    val dates = LocalRecordCalendar.current.dates
    val today = Dates.today()
    Column(Modifier.animateContentSize(tween(260, easing = FastOutSlowInEasing))) {
        // Undated belongs to the todo filter. Merely visiting Diary must neither
        // clear that filter nor hide Diary's shared selected date.
        TodoMonthCalendar(YearMonth.from(LocalDate.ofEpochDay(dates.monthEpoch)), today,
            dates.day.takeUnless { diaryCounts == null && dates.undated }, tasks,
            onMonth = { target -> dates.step(if (target.atDay(1).toEpochDay() < dates.monthEpoch) -1 else 1) },
            onDay = dates::pick, onEdit = onEdit, onToday = { dates.pick(today) },
            weekDays = if (dates.collapsed) dates.week() else null, diaryCounts = diaryCounts,
            onToggleWeeks = { dates.collapsed = !dates.collapsed })

    }
}

@Composable internal fun RecordDateScope() {
    val dates = LocalRecordCalendar.current.dates
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(dates.day?.let { Dates.full(it) } ?: "全部日期", modifier = Modifier.weight(1f), color = LocalGlassPalette.current.content)
        if (dates.day != null) TextButton(onClick = { dates.filterEpoch = TodoCalendarModel.ALL }) { Text("全部日期") }
    }
}
