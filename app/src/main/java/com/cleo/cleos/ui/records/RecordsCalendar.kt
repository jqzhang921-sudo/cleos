package com.cleo.cleos.ui.records

import androidx.compose.runtime.Composable
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
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.systemMotionEnabled
import com.cleo.cleos.ui.todo.AgendaCalendar
import com.cleo.cleos.ui.todo.AgendaCalendarLevel
import com.cleo.cleos.ui.todo.TodoCalendarModel
import java.time.LocalDate

@Composable internal fun RecordViewButton(backdrop: Backdrop) {
    val control = LocalRecordCalendar.current
    GlassIconButton(if (control.calendar == true) Icons.Rounded.ViewList else Icons.Rounded.Event,
        if (control.calendar == true) "切换到列表" else "切换到日历",
        { control.onMode(control.calendar != true) }, backdrop, size = 48.dp, enabled = control.calendar != null)
}

@Composable internal fun RecordsCalendar(diaryCounts: Map<Long, Int>) {
    val dates = LocalRecordCalendar.current.dates
    AgendaCalendar(
        focused = LocalDate.ofEpochDay(dates.focusEpoch),
        today = Dates.today(),
        level = if (dates.collapsed) AgendaCalendarLevel.Week else AgendaCalendarLevel.Month,
        selected = dates.day,
        lastLevel = AgendaCalendarLevel.Month,
        diaryCounts = diaryCounts,
        motion = systemMotionEnabled(),
        onLevelChange = { dates.collapsed = it == AgendaCalendarLevel.Week },
        onStep = dates::step,
        onPick = dates::pick,
    )
}

@Composable internal fun RecordDateScope() {
    val dates = LocalRecordCalendar.current.dates
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(dates.day?.let { Dates.full(it) } ?: "全部日期", modifier = Modifier.weight(1f), color = LocalGlassPalette.current.content)
        if (dates.day != null) TextButton(onClick = { dates.filterEpoch = TodoCalendarModel.ALL }) { Text("全部日期") }
    }
}
