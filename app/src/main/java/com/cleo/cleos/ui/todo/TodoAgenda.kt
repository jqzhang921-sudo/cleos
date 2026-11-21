package com.cleo.cleos.ui.todo

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.ai.CalEvent
import com.cleo.cleos.data.db.TodoEntity
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.circularPressFeedback
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

internal sealed interface AgendaItem {
    val title: String
    data class Event(val event: CalEvent) : AgendaItem {
        override val title get() = event.title.ifBlank { "未命名日程" }
    }
    data class Task(val todo: TodoEntity) : AgendaItem {
        override val title get() = todo.title
    }
}

internal object TodoAgenda {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    // Android stores all-day dates at UTC midnight. Timed events belong to local days.
    fun eventOn(event: CalEvent, day: LocalDate, zone: ZoneId): Boolean {
        val eventZone = if (event.allDay) ZoneOffset.UTC else zone
        val first = day.atStartOfDay(eventZone).toInstant().toEpochMilli()
        val next = day.plusDays(1).atStartOfDay(eventZone).toInstant().toEpochMilli()
        return event.begin < next && maxOf(event.end, event.begin + 1) > first
    }

    fun items(day: LocalDate, zone: ZoneId, tasks: List<TodoEntity>, events: List<CalEvent>): List<AgendaItem> =
        events.filter { eventOn(it, day, zone) }.sortedWith(compareBy<CalEvent> { !it.allDay }.thenBy { it.begin })
            .map { AgendaItem.Event(it) } +
            tasks.filter { it.dueDay == day.toEpochDay() }
                .sortedWith(compareBy<TodoEntity> { it.done }.thenBy { it.createdAt }).map { AgendaItem.Task(it) }

    fun time(event: CalEvent, day: LocalDate, zone: ZoneId): String {
        if (event.allDay) return "全天"
        val start = Instant.ofEpochMilli(event.begin).atZone(zone)
        val end = Instant.ofEpochMilli(event.end).atZone(zone)
        fun part(instant: java.time.ZonedDateTime): String =
            if (instant.toLocalDate() == day) instant.format(clock)
            else "${Dates.monthDay(instant.toLocalDate())} ${instant.format(clock)}"
        return if (event.end > event.begin) "${part(start)}–${part(end)}" else part(start)
    }

    fun week(day: LocalDate): List<LocalDate> {
        val sunday = day.minusDays((day.dayOfWeek.value % 7).toLong())
        return List(7) { sunday.plusDays(it.toLong()) }
    }
}

internal enum class AgendaCalendarLevel(val label: String) {
    Week("周视图"), Month("点阵月历"), Schedule("日程月历"),
}

internal data class AgendaCalendarTravel(
    val month: Float,
    val schedule: Float,
    val stretch: Float,
    val snap: Float,
    val lastLevel: AgendaCalendarLevel = AgendaCalendarLevel.Schedule,
) {
    val end: Float get() = if (lastLevel == AgendaCalendarLevel.Month) month else schedule + stretch * 4f

    fun distance(progress: Float): Float = when {
        progress <= 1f -> progress * month
        progress <= 2f -> month + (progress - 1f) * (schedule - month)
        else -> schedule + (progress - 2f) * stretch
    }

    fun progress(distance: Float): Float = when {
        lastLevel == AgendaCalendarLevel.Month -> (distance / month).coerceIn(0f, 1f)
        distance <= month -> (distance / month).coerceAtLeast(0f)
        distance <= schedule -> 1f + (distance - month) / (schedule - month)
        else -> 2f + ((distance - schedule) / stretch).coerceAtMost(4f)
    }

    fun release(from: AgendaCalendarLevel, start: Float, end: Float): AgendaCalendarLevel {
        val direction = if (end >= start) 1 else -1
        if (abs(end - start) < snap * .45f) return from
        var level = from
        while (true) {
            val next = AgendaCalendarLevel.entries.getOrNull(level.ordinal + direction) ?: return level
            if (next.ordinal > lastLevel.ordinal) return level
            val anchor = distance(level.ordinal.toFloat())
            val gap = abs(distance(next.ordinal.toFloat()) - anchor)
            if ((end - anchor) * direction < minOf(snap, gap * .28f)) return level
            level = next
        }
    }
}

@Composable
internal fun AgendaCalendar(
    focused: LocalDate,
    today: LocalDate,
    level: AgendaCalendarLevel,
    entries: Map<LocalDate, List<AgendaItem>> = emptyMap(),
    motion: Boolean,
    onLevelChange: (AgendaCalendarLevel) -> Unit,
    onStep: (Int) -> Unit,
    onPick: (LocalDate) -> Unit,
    onOverflow: (LocalDate) -> Unit = {},
    selected: LocalDate? = focused,
    lastLevel: AgendaCalendarLevel = AgendaCalendarLevel.Schedule,
    diaryCounts: Map<Long, Int>? = null,
) {
    val palette = LocalGlassPalette.current
    val currentLevel by rememberUpdatedState(level)
    val changeLevel by rememberUpdatedState(onLevelChange)
    val month = YearMonth.from(focused)
    val weeks = remember(month) { TodoCalendarModel.weeks(month) }
    val focusedWeek = remember(focused) { TodoAgenda.week(focused) }
    val density = LocalDensity.current
    val scale = density.fontScale.coerceAtLeast(1f)
    val compactHeight = 48.dp * scale
    val titleHeight = 12.dp * scale
    val stretchHeight = 26.dp * scale
    val previewHeights = remember(weeks, entries, scale) {
        weeks.map { week ->
            val count = week.maxOf { entries[it].orEmpty().size }
            when {
                count > PREVIEW_COUNT -> 32.dp * scale
                count > 0 -> 20.dp * scale
                else -> 8.dp * scale
            }
        }
    }
    val travel = with(density) {
        val monthDistance = compactHeight.toPx() * (weeks.size - 1)
        AgendaCalendarTravel(monthDistance, monthDistance + previewHeights.sumOf { it.toPx().toDouble() }.toFloat(),
            stretchHeight.toPx() * weeks.size, 36.dp.toPx(), lastLevel)
    }
    val currentTravel by rememberUpdatedState(travel)
    var position by remember { mutableFloatStateOf(level.ordinal.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(level, dragging, motion) {
        if (dragging) return@LaunchedEffect
        val destination = level.ordinal.toFloat()
        if (!motion) position = destination
        else animate(position, destination, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
            if (!dragging) position = value
        }
    }
    val dragCalendar = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var initialDelta = 0f
            val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, amount ->
                val onGrip = down.position.y < 52.dp.toPx() || down.position.y > size.height - 27.dp.toPx()
                // Leave upward swipes on the date grid to the agenda list so tall months can scroll.
                val canExpand = currentTravel.lastLevel == AgendaCalendarLevel.Schedule ||
                    position < currentTravel.lastLevel.ordinal.toFloat()
                if ((amount > 0f && canExpand) || (amount < 0f && onGrip && position > 0f)) {
                    initialDelta = amount
                    change.consume()
                }
            } ?: return@awaitEachGesture
            val gestureTravel = currentTravel
            dragging = true
            val start = gestureTravel.distance(position)
            var distance = start
            fun move(amount: Float) {
                distance = (distance + amount).coerceIn(0f, gestureTravel.end)
                position = gestureTravel.progress(distance)
            }
            try {
                move(initialDelta)
                val completed = verticalDrag(slop.id) { change ->
                    move(change.positionChange().y)
                    change.consume()
                }
                if (completed) changeLevel(gestureTravel.release(currentLevel, start, distance))
            } finally {
                dragging = false
            }
        }
    }
    val monthVisible = position.coerceIn(0f, 1f)
    val scheduleVisible = (position - 1f).coerceIn(0f, 1f)
    val overshoot = (position - 2f).coerceAtLeast(0f)
    val stretchVisible = overshoot / (1f + overshoot)
    val next = if (level == lastLevel) AgendaCalendarLevel.Week else AgendaCalendarLevel.entries[level.ordinal + 1]
    val monthly = level != AgendaCalendarLevel.Week
    val actionLabel = when (next) {
        AgendaCalendarLevel.Week -> "收起到周视图"
        AgendaCalendarLevel.Month -> "展开点阵月历"
        AgendaCalendarLevel.Schedule -> "展开日程月历"
    }

    GlassSurface(Modifier.fillMaxWidth().then(dragCalendar), style = palette.card, shape = GlassShape.Rounded(24.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = { onLevelChange(next) })
                    .semantics {
                        contentDescription = actionLabel
                        stateDescription = level.label
                    }
                    .padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${month.year}年${month.monthValue}月", Modifier.weight(1f, fill = false),
                        color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(if (next == AgendaCalendarLevel.Week) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null, tint = palette.contentSecondary,
                        modifier = Modifier.padding(start = 4.dp).size(17.dp))
                }
                IconButton(onClick = { onStep(-1) }, enabled = focused.year > 1 || focused.monthValue > 1) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, if (monthly) "上个月" else "上一周", tint = palette.content)
                }
                TextButton(onClick = { onPick(today) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("今天", color = palette.accentContent, fontSize = 12.sp)
                }
                IconButton(onClick = { onStep(1) }, enabled = focused.year < 9999 || focused.monthValue < 12) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, if (monthly) "下个月" else "下一周", tint = palette.content)
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach { label ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(label, color = palette.contentSecondary, fontSize = 11.sp)
                    }
                }
            }
            for ((index, week) in weeks.withIndex()) {
                val isFocusedWeek = focused in week
                val rowReveal = if (isFocusedWeek) 1f else monthVisible
                if (rowReveal <= 0f) continue
                val rowHeight = compactHeight + previewHeights[index] * scheduleVisible + stretchHeight * stretchVisible
                Box(Modifier.fillMaxWidth().height(rowHeight * rowReveal).clipToBounds()
                    .graphicsLayer { alpha = rowReveal }) {
                    Row(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(rowHeight),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        week.forEachIndexed { column, day ->
                            val date = day ?: focusedWeek[column].takeIf { isFocusedWeek && monthVisible < 1f }
                            if (date == null) Spacer(Modifier.weight(1f))
                            else AgendaDateCell(
                                day = date, today = today, selected = date == selected, items = entries[date].orEmpty(),
                                diaryCount = diaryCounts?.let { it[date.toEpochDay()] ?: 0 },
                                compactHeight = compactHeight, titleHeight = titleHeight, scheduleVisible = scheduleVisible,
                                stretchVisible = stretchVisible, onPick = { onPick(date) }, onOverflow = { onOverflow(date) },
                                modifier = Modifier.weight(1f).fillMaxHeight()
                                    .graphicsLayer { alpha = if (day == null) 1f - monthVisible else 1f },
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 32.dp, height = 3.dp)
                    .background(palette.content.copy(alpha = .24f), CircleShape))
            }
        }
    }
}

@Composable
private fun AgendaDateCell(
    day: LocalDate,
    today: LocalDate,
    selected: Boolean,
    items: List<AgendaItem>,
    diaryCount: Int?,
    compactHeight: Dp,
    titleHeight: Dp,
    scheduleVisible: Float,
    stretchVisible: Float,
    onPick: () -> Unit,
    onOverflow: () -> Unit,
    modifier: Modifier,
) {
    val palette = LocalGlassPalette.current
    val interaction = remember(day) { MutableInteractionSource() }
    val label = if (diaryCount == null) Modifier else Modifier.semantics {
        contentDescription = "${Dates.full(day)}，${diaryCount}篇日记"
    }
    Column(
        modifier.clipToBounds().selectable(selected, interactionSource = interaction, indication = null,
            role = Role.Button, onClick = onPick).then(label),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = 2.dp).size(compactHeight * .56f).circularPressFeedback(interaction).clip(CircleShape)
            .background(when {
                selected -> palette.accent
                day == today -> palette.accent.copy(alpha = .14f)
                else -> Color.Transparent
            }), contentAlignment = Alignment.Center) {
            Text("${day.dayOfMonth}", color = if (selected) Color.White else if (day == today) palette.accentContent else palette.content,
                fontSize = 14.sp, fontWeight = if (selected || day == today) FontWeight.SemiBold else FontWeight.Normal)
        }
        if (items.isNotEmpty() || (diaryCount ?: 0) > 0) {
            if (scheduleVisible < .55f) {
                Box(Modifier.padding(top = 4.dp).size(4.dp).graphicsLayer { alpha = 1f - scheduleVisible / .55f }
                    .background(palette.accentContent, CircleShape))
            } else {
                val previewAlpha = ((scheduleVisible - .55f) / .35f).coerceIn(0f, 1f)
                val extraLine = (stretchVisible * 1.6f).coerceAtMost(1f)
                val lines = if (extraLine > .32f) 2 else 1
                Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                    .graphicsLayer { alpha = previewAlpha }) {
                    items.take(PREVIEW_COUNT).forEach { item ->
                        val fill = if (item is AgendaItem.Event) palette.accent.copy(alpha = .14f) else palette.content.copy(alpha = .07f)
                        Text(item.title, Modifier.fillMaxWidth().padding(top = 2.dp).clip(RoundedCornerShape(5.dp))
                            .background(fill).padding(horizontal = 2.dp)
                            .heightIn(max = titleHeight * (1f + extraLine))
                            .wrapContentHeight(Alignment.Top, unbounded = true),
                            color = palette.content, fontSize = 10.sp, lineHeight = 12.sp, maxLines = lines,
                            overflow = TextOverflow.Ellipsis,
                            textDecoration = if ((item as? AgendaItem.Task)?.todo?.done == true) TextDecoration.LineThrough else null)
                    }
                    if (items.size > PREVIEW_COUNT) Text("+${items.size - PREVIEW_COUNT}",
                        Modifier.fillMaxWidth().clickable(role = Role.Button, enabled = scheduleVisible >= .9f,
                            onClickLabel = "查看${Dates.monthDay(day)}的全部日程", onClick = onOverflow)
                            .padding(horizontal = 3.dp, vertical = 3.dp),
                        color = palette.accentContent, fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private const val PREVIEW_COUNT = 2

@Composable
internal fun AgendaRow(
    item: AgendaItem, day: LocalDate, zone: ZoneId, onToggle: (TodoEntity) -> Unit, onClick: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val event = (item as? AgendaItem.Event)?.event
    val task = (item as? AgendaItem.Task)?.todo
    GlassSurface(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick), style = palette.card,
        shape = GlassShape.Rounded(18.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (task != null) {
                IconButton(onClick = { onToggle(task) }) {
                    Icon(if (task.done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        if (task.done) "重开${task.title}" else "完成${task.title}",
                        tint = palette.accentContent, modifier = Modifier.size(20.dp))
                }
            } else {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Event, null, tint = palette.accentContent, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    textDecoration = if (task?.done == true) TextDecoration.LineThrough else null)
                val detail = if (event != null) listOf(TodoAgenda.time(event, day, zone), event.location)
                    .filter { it.isNotBlank() }.joinToString(" · ") else if (task?.done == true) "待办 · 已完成" else "待办"
                Text(detail, color = palette.contentSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgendaDaySheet(
    day: LocalDate, rows: List<AgendaItem>, zone: ZoneId, onDismiss: () -> Unit,
    onToggle: (TodoEntity) -> Unit, onOpen: (AgendaItem) -> Unit,
) {
    val palette = LocalGlassPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(Dates.full(day), Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
            color = palette.content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows) { item -> AgendaRow(item, day, zone, onToggle) { onOpen(item) } }
        }
    }
}
