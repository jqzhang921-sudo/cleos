package com.cleo.cleos.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.ChatSearch
import com.cleo.cleos.data.StickerText
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Whose words a search looks through: [role] as the messages have it, null for both. */
private enum class Side(val role: String?) { All(null), Ta("assistant"), Me("user") }

/**
 * Finding what was said with the current TA, across all their conversations: by words, or by
 * the day on a calendar, the way WeChat does it. Everything is looked up on the phone. A result
 * opens its conversation at that message ([onFound] goes back to the chat, which brings it
 * into view and lights it up).
 */
@Composable
fun SearchScreen(onBack: () -> Unit, onFound: () -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val ta by remember { c.companions.current }.collectAsStateWithLifecycle(null)
    val settings by remember { c.settings.settings }.collectAsStateWithLifecycle(null)
    val conversations by remember(ta?.id) {
        ta?.let { c.db.conversations().observeFor(it.id) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var side by rememberSaveable { mutableStateOf(Side.All) }
    var byDate by rememberSaveable { mutableStateOf(false) }
    // null while nothing has been looked for.
    var results by remember { mutableStateOf<List<MessageEntity>?>(null) }
    var days by remember { mutableStateOf<Set<LocalDate>?>(null) }
    val focus = remember { FocusRequester() }
    val zone = remember { ZoneId.systemDefault() }

    val taName = ta?.name?.trim().orEmpty().ifEmpty { "TA" }
    val myName = settings?.userName?.trim().orEmpty().ifEmpty { "我" }
    val titles = remember(conversations) { conversations.associate { it.id to it.title } }

    fun open(m: MessageEntity) {
        scope.launch {
            c.chat.show(m)
            onFound()
        }
    }

    // A moment after the last key, so each letter typed doesn't start a search of its own.
    LaunchedEffect(query, side, ta?.id) {
        val id = ta?.id ?: return@LaunchedEffect
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(250)
        results = c.db.messages().search(id, ChatSearch.pattern(query), side.role, ChatSearch.LIMIT)
    }
    LaunchedEffect(byDate, ta?.id) {
        val id = ta?.id ?: return@LaunchedEffect
        if (byDate && days == null) days = ChatSearch.days(c.db.messages().saidTimes(id), zone)
    }
    LaunchedEffect(byDate) { if (!byDate) runCatching { focus.requestFocus() } }
    BackHandler(enabled = byDate) { byDate = false }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = if (byDate) "按日期查找" else "查找聊天记录",
                subtitle = "和${taName}的全部对话",
                backdrop = page,
                leading = {
                    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", { if (byDate) byDate = false else onBack() }, page)
                },
            )
        },
    ) {
        if (byDate) {
            DayPicker(
                days = days,
                today = LocalDate.now(zone),
                top = statusTop + TopBarHeight,
                bottom = navBottom,
                onPick = { day ->
                    val id = ta?.id ?: return@DayPicker
                    scope.launch {
                        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
                        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                        c.db.messages().firstSaidBetween(id, from, to)?.let { c.chat.show(it); onFound() }
                    }
                },
            )
            return@GlassPage
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().fadeUnderTopBar(statusTop + TopBarHeight),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = statusTop + TopBarHeight + 10.dp, bottom = navBottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "field") {
                com.cleo.cleos.ui.common.GlassSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜聊过的话",
                    description = "搜索聊天记录",
                    fieldModifier = Modifier.focusRequester(focus),
                )
            }
            item(key = "sides") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Side.entries.forEach { s ->
                        val label = when (s) {
                            Side.All -> "全部"
                            Side.Ta -> "${taName}说的"
                            Side.Me -> "我说的"
                        }
                        Pill(label, selected = side == s) { side = s }
                    }
                }
            }
            val found = results
            if (found == null) {
                item(key = "dates") {
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null) { byDate = true },
                        shape = GlassShape.Rounded(22.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = palette.accentContent, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("按日期查找", color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("聊过天的日子都标出来了，点一天就到那天", color = palette.contentSecondary, fontSize = 12.sp)
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.contentSecondary)
                        }
                    }
                }
                item(key = "hint") {
                    Text(
                        "搜的是和${taName}聊过的话，所有对话一起找，语音消息搜它转出来的字。都在这台手机上找，不联网。",
                        color = palette.contentSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            } else {
                item(key = "count") {
                    Text(
                        when {
                            found.isEmpty() -> "没找到"
                            found.size >= ChatSearch.LIMIT -> "找到很多，这里是最近的 ${ChatSearch.LIMIT} 条"
                            else -> "找到 ${found.size} 条"
                        },
                        color = palette.contentSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                items(found, key = { it.id }) { m ->
                    Hit(
                        message = m,
                        who = if (m.role == "user") myName else taName,
                        query = query,
                        conversation = titles[m.conversationId].takeIf { conversations.size > 1 },
                        onClick = { open(m) },
                    )
                }
            }
        }
    }
}

/** One result: who said it and when, the words around what was looked for, lit up. */
@Composable
private fun Hit(message: MessageEntity, who: String, query: String, conversation: String?, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    val snippet = remember(message.content, query) { ChatSearch.snippet(StickerText.plain(message.content), query) }
    GlassSurface(
        modifier = Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null, onClick = onClick),
        shape = GlassShape.Rounded(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(who, color = palette.content, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(Dates.chatStamp(message.createdAt), color = palette.contentSecondary, fontSize = 12.sp)
            }
            Text(
                buildAnnotatedString {
                    if (message.audio != null) append("[语音] ")
                    var at = 0
                    for (hit in snippet.hits) {
                        append(snippet.text.substring(at, hit.first))
                        withStyle(SpanStyle(color = palette.accentContent, fontWeight = FontWeight.SemiBold)) {
                            append(snippet.text.substring(hit.first, hit.last + 1))
                        }
                        at = hit.last + 1
                    }
                    append(snippet.text.substring(at))
                },
                color = palette.content,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            conversation?.let { Text("在「$it」里", color = palette.contentSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * Months from the first day anything was said to this one, the latest at the bottom where the
 * list opens, under a row of weekdays that stays put. Days something was said on are dark and
 * can be tapped; the rest are faint. Today has a ring.
 */
@Composable
private fun DayPicker(days: Set<LocalDate>?, today: LocalDate, top: Dp, bottom: Dp, onPick: (LocalDate) -> Unit) {
    val palette = LocalGlassPalette.current
    val months = remember(days, today) {
        val first = days?.minOrNull()?.let(YearMonth::from) ?: YearMonth.from(today)
        generateSequence(first) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(today)) }.toList()
    }
    val state = rememberLazyListState()
    LaunchedEffect(months.size, days != null) { if (days != null && months.isNotEmpty()) state.scrollToItem(months.lastIndex) }
    Column(Modifier.fillMaxSize().padding(top = top)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                Text(it, color = palette.contentSecondary, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        if (days == null) return@Column
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(months, key = { it.toString() }) { month ->
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = GlassShape.Rounded(22.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${month.year}年${month.monthValue}月",
                            color = palette.content,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
                        )
                        // Sunday first, like the weekday row above.
                        val lead = month.atDay(1).dayOfWeek.value % 7
                        val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
                        cells.chunked(7).forEach { week ->
                            Row(Modifier.fillMaxWidth()) {
                                for (i in 0 until 7) {
                                    val day = week.getOrNull(i)
                                    Box(Modifier.weight(1f).aspectRatio(1.15f), contentAlignment = Alignment.Center) {
                                        if (day != null) DayCell(day, said = day in days, today = day == today, onPick = onPick)
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

@Composable
private fun DayCell(day: LocalDate, said: Boolean, today: Boolean, onPick: (LocalDate) -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .then(if (today) Modifier.border(1.5.dp, palette.accentContent, CircleShape) else Modifier)
            .then(if (said) Modifier.clickable { onPick(day) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.dayOfMonth.toString(),
            color = if (said) palette.content else palette.contentSecondary.copy(alpha = 0.45f),
            fontSize = 16.sp,
            fontWeight = if (said) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** A plain pill, not glass: it sits in a row on the page like the settings' chips. */
@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) palette.accent else palette.content.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) Color.White else palette.content, fontSize = 14.sp)
    }
}
