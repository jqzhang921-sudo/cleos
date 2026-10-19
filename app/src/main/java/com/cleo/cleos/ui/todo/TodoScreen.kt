package com.cleo.cleos.ui.todo

import android.animation.ValueAnimator
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.data.db.TodoEntity
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.liquidGlass
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appViewModel
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
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

    fun add(title: String, dueDay: Long? = null) {
        val t = title.trim()
        if (t.isEmpty()) return
        c.appScope.launch {
            c.db.todos().insert(TodoEntity(title = t, dueDay = dueDay, createdAt = System.currentTimeMillis()))
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

/** Sentinel for the saved filter: no day chosen, the whole list. */
private const val NO_DAY = Long.MIN_VALUE

@Composable
fun TodoTab(bottomInset: Dp) {
    val vm = appViewModel { TodoViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scroll = rememberScrollState()
    var input by rememberSaveable { mutableStateOf("") }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TodoEntity?>(null) }
    var barHeight by remember { mutableIntStateOf(0) }
    // A checked row stays put while the tick is visible, then collapses out of the list.
    var leaving by remember { mutableStateOf<List<LeavingTodo>>(emptyList()) }
    var hiding by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // Compose already honours the system animation scale; the short hold below is ours to skip.
    val motion = remember { ValueAnimator.areAnimatorsEnabled() }

    // The date strip: the centred day and, when one is picked, the day the list is filtered to.
    // "全部" is the default and keeps the page exactly as before.
    val today = Dates.today()
    var focusEpoch by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var filterEpoch by rememberSaveable { mutableStateOf(NO_DAY) }
    val filterDay = if (filterEpoch == NO_DAY) null else LocalDate.ofEpochDay(filterEpoch)

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val barBottom = if (imeBottom > bottomInset) imeBottom + 8.dp else bottomInset
    val barTop = barBottom + with(density) { barHeight.toDp() }

    // The one id that just appeared, to fade it in; comparing against the previous emission
    // rather than the previous composition keeps it stable across unrelated recompositions.
    val pendingIds = state.pending.map { it.id }
    val lastPendingIds = remember { arrayOfNulls<List<Long>>(1) }
    val freshId = remember(pendingIds) {
        val previous = lastPendingIds[0]
        val added = if (previous != null && pendingIds.size > previous.size) {
            pendingIds.toSet() - previous.toSet()
        } else {
            emptySet()
        }
        lastPendingIds[0] = pendingIds
        added.singleOrNull()
    }

    // What the page shows: everything by default, or one day's tasks when a date is picked.
    val visiblePending = remember(state.pending, filterEpoch) {
        if (filterEpoch == NO_DAY) state.pending else state.pending.filter { it.dueDay == filterEpoch }
    }
    val visibleDone = remember(state.done, filterEpoch) {
        if (filterEpoch == NO_DAY) state.done else state.done.filter { it.dueDay == filterEpoch }
    }
    // The little "how many" line under each date in the strip.
    val dayCounts = remember(state.pending) {
        state.pending.mapNotNull { it.dueDay }.groupingBy { it }.eachCount()
    }

    // Rows that already left the database but are still on screen, put back where they were.
    val displayPending = remember(visiblePending, leaving) {
        val rows = visiblePending.toMutableList()
        for (item in leaving) {
            val at = rows.indexOfFirst { it.id == item.todo.id }
            if (at >= 0) rows[at] = item.todo else rows.add(item.index.coerceIn(0, rows.size), item.todo)
        }
        rows
    }
    val leavingIds = remember(leaving) { leaving.mapTo(mutableSetOf()) { it.todo.id } }

    // After adding, bring the new item (the last undated one) into view.
    var lastCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.pending.size) {
        val grew = lastCount >= 0 && state.pending.size > lastCount
        lastCount = state.pending.size
        if (grew) {
            withFrameNanos { }
            withFrameNanos { }
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    // A different day means a different list; start it from the top so the strip stays in view.
    LaunchedEffect(filterEpoch) {
        if (filterEpoch != NO_DAY) scroll.animateScrollTo(0)
    }

    fun deleteWithUndo(todo: TodoEntity) {
        vm.delete(todo)
        scope.launch {
            // An explicit duration: with an action button the default is "until dismissed".
            val result = snackbar.showSnackbar(
                "删掉了「${todo.title}」",
                actionLabel = "撤销",
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.restore(todo)
        }
    }

    fun toggleFromPending(todo: TodoEntity) {
        if (todo.id in leavingIds || todo.id in hiding) return
        val index = displayPending.indexOfFirst { it.id == todo.id }
        if (index < 0) return
        // Commit immediately, then hold the checked row in place: if the user leaves the
        // page mid-animation the database is already right.
        vm.toggle(todo)
        leaving = leaving + LeavingTodo(todo.copy(done = true), index)
        scope.launch {
            delay(if (motion) 200L else 0L)
            hiding = hiding + todo.id
            delay(if (motion) 220L else 0L)
            leaving = leaving.filterNot { it.todo.id == todo.id }
            hiding = hiding - todo.id
        }
    }

    fun toggleFromDone(todo: TodoEntity) {
        vm.toggle(todo)
    }

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = "待办",
                subtitle = when {
                    !state.loaded -> null
                    filterDay != null -> {
                        val date = Dates.monthDay(filterDay)
                        when {
                            visiblePending.isNotEmpty() -> "$date · 还有 ${visiblePending.size} 件"
                            visibleDone.isNotEmpty() -> "$date · 都做完了"
                            else -> "$date · 没有待办"
                        }
                    }
                    state.pending.isEmpty() && state.done.isEmpty() -> null
                    state.pending.isEmpty() -> "都做完了"
                    else -> "还有 ${state.pending.size} 件"
                },
                backdrop = page,
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = barBottom)
                    .onSizeChanged { barHeight = it.height },
            ) {
                SnackbarHost(snackbar, Modifier.padding(horizontal = 8.dp))
                AddBar(
                    backdrop = page,
                    text = input,
                    placeholder = if (filterDay != null) "添加到 ${Dates.monthDay(filterDay)}…" else "添加一件事…",
                    onTextChange = { input = it },
                    onAdd = {
                        // A new task written while a day is picked belongs to that day.
                        vm.add(input, filterDay?.toEpochDay())
                        input = ""
                    },
                )
            }
        },
    ) {
        if (state.loaded && state.pending.isEmpty() && state.done.isEmpty()) {
            GlassSurface(
                modifier = Modifier.align(Alignment.Center),
                style = palette.notice,
                shape = GlassShape.Rounded(22.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有待办", color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.size(6.dp))
                    Text("在下面写一件要做的事", color = palette.contentSecondary, fontSize = 14.sp)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .fadeUnderTopBar(statusTop + TopBarHeight, bottom = barTop)
                .verticalScroll(scroll)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = statusTop + TopBarHeight + 10.dp,
                    bottom = barTop + 20.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val total = visiblePending.size + visibleDone.size
            if (total > 0) {
                ProgressCard(done = visibleDone.size, total = total)
            }

            // The strip filters the page; "全部" keeps everything on one list.
            if (state.pending.isNotEmpty() || state.done.isNotEmpty()) {
                DateStrip(
                    today = today,
                    focused = LocalDate.ofEpochDay(focusEpoch),
                    selected = filterDay,
                    counts = dayCounts,
                    onFocus = { day -> focusEpoch = day.toEpochDay() },
                    onPick = { day ->
                        filterEpoch = day.toEpochDay()
                        focusEpoch = day.toEpochDay()
                    },
                    onAll = { filterEpoch = NO_DAY },
                )
            }

            if (filterDay != null && total == 0) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    style = palette.notice,
                    shape = GlassShape.Rounded(20.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Column {
                        Text("这天没有待办", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("在下面添加一件，会记到这一天", color = palette.contentSecondary, fontSize = 13.sp)
                    }
                }
            }

            if (displayPending.isNotEmpty()) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    style = palette.card,
                    shape = GlassShape.Rounded(24.dp),
                ) {
                    Column {
                        displayPending.forEachIndexed { index, todo ->
                            key(todo.id) {
                                AnimatedVisibility(
                                    visible = todo.id !in hiding,
                                    exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing)) +
                                        fadeOut(tween(140)),
                                ) {
                                    Column {
                                        TodoRow(
                                            todo = todo,
                                            onToggle = {
                                                when {
                                                    todo.id in leavingIds -> Unit
                                                    todo.done -> toggleFromDone(todo)
                                                    else -> toggleFromPending(todo)
                                                }
                                            },
                                            onClick = { editing = todo },
                                            onLongClick = { deleteWithUndo(todo) },
                                            enter = todo.id == freshId,
                                        )
                                        if (index < displayPending.lastIndex) TodoDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (visibleDone.isNotEmpty()) {
                // A just-checked row is still folding out of the pending list; don't show its
                // finished copy here at the same time.
                val shownDone = visibleDone.filterNot { it.id in leavingIds }
                Column {
                    DoneHeader(visibleDone.size, showDone) { showDone = !showDone }
                    AnimatedVisibility(
                        visible = showDone,
                        enter = expandVertically(tween(240, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(140)),
                    ) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            GlassSurface(
                                modifier = Modifier.fillMaxWidth(),
                                style = palette.card,
                                shape = GlassShape.Rounded(20.dp),
                            ) {
                                Column {
                                    shownDone.forEachIndexed { index, todo ->
                                        key(todo.id) {
                                            TodoRow(
                                                todo = todo,
                                                onToggle = { toggleFromDone(todo) },
                                                onClick = { editing = todo },
                                                onLongClick = { deleteWithUndo(todo) },
                                                enter = false,
                                            )
                                            if (index < shownDone.lastIndex) TodoDivider()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { todo ->
        TodoEditDialog(
            todo = todo,
            onDismiss = { editing = null },
            onSave = {
                vm.save(it)
                editing = null
            },
            onDelete = {
                editing = null
                deleteWithUndo(todo)
            },
        )
    }
}

/**
 * How much of the list is finished. Sits a step below the task glass: it carries a few
 * words and a line, so it uses the bar family rather than the card family.
 */
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
        style = palette.bar,
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

/**
 * A swipeable day strip: [全部] on the left, then a rail of dates that dissolves into the glass
 * at both ends. Whatever day comes to rest in the middle becomes the day the page is filtered
 * to, so a swipe and a tap mean the same thing. It filters rather than pages, so tasks without
 * a date still live on the "全部" list.
 */
@Composable
private fun DateStrip(
    today: LocalDate,
    focused: LocalDate,
    selected: LocalDate?,
    counts: Map<Long, Int>,
    onFocus: (LocalDate) -> Unit,
    onPick: (LocalDate) -> Unit,
    onAll: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val baseEpoch = today.toEpochDay() - DAYS_AROUND
    fun dayAt(page: Int): LocalDate = LocalDate.ofEpochDay(baseEpoch + page)
    fun pageOf(day: LocalDate): Int =
        (day.toEpochDay() - baseEpoch).toInt().coerceIn(0, DAYS_AROUND * 2)
    val pager = rememberPagerState(initialPage = pageOf(focused)) { DAYS_AROUND * 2 + 1 }

    // The list changes once the rail has come to rest: a long fling filters once, not per day.
    LaunchedEffect(pager) {
        var moving = false
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }.collect { (page, scrolling) ->
            if (moving && !scrolling) {
                val day = dayAt(page)
                onFocus(day)
                onPick(day)
            }
            moving = scrolling
        }
    }

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        style = palette.bar,
        shape = GlassShape.Rounded(20.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AllDaysChip(selected == null, onAll)
            BoxWithConstraints(Modifier.weight(1f).height(46.dp)) {
                val side = ((maxWidth - DayTileWidth) / 2).coerceAtLeast(0.dp)
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize().edgeFade(64.dp),
                    contentPadding = PaddingValues(horizontal = side),
                    pageSize = PageSize.Fixed(DayTileWidth),
                    pageSpacing = 4.dp,
                    beyondViewportPageCount = 2,
                ) { page ->
                    val day = dayAt(page)
                    val centred = page == pager.currentPage
                    DayTile(
                        day = day,
                        today = today,
                        selected = day == selected,
                        count = counts[day.toEpochDay()] ?: 0,
                    ) {
                        when {
                            // Tapping the day in the middle toggles it: all days on, all days off.
                            centred && day == selected -> onAll()
                            centred -> onPick(day)
                            // A date beside it slides into the middle and is picked on arrival.
                            else -> scope.launch {
                                pager.animateScrollToPage(page, animationSpec = tween(240, easing = FastOutSlowInEasing))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Both ends of the rail dissolve into the glass instead of being cut off. */
private fun Modifier.edgeFade(width: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = width.toPx().coerceAtMost(size.width / 2f)
        if (edge <= 0f) return@drawWithContent
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                edge / size.width to Color.Black,
                1f - edge / size.width to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun AllDaysChip(on: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    val fill by animateColorAsState(
        targetValue = if (on) palette.accent else Color.Transparent,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "allDaysChip",
    )
    Box(
        modifier = Modifier
            .height(46.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(fill)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "全部",
            color = if (on) Color.White else palette.content,
            fontSize = 12.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun DayTile(day: LocalDate, today: LocalDate, selected: Boolean, count: Int, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    val fill by animateColorAsState(
        targetValue = if (selected) palette.accent else Color.Transparent,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "dayTile",
    )
    Box(
        modifier = Modifier
            .size(width = DayTileWidth, height = 46.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(fill)
            .clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = "查看 ${Dates.monthDay(day)} 的待办",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (day == today) "今天" else "${day.monthValue}月",
                color = when {
                    selected -> Color.White.copy(alpha = 0.92f)
                    day == today -> palette.accentContent
                    else -> palette.contentSecondary
                },
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = if (day == today) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                "${day.dayOfMonth}",
                color = if (selected) Color.White else palette.content,
                fontSize = 16.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Box(Modifier.height(11.dp), contentAlignment = Alignment.Center) {
                if (count > 0) {
                    Text(
                        "· $count",
                        color = if (selected) Color.White.copy(alpha = 0.8f) else palette.contentSecondary,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                    )
                }
            }
        }
    }
}

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
        style = palette.bar,
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

@Composable
private fun AddBar(backdrop: Backdrop, text: String, placeholder: String, onTextChange: (String) -> Unit, onAdd: () -> Unit) {
    val palette = LocalGlassPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 50.dp)
                .liquidGlass(backdrop, palette.input, GlassShape.Capsule)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) Text(placeholder, color = palette.contentSecondary, fontSize = 16.sp)
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(color = palette.content, fontSize = 16.sp),
                cursorBrush = SolidColor(palette.accentContent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "添加待办" },
            )
        }
        Spacer(Modifier.width(8.dp))
        GlassIconButton(
            Icons.Rounded.Add,
            "添加",
            onAdd,
            backdrop,
            style = palette.accentSurface,
            tint = Color.White,
            enabled = text.isNotBlank(),
            size = 50.dp,
        )
    }
}
