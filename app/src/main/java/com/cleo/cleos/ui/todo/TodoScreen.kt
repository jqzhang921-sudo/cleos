package com.cleo.cleos.ui.todo

import com.cleo.cleos.ui.common.tabBarScroll
import com.cleo.cleos.ui.common.SlidingCapsuleTabs
import com.cleo.cleos.ai.CalEvent
import android.animation.ValueAnimator
import com.cleo.cleos.ui.records.LocalRecordCalendar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.data.db.TodoEntity
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassFloatingPanel
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.records.recordTopBarHeight
import com.cleo.cleos.ui.common.appViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

data class TodoState(val pending: List<TodoEntity> = emptyList(), val done: List<TodoEntity> = emptyList(), val loaded: Boolean = false)

class TodoViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<TodoState> = c.db.todos().observeAll().map { all ->
        TodoState(
            // Things with a date first, soonest first; the rest in the order they were added.
            pending = all.filter { !it.done }.sortedWith(compareBy<TodoEntity> { it.dueDay ?: Long.MAX_VALUE }.thenBy { it.createdAt }),
            done = all.filter { it.done }.sortedByDescending { it.doneAt ?: 0L },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoState())

    fun add(todo: TodoEntity) {
        c.appScope.launch {
            c.db.todos().insert(todo.copy(createdAt = System.currentTimeMillis()))
        }
    }

    fun toggle(todo: TodoEntity) {
        c.appScope.launch {
            c.db.todos().upsert(todo.copy(done = !todo.done, doneAt = if (todo.done) null else System.currentTimeMillis()))
        }
    }

    fun save(todo: TodoEntity) {
        c.appScope.launch { c.db.todos().upsert(todo) }
    }

    fun delete(todo: TodoEntity) {
        c.appScope.launch { c.db.todos().delete(todo) }
    }

    /** Puts a deleted item back exactly as it was, id included. */
    fun restore(todo: TodoEntity) {
        c.appScope.launch { c.db.todos().upsert(todo) }
    }
}

/** A finished row kept on screen for one more beat, so the tick is seen before it folds away. */
private data class LeavingTodo(val todo: TodoEntity, val index: Int)

private enum class TodoSection { Agenda, All, Undated }

@Composable
private fun TodoMenuItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) palette.accentContent else palette.contentSecondary)
        Text(label, color = if (enabled) palette.content else palette.contentSecondary, fontSize = 15.sp)
    }
}

@Composable
fun TodoTab(bottomInset: Dp, sectionHeader: @Composable () -> Unit = {}) {
    val c = com.cleo.cleos.ui.common.appContainer()
    val focusActive by remember { c.focus.active }.collectAsStateWithLifecycle(null)
    val vm = appViewModel { TodoViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val dates = LocalRecordCalendar.current.dates
    val palette = LocalGlassPalette.current
    val motion = remember { ValueAnimator.areAnimatorsEnabled() }
    val pager = rememberPagerState { TodoSection.entries.size }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val today = Dates.today()
    val selected = dates.day ?: today
    val zone = ZoneId.systemDefault()
    val month = YearMonth.from(selected)
    val first = TodoAgenda.week(month.atDay(1)).first()
    val last = TodoAgenda.week(month.atEndOfMonth()).last()
    val access = todoCalendarAccess(first, last.plusDays(1), zone)
    val tasks = remember(state.pending, state.done) { state.pending + state.done }
    val entries = remember(first, last, zone, tasks, access.events) {
        generateSequence(first) { day -> day.plusDays(1).takeUnless { it.isAfter(last) } }
            .associateWith { day -> TodoAgenda.items(day, zone, tasks, access.events) }
    }
    val chosenRows = entries[selected].orEmpty()
    var calendarLevel by key("agendaCalendarLevel") { rememberSaveable { mutableStateOf(AgendaCalendarLevel.Week) } }
    var detailDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var eventDetail by remember { mutableStateOf<Pair<CalEvent, LocalDate>?>(null) }
    var editing by remember { mutableStateOf<TodoEntity?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var newDue by rememberSaveable { mutableStateOf<Long?>(null) }
    var more by remember { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf<Rect?>(null) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBarHeight = recordTopBarHeight()
    val undatedCount = state.pending.count { it.dueDay == null }

    fun deleteWithUndo(todo: TodoEntity) {
        vm.delete(todo)
        scope.launch {
            val result = snackbar.showSnackbar("删掉了「${todo.title}」", actionLabel = "撤销",
                duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) vm.restore(todo)
        }
    }

    fun openAgendaItem(item: AgendaItem, day: LocalDate) {
        detailDay = null
        when (item) {
            is AgendaItem.Event -> eventDetail = item.event to day
            is AgendaItem.Task -> editing = item.todo
        }
    }

    GlassPage(overlay = { page ->
        GlassTopBar(
            barHeight = topBarHeight,
            title = "待办",
            subtitle = if (state.loaded && state.pending.isNotEmpty()) "还有 ${state.pending.size} 件" else null,
            backdrop = page,
            trailing = {
                GlassIconButton(Icons.Rounded.Timer, if (focusActive != null) "回到专注" else "专注",
                    { c.opening.value = com.cleo.cleos.Opening.Focus() }, page)
                GlassIconButton(Icons.Rounded.Add, "新建待办", {
                    newDue = selected.toEpochDay().takeIf { pager.currentPage == TodoSection.Agenda.ordinal }
                    creating = true
                }, page)
                GlassIconButton(Icons.Rounded.MoreVert, "日程选项", { more = true }, page,
                    modifier = Modifier.onGloballyPositioned { moreAnchor = it.boundsInRoot() })
            },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp)
            .padding(bottom = bottomInset))
        moreAnchor?.let { anchor ->
            GlassFloatingPanel(
                visible = more,
                backdrop = page,
                title = "日程选项",
                onDismiss = { more = false },
                modifier = Modifier.matchParentSize(),
                anchor = anchor,
                alignToEnd = true,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TodoMenuItem(Icons.Rounded.Refresh, if (access.canRead) "刷新手机日历" else "显示手机日历", more) {
                        more = false
                        access.connect()
                    }
                    TodoMenuItem(Icons.Rounded.UploadFile, if (access.busy) "正在读取课表…" else "导入课表（.ics）", more && !access.busy) {
                        more = false
                        scope.launch { pager.scrollToPage(TodoSection.Agenda.ordinal) }
                        access.pickCourseFile()
                    }
                }
            }
        }
    }) {
        Column(Modifier.fillMaxSize().padding(top = statusTop + topBarHeight + 8.dp)) {
            Box(Modifier.padding(horizontal = 16.dp)) { sectionHeader() }
            SlidingCapsuleTabs(listOf("日程", "全部", "未安排 · $undatedCount"), pager, motion)
            access.notice?.let { notice ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, Modifier.weight(1f), color = palette.contentSecondary, fontSize = 12.sp)
                    TextButton(onClick = access.clearNotice) { Text("关闭", fontSize = 12.sp) }
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                val scroll = rememberScrollState()
                val visibleScroll = if (pager.settledPage == page) Modifier.tabBarScroll(scroll) else Modifier
                Column(Modifier.fillMaxSize().then(visibleScroll).verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomInset + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (TodoSection.entries[page]) {
                        TodoSection.Agenda -> {
                            AgendaCalendar(
                                focused = selected, today = today, level = calendarLevel, entries = entries,
                                motion = motion, onLevelChange = { calendarLevel = it },
                                onStep = { direction ->
                                    val next = if (calendarLevel == AgendaCalendarLevel.Week) selected.plusWeeks(direction.toLong())
                                    else selected.plusMonths(direction.toLong())
                                    if (next.year in 1..9999) dates.pick(next)
                                },
                                onPick = dates::pick,
                                onOverflow = { day -> dates.pick(day); detailDay = day.toEpochDay() },
                            )
                            Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (selected == today) "今天的安排" else "${Dates.monthDay(selected)}的安排",
                                    Modifier.weight(1f), color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                if (chosenRows.isNotEmpty()) Text("${chosenRows.size} 项", color = palette.contentSecondary, fontSize = 12.sp)
                            }
                            chosenRows.forEach { item -> AgendaRow(item, selected, zone, vm::toggle) { openAgendaItem(item, selected) } }
                            if (state.loaded && chosenRows.isEmpty()) TodoEmpty("这天没有安排")
                            if (!access.canRead) TextButton(onClick = access.connect) { Text("显示手机日历") }
                        }
                        TodoSection.All, TodoSection.Undated -> {
                            val undated = page == TodoSection.Undated.ordinal
                            TodoTaskList(
                                state.pending.filter { !undated || it.dueDay == null },
                                state.done.filter { !undated || it.dueDay == null },
                                state.loaded, undated, motion, vm::toggle,
                                onEdit = { editing = it }, onDelete = ::deleteWithUndo,
                            )
                        }
                    }
                }
            }
        }
    }

    detailDay?.let { epoch ->
        val day = LocalDate.ofEpochDay(epoch)
        AgendaDaySheet(day, entries[day].orEmpty(), zone, onDismiss = { detailDay = null }, onToggle = vm::toggle) {
            openAgendaItem(it, day)
        }
    }
    eventDetail?.let { (event, day) ->
        AlertDialog(onDismissRequest = { eventDetail = null },
            title = { Text(event.title.ifBlank { "未命名日程" }) },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${Dates.full(day)} · ${TodoAgenda.time(event, day, zone)}")
                    if (event.location.isNotBlank()) Text(event.location)
                    if (event.notes.isNotBlank()) Text(event.notes)
                    if (event.calendar.isNotBlank()) Text(event.calendar, color = palette.contentSecondary, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { eventDetail = null }) { Text("完成") } })
    }
    if (creating) {
        TodoEditDialog(
            todo = TodoEntity(title = dates.todoDraft, dueDay = newDue, createdAt = 0L),
            onTitleChange = { dates.todoDraft = it },
            onDismiss = { creating = false },
            onSave = {
                vm.add(it)
                dates.todoDraft = ""
                creating = false
            },
        )
    }
    editing?.let { todo ->
        TodoEditDialog(
            todo = todo,
            onFocus = { draft ->
                editing = null
                c.appScope.launch {
                    c.db.todos().upsert(draft)
                    c.opening.value = com.cleo.cleos.Opening.Focus(todo.id)
                }
            },
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
            onDelete = { editing = null; deleteWithUndo(todo) },
        )
    }
}

@Composable
private fun TodoEmpty(label: String) {
    val palette = LocalGlassPalette.current
    GlassSurface(Modifier.fillMaxWidth(), style = palette.notice, shape = GlassShape.Rounded(18.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Text(label, color = palette.contentSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun TodoTaskList(
    pending: List<TodoEntity>,
    done: List<TodoEntity>,
    loaded: Boolean,
    undated: Boolean,
    motion: Boolean,
    onToggle: (TodoEntity) -> Unit,
    onEdit: (TodoEntity) -> Unit,
    onDelete: (TodoEntity) -> Unit,
) {
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    var showDone by rememberSaveable { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<List<LeavingTodo>>(emptyList()) }
    var hiding by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val displayPending = pending.toMutableList().apply {
        for (item in leaving) {
            val index = indexOfFirst { it.id == item.todo.id }
            if (index >= 0) this[index] = item.todo else add(item.index.coerceIn(0, size), item.todo)
        }
    }
    val leavingIds = leaving.mapTo(mutableSetOf()) { it.todo.id }
    val visibleDone = done.filterNot { it.id in leavingIds }
    if (loaded && pending.isEmpty() && done.isEmpty()) {
        TodoEmpty(if (undated) "没有未安排的待办" else "还没有待办")
    }
    if (pending.isNotEmpty() || done.isNotEmpty()) ProgressCard(done.size, pending.size + done.size)
    if (displayPending.isNotEmpty()) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, shape = GlassShape.Rounded(20.dp)) {
            Column {
                displayPending.forEachIndexed { index, todo ->
                    key(todo.id) {
                        AnimatedVisibility(visible = todo.id !in hiding,
                            exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(140))) {
                            Column {
                                TodoRow(todo,
                                    onToggle = {
                                        if (todo.id !in leavingIds && todo.id !in hiding) {
                                            onToggle(todo)
                                            leaving = leaving + LeavingTodo(todo.copy(done = true), index)
                                            scope.launch {
                                                delay(if (motion) 200L else 0L)
                                                hiding = hiding + todo.id
                                                delay(if (motion) 220L else 0L)
                                                leaving = leaving.filterNot { it.todo.id == todo.id }
                                                hiding = hiding - todo.id
                                            }
                                        }
                                    },
                                    onClick = { onEdit(todo) }, onLongClick = { onDelete(todo) }, enter = false)
                                if (index < displayPending.lastIndex) TodoDivider()
                            }
                        }
                    }
                }
            }
        }
    }
    if (done.isNotEmpty()) {
        Column {
            DoneHeader(done.size, showDone) { showDone = !showDone }
            AnimatedVisibility(showDone,
                enter = expandVertically(tween(240, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(140))) {
                GlassSurface(Modifier.fillMaxWidth().padding(top = 8.dp), style = palette.card,
                    shape = GlassShape.Rounded(20.dp)) {
                    Column {
                        visibleDone.forEachIndexed { index, todo ->
                            key(todo.id) {
                                TodoRow(todo, { onToggle(todo) }, { onEdit(todo) }, { onDelete(todo) }, enter = false)
                                if (index < visibleDone.lastIndex) TodoDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(done: Int, total: Int) {
    val palette = LocalGlassPalette.current
    val target = if (total == 0) 0f else done.toFloat() / total
    val shown by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "todoProgress",
    )
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        style = palette.card,
        shape = GlassShape.Rounded(20.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("完成", color = palette.contentSecondary, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text("$done", color = palette.accentContent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(" / $total", color = palette.contentSecondary, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "${(shown * 100).roundToInt()}%",
                    color = palette.content,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(10.dp))
            ProgressLine(shown)
        }
    }
}

@Composable
private fun ProgressLine(progress: Float) {
    val palette = LocalGlassPalette.current
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(palette.content.copy(alpha = if (palette.dark) 0.18f else 0.10f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(palette.accent),
        )
    }
}

/** How far either side of today the strip can travel. */
private const val DAYS_AROUND = 365
private val DayTileWidth = 50.dp

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TodoRow(
    todo: TodoEntity,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    enter: Boolean,
) {
    val palette = LocalGlassPalette.current
    // New rows rise a little as they fade in; rows already on screen start fully drawn.
    val appear = remember { Animatable(if (enter) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (appear.value < 1f) appear.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 6.dp.toPx()
            }
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(todo.done, onToggle)
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f)) {
            Text(
                todo.title,
                color = if (todo.done) palette.contentSecondary else palette.content,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
            )
            if (todo.note.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    todo.note,
                    color = palette.contentSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            todo.dueDay?.let { day ->
                val date = LocalDate.ofEpochDay(day)
                val today = Dates.today()
                val overdue = !todo.done && date.isBefore(today)
                val color = when {
                    overdue -> palette.error
                    date == today -> palette.accentContent
                    else -> palette.contentSecondary
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(Icons.Rounded.Event, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (overdue) "逾期 · ${Dates.due(date)}" else Dates.due(date),
                        color = color,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TodoDivider() {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = 16.dp)
            .height(0.6.dp)
            .background(palette.content.copy(alpha = if (palette.dark) 0.11f else 0.075f)),
    )
}

@Composable
private fun CheckCircle(done: Boolean, onToggle: () -> Unit) {
    val palette = LocalGlassPalette.current
    val fill by animateColorAsState(
        targetValue = if (done) palette.accent else Color.Transparent,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "todoCheckFill",
    )
    val border by animateColorAsState(
        targetValue = if (done) palette.accent else palette.content.copy(alpha = if (palette.dark) 0.40f else 0.30f),
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "todoCheckBorder",
    )
    // The 48dp box is the touch target; the drawn circle stays small.
    Box(
        Modifier
            .size(48.dp)
            .toggleable(
                value = done,
                interactionSource = null,
                indication = null,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .background(fill, CircleShape)
                .border(1.5.dp, border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = done,
                enter = scaleIn(initialScale = 0.5f, animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                    fadeIn(tween(120)),
                exit = scaleOut(targetScale = 0.7f, animationSpec = tween(120)) + fadeOut(tween(100)),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun DoneHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val palette = LocalGlassPalette.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "doneChevron",
    )
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = null, indication = null, onClick = onToggle),
        style = palette.topBar,
        shape = GlassShape.Rounded(16.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已完成", color = palette.contentSecondary, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text("$count", color = palette.accentContent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起已完成的待办" else "展开已完成的待办",
                tint = palette.contentSecondary,
                modifier = Modifier.size(20.dp).rotate(rotation),
            )
        }
    }
}
