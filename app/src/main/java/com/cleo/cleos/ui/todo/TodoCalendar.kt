package com.cleo.cleos.ui.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.db.TodoEntity
import com.cleo.cleos.glass.LocalGlassPalette
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun TodoScopeChips(filter: Long, undatedCount: Int, onAll: () -> Unit, onUndated: () -> Unit) {
    val palette = LocalGlassPalette.current
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple(TodoCalendarModel.ALL, "全部", onAll),
            Triple(TodoCalendarModel.UNDATED, "未安排 · $undatedCount", onUndated)).forEach { (value, label, action) ->
            val selected = filter == value
            Box(Modifier.clip(RoundedCornerShape(13.dp))
                .background(if (selected) palette.accent.copy(alpha = .16f) else palette.content.copy(alpha = .06f))
                .selectable(selected, role = Role.Tab, onClick = action)
                .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 13.sp, color = if (selected) palette.accentContent else palette.contentSecondary)
            }
        }
    }
}

@Composable
internal fun TodoMonthCalendar(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate?,
    tasks: List<TodoEntity>,
    onMonth: (YearMonth) -> Unit,
    onDay: (LocalDate) -> Unit,
    onEdit: (TodoEntity) -> Unit,
    onToday: () -> Unit,
    weekDays: List<LocalDate>? = null,
    diaryCounts: Map<Long, Int>? = null,
    onToggleWeeks: (() -> Unit)? = null,
) {
    val palette = LocalGlassPalette.current
    val weeks = remember(month, weekDays) { weekDays?.let { listOf(it.map { day -> day as LocalDate? }) } ?: TodoCalendarModel.weeks(month) }
    val byDay = remember(tasks) { TodoCalendarModel.byDay(tasks) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onMonth(month.minusMonths(1)) }, enabled = month.year > 1 || month.monthValue > 1,
                contentPadding = PaddingValues(2.dp), modifier = Modifier.width(48.dp).semantics { contentDescription = if (weekDays == null) "上个月" else "上一周" }) {
                Text("‹", color = palette.content, fontSize = 25.sp)
            }
            Text("${month.year}年${month.monthValue}月${if (onToggleWeeks == null) "" else if (weekDays == null) " ⌃" else " ⌄"}",
                Modifier.weight(1f).heightIn(min = 48.dp).then(
                    if (onToggleWeeks == null) Modifier else Modifier.clickable(onClick = onToggleWeeks)
                        .semantics { contentDescription = if (weekDays == null) "收为周历" else "展开月历" }
                ), color = palette.content,
                fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onToday, contentPadding = PaddingValues(2.dp), modifier = Modifier.width(48.dp)) { Text("今天", color = palette.accentContent, fontSize = 13.sp) }
            TextButton(onClick = { onMonth(month.plusMonths(1)) }, enabled = month.year < 9999,
                contentPadding = PaddingValues(2.dp), modifier = Modifier.width(48.dp).semantics { contentDescription = if (weekDays == null) "下个月" else "下一周" }) {
                Text("›", color = palette.content, fontSize = 25.sp)
            }
        }
        if (weekDays != null) Text("${weekDays.first().monthValue}月${weekDays.first().dayOfMonth}日 — ${weekDays.last().monthValue}月${weekDays.last().dayOfMonth}日", color = palette.contentSecondary, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth()) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEachIndexed { i, title ->
                Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(title, fontSize = 12.sp, color = if (i == 0 || i == 6) palette.contentSecondary else palette.content)
                }
            }
        }
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { day ->
                    if (day == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val rows = byDay[day.toEpochDay()].orEmpty()
                        val active = day == selected
                        Column(
                            Modifier.weight(1f).fillMaxHeight().heightIn(min = if (diaryCounts != null) 64.dp else 96.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) palette.accent.copy(alpha = .12f) else Color.Transparent)
                                .selectable(active, role = Role.Button, onClick = { onDay(day) })
                                .semantics { contentDescription = "${day.year}年${day.monthValue}月${day.dayOfMonth}日，${if (diaryCounts != null) "${diaryCounts[day.toEpochDay()] ?: 0}篇日记" else "${rows.count { !it.done }}件待办，${rows.count { it.done }}件已完成"}" }
                                .padding(horizontal = 2.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(Modifier.align(Alignment.CenterHorizontally)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (day == today) palette.accent else Color.Transparent)
                                .padding(horizontal = 4.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                                Text("${day.dayOfMonth}", color = if (day == today) Color.White else palette.content,
                                    fontSize = 16.sp, fontWeight = if (active || day == today) FontWeight.Bold else FontWeight.Medium)
                            }
                            if (diaryCounts != null && (diaryCounts[day.toEpochDay()] ?: 0) > 0) {
                                Text("• ${diaryCounts[day.toEpochDay()]}", color = palette.accentContent, fontSize = 11.sp,
                                    modifier = Modifier.align(Alignment.CenterHorizontally))
                            }
                            rows.take(2).forEach { todo ->
                                Text(todo.title,
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
                                        .background(palette.content.copy(alpha = if (todo.done) .04f else .08f))
                                        .clickable(onClickLabel = "编辑待办", onClick = { onEdit(todo) })
                                        .padding(horizontal = 3.dp, vertical = 4.dp),
                                    color = if (todo.done) palette.contentSecondary else palette.content,
                                    fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    textDecoration = if (todo.done) TextDecoration.LineThrough else null)
                            }
                            if (rows.size > 2) {
                                Text("还有${rows.size - 2}项", color = palette.contentSecondary,
                                    fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(horizontal = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
