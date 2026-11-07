package com.cleo.cleos.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.cleo.cleos.ui.records.LocalRecordCalendar
import com.cleo.cleos.ui.records.RecordViewButton
import com.cleo.cleos.ui.records.RecordsCalendar
import com.cleo.cleos.ui.records.RecordDateScope
import com.cleo.cleos.ui.records.RecordViewContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.records.recordTopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

private data class DiaryCard(
    val id: Long,
    val date: LocalDate,
    val title: String,
    val excerpt: String,
    val cover: File?,
    val imageCount: Int,
    /** The TA who wrote it; null for the person's own. */
    val byTa: Long?,
    val secret: Boolean,
)

private sealed interface DiaryRow {
    data class Month(val month: YearMonth) : DiaryRow
    data class Entry(val card: DiaryCard) : DiaryRow
}

/** One book, both writers: these pick whose pages to show, or only the locked ones. */
private enum class DiaryFilter(val label: String, val empty: String, val hint: String) {
    All("全部", "还没有日记", "点右下角的笔，写第一篇"),
    Mine("我写的", "你还没写过日记", "点右下角的笔，写第一篇"),
    Theirs("TA 写的", "TA 还没写过日记", "在聊天里请 TA 写一篇"),
    Secrets("小秘密", "还没有小秘密", "你和 TA 都可以把想留给自己的话写在这里"),
    ;

    fun accepts(e: DiaryEntryEntity) = when (this) {
        All -> true
        Mine -> e.author == DiaryEntryEntity.AUTHOR_ME
        Theirs -> e.author == DiaryEntryEntity.AUTHOR_AI
        Secrets -> e.secret
    }
}

@Composable
fun DiaryTab(
    bottomInset: Dp,
    onOpenEntry: (id: Long, secret: Boolean, day: Long?) -> Unit,
    sectionHeader: @Composable () -> Unit = {},
) {
    val control = LocalRecordCalendar.current
    val selectedDay = control.dates.dayEpoch
    val c = appContainer()
    val palette = LocalGlassPalette.current
    var filter by rememberSaveable { mutableStateOf(DiaryFilter.All) }
    var writerId by rememberSaveable { mutableStateOf(0L) }
    val queryKey = listOf(filter.name, writerId.toString(), selectedDay.toString())
    val result by remember(filter, writerId, selectedDay) {
        c.db.diary().observeAll()
            .map { entries ->
                val eligible = entries.filter { filter.accepts(it) && (writerId == 0L || it.companionId == writerId) }
                val rows = toRows(eligible.filter {
                    selectedDay == com.cleo.cleos.ui.todo.TodoCalendarModel.ALL || it.day == selectedDay
                }) { c.images.file(it) }
                Triple(queryKey, eligible.groupingBy { it.day }.eachCount(), rows)
            }
            .flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(null)
    val current = result?.takeIf { it.first == queryKey }
    val rows = current?.third
    val counts = current?.second.orEmpty()
    val listState = rememberLazyListState()
    val calendarState = rememberLazyListState()
    val viewportKey = "$selectedDay:$filter:$writerId"
    var previousViewportKey by rememberSaveable { mutableStateOf(viewportKey) }
    LaunchedEffect(viewportKey) {
        if (previousViewportKey != viewportKey) {
            listState.scrollToItem(0)
            calendarState.scrollToItem(0)
            previousViewportKey = viewportKey
        }
    }
    // Which TA wrote which entry, by name: the book holds every TA's pages.
    val companions by remember { c.companions.all }.collectAsStateWithLifecycle(null)
    val names = companions.orEmpty().associate { it.id to it.name.trim().ifEmpty { "TA" } }
    LaunchedEffect(companions, writerId, filter) {
        // Older versions could save the removed, all-TA filter. Keep a visible selection.
        if ((filter == DiaryFilter.Theirs && writerId == 0L) ||
            (companions != null && writerId != 0L && writerId !in names)
        ) {
            writerId = 0L
            filter = DiaryFilter.All
        }
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBarHeight = recordTopBarHeight()
    val count = rows?.count { it is DiaryRow.Entry } ?: 0

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                barHeight = topBarHeight,
                title = "日记",
                subtitle = if (count > 0) "$count 篇" else null,
                backdrop = page,
                trailing = { RecordViewButton(page) },
            )
            // Writing from the secrets page starts locked: that page is where secrets are kept.
            val secret = filter == DiaryFilter.Secrets
            GlassIconButton(
                if (secret) Icons.Rounded.Lock else Icons.Rounded.EditNote,
                if (secret) "写小秘密" else "写日记",
                { onOpenEntry(0L, secret, control.dates.day?.toEpochDay()) },
                page,
                style = palette.accentSurface,
                tint = Color.White,
                size = 58.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = bottomInset + 4.dp),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().padding(top = statusTop + topBarHeight + 6.dp)) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                sectionHeader()
                Spacer(Modifier.height(10.dp))
                FilterBar(filter, writerId, companions.orEmpty()) { next, writer ->
                    filter = next
                    writerId = writer
                }
                Spacer(Modifier.height(6.dp))
            }
            RecordViewContent(control.calendar == true, Modifier.weight(1f)) { calendar ->
                val list = rows
                if (list != null && list.isEmpty() && !calendar && control.dates.day == null) {
                    GlassSurface(
                        modifier = Modifier.align(Alignment.Center),
                        style = palette.notice,
                        shape = GlassShape.Rounded(22.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (writerId != 0L) "${names[writerId] ?: "TA"}还没写过日记" else filter.empty,
                                color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(filter.hint, color = palette.contentSecondary, fontSize = 14.sp)
                        }
                    }
                }
                LazyColumn(
                    state = if (calendar) calendarState else listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .fadeUnderTopBar(0.dp, bottom = bottomInset - 10.dp),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = bottomInset + 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (calendar) item(key = "calendar") { RecordsCalendar(diaryCounts = counts) }
                    item(key = "dateScope") { RecordDateScope() }
                    if (list != null && list.isEmpty() && (calendar || control.dates.day != null)) {
                        item(key = "dateEmpty") {
                            Text(if (control.dates.day != null) "这天没有日记" else filter.empty,
                                color = palette.contentSecondary, modifier = Modifier.padding(16.dp))
                        }
                    }
                    items(list.orEmpty(), key = {
                        when (it) {
                            is DiaryRow.Month -> "m${it.month}"
                            is DiaryRow.Entry -> it.card.id
                        }
                    }) { row ->
                        when (row) {
                            is DiaryRow.Month -> MonthHeader(row.month)
                            is DiaryRow.Entry -> DiaryCardView(row.card, row.card.byTa?.let { names[it] ?: "TA" }) {
                                onOpenEntry(row.card.id, false, null)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun toRows(entries: List<DiaryEntryEntity>, file: (String) -> File): List<DiaryRow> {
    val rows = ArrayList<DiaryRow>(entries.size + 12)
    var month: YearMonth? = null
    for (e in entries) {
        val date = LocalDate.ofEpochDay(e.day)
        val ym = YearMonth.from(date)
        if (ym != month) {
            rows += DiaryRow.Month(ym)
            month = ym
        }
        val blocks = DiaryBlocks.decode(e.blocks)
        val images = DiaryBlocks.images(blocks)
        rows += DiaryRow.Entry(
            DiaryCard(
                id = e.id,
                date = date,
                title = if (e.lockedForUser) "TA 的小秘密" else e.title,
                excerpt = if (e.lockedForUser) e.publicHint.ifBlank { "有些话，暂时想留给自己" } else DiaryBlocks.plainText(blocks).replace(Regex("\\s+"), " ").take(160),
                cover = if (e.lockedForUser) null else images.firstOrNull()?.let { file(it.file) },
                imageCount = images.size,
                byTa = if (e.author == DiaryEntryEntity.AUTHOR_AI) e.companionId ?: 0L else null,
                secret = e.secret,
            ),
        )
    }
    return rows
}

@Composable
private fun MonthHeader(month: YearMonth) {
    val palette = LocalGlassPalette.current
    Box(Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp)) {
        GlassSurface(style = palette.bar, shape = GlassShape.Capsule, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)) {
            Text(Dates.yearMonth(month.atDay(1)), color = palette.content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A separate, horizontally scrollable row, below the diary / todo sections. */
@Composable
private fun FilterBar(
    selected: DiaryFilter,
    writerId: Long,
    companions: List<CompanionEntity>,
    onSelect: (DiaryFilter, Long) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DiaryFilter.entries.take(2).forEach { filter ->
            WriterChip(filter.label, writerId == 0L && selected == filter) { onSelect(filter, 0L) }
        }
        companions.forEach { ta ->
            WriterChip(ta.name.trim().ifEmpty { "TA" }, writerId == ta.id) { onSelect(DiaryFilter.Theirs, ta.id) }
        }
        WriterChip(DiaryFilter.Secrets.label, writerId == 0L && selected == DiaryFilter.Secrets) {
            onSelect(DiaryFilter.Secrets, 0L)
        }
    }
}

@Composable
private fun WriterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier.clip(CircleShape)
            .background(palette.content.copy(alpha = if (selected) .14f else .06f))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 44.dp).widthIn(max = 170.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (selected) palette.content else palette.contentSecondary,
            fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CardTag(icon: ImageVector, text: String) {
    val palette = LocalGlassPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 3.dp)) {
        Icon(icon, contentDescription = null, tint = palette.accentContent, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = palette.accentContent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DiaryCardView(card: DiaryCard, ta: String?, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null, onClick = onClick),
        shape = GlassShape.Rounded(24.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${card.date.dayOfMonth}", color = palette.accentContent, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                Text(Dates.weekday(card.date).replace("星期", "周"), color = palette.contentSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                when {
                    card.secret -> CardTag(Icons.Rounded.Lock, if (ta != null) "${ta}的小秘密" else "我的小秘密")
                    ta != null -> CardTag(Icons.Rounded.AutoAwesome, "${ta}写的")
                }
                if (card.title.isNotBlank()) {
                    Text(card.title, color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.size(3.dp))
                }
                when {
                    card.excerpt.isNotBlank() -> Text(
                        card.excerpt,
                        color = palette.content.copy(alpha = 0.78f),
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        maxLines = if (card.title.isBlank()) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    card.title.isBlank() -> Text("${card.imageCount} 张图片", color = palette.contentSecondary, fontSize = 14.sp)
                }
            }
            card.cover?.let { cover ->
                Spacer(Modifier.width(12.dp))
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
                )
            }
        }
    }
}
