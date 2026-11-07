package com.cleo.cleos.ui.world

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.WorldOutside
import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.WorldOutsideSelection
import com.cleo.cleos.ui.LocalPageShown
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorldOutsidePage(ta: CompanionEntity?, articles: List<WorldArticleEntity>, notes: List<WorldNoteEntity>,
    looks: List<WorldPictureLookEntity>, pictures: List<WorldPictureEntity>, ready: Boolean, reading: WorldReadingStateEntity?,
    busy: Boolean, top: Dp, bottom: Dp, backdrop: Backdrop, onOpen: (Long, Int) -> Unit,
    onLookup: (String) -> Unit, onJournal: (Long) -> Unit, onJourney: () -> Unit, navigation: WorldNavigationModel,
) {
    val c = appContainer(); val p = LocalGlassPalette.current; val scope = rememberCoroutineScope()
    val states by c.worldOutside.states.collectAsStateWithLifecycle()
    val refresh = states[ta?.id]
    var sourceId by navigation.outsideSource
    val scheduleRow by key(ta?.id) { remember { ta?.let { c.db.world().observeOutsideSchedule(it.id) } ?: flowOf(null) }.collectAsStateWithLifecycle(null) }
    val schedule = scheduleRow?.takeIf { ta != null && it.companionId == ta.id && it.companionCreatedAt == ta.createdAt }
    var changing by remember(ta?.id) { mutableStateOf(false) }
    var error by remember(ta?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(ta?.id, schedule?.sourceId) { schedule?.takeIf { ticket -> WorldOutside.sources.any { it.id == ticket.sourceId } }?.let { sourceId = it.sourceId } }
    fun permission(enabled: Boolean, feed: String) {
        val target = ta ?: return
        changing = true; error = null
        scope.launch {
            try {
                c.worldOutsideScheduler.setEnabled(target.id, target.createdAt, enabled, feed)
                if (navigation.selectedId.value == target.id) sourceId = feed
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "自主浏览设置未完成，请稍后重试" }
            finally { changing = false }
        }
    }
    WorldOutsidePictureMaintenance(ta)
    val source = WorldOutside.sources.firstOrNull { it.id == sourceId } ?: WorldOutside.sources.first()
    val outside = remember(ta?.id, articles) { WorldOutsideViews.articles(ta?.id, articles) }
    val publicNotes = remember(ta?.id, articles, notes, looks, pictures) { WorldOutsideViews.notes(ta?.id, articles, notes, looks, pictures) }
    val trail = remember(ta?.id, articles, notes, looks, pictures) { WorldOutsideViews.trail(ta?.id, articles, notes, looks, pictures) }
    var managing by navigation.outsideManaging
    var selected by navigation.outsideSelected
    var options by navigation.outsideOptions
    var filter by navigation.outsideBrowseSource
    val tab = navigation.outsideTab.value.coerceIn(0, 2)
    val shown = if (managing) WorldOutsideSelection.rows(ta?.id, outside, navigation.outsideFilter.value) else WorldOutsideViews.articles(ta?.id, outside, filter)
    val deleting by c.world.removingOutside.collectAsStateWithLifecycle()
    val removing = ta?.id in deleting
    val feedList = rememberSaveable(ta?.id, saver = LazyListState.Saver) { LazyListState() }
    val trailList = rememberSaveable(ta?.id, saver = LazyListState.Saver) { LazyListState() }
    val noteList = rememberSaveable(ta?.id, saver = LazyListState.Saver) { LazyListState() }
    val list = when (tab) { 1 -> trailList; 2 -> noteList; else -> feedList }
    // Explicit anchors survive the parent's temporarily absent TA/Room flows on recreation.
    LaunchedEffect(ta?.id, navigation.selectedId.value, tab, ready) {
        val owner = ta?.id ?: return@LaunchedEffect
        if (!ready || navigation.selectedId.value != owner) return@LaunchedEffect
        val (index, offset) = navigation.outsideScroll(tab)
        list.scrollToItem(index, offset)
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.layoutInfo.totalItemsCount) }
            .collect { (row, pixels, count) ->
                if (count > 0 && navigation.selectedId.value == owner) navigation.saveOutsideScroll(tab, row, pixels)
            }
    }
    LaunchedEffect(managing) { if (managing) feedList.scrollToItem(0) }
    BackHandler(enabled = LocalPageShown.current && (managing || options)) {
        if (managing) { managing = false; selected = arrayListOf() } else options = false
    }
    val used = reading?.takeIf { it.companionId == ta?.id && it.day == LocalDate.now().toEpochDay() }
        ?.let { (it.attempts - if (it.token != null) 1 else 0).coerceAtLeast(0) } ?: 0
    val outsideBusy = busy && reading?.space in setOf("outside", "picture")
    // Do not lay out an empty placeholder list over a restored scroll position while Room starts.
    if (!ready) {
        Box(Modifier.fillMaxSize().padding(worldPadding(top, bottom))) { Text("正在打开本地见闻…", color = p.contentSecondary, fontSize = 14.sp) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().fadeUnderTopBar(top, bottom), state = list, contentPadding = worldPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "tabs") { PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = p.content) {
            listOf("见闻", "足迹", "笔记").forEachIndexed { index, label -> Tab(selected = tab == index, enabled = !managing,
                onClick = { navigation.outsideTab.value = index; options = false }, text = { Text(label, fontSize = 15.sp) }) }
        } }
        if (!managing) item(key = "actions") { WorldPanel {
            Text("${source.name} · 来源摘要", color = p.contentSecondary, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(onClick = { ta?.let { if (refresh?.busy == true) c.worldOutside.cancel(it.id) else c.worldOutside.refresh(it.id, it.createdAt, source.id) } },
                    backdrop = backdrop, enabled = ta != null) { Text(if (refresh?.busy == true) "停止刷新" else "刷新见闻") }
                TextButton(onClick = { ta?.let { if (outsideBusy) c.world.cancel(it.id) else c.world.startOutside(it.id) } },
                    enabled = ta != null && (outsideBusy || !busy && outside.any { it.readOffset < it.content.length } && used < WorldReading.DAILY)) {
                    Text(if (outsideBusy) "停止本次活动" else "让 TA 看一会")
                }
            }
            Text("获取不调用模型；阅读会把摘要发送给这位 TA 的模型。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 19.sp)
            if (busy && !outsideBusy) Text("TA 正在其他空间活动，完成后可继续窗外阅读。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 19.sp)
            refresh?.message?.takeIf(String::isNotBlank)?.let { Text(it, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp) }
            reading?.takeIf { it.companionId == ta?.id && it.space in setOf("outside", "picture") && it.status.isNotBlank() }?.let {
                Text((if (outsideBusy) "本次活动：" else "最近一次结果：") + it.status, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onJourney, enabled = ta != null) { Text("图文漫游") }
                TextButton(onClick = { onLookup("") }, enabled = ta != null) { Text("继续查阅") }
                TextButton(onClick = { options = !options }) { Text(if (options) "收起窗外设置" else "窗外设置") }
            }
        } }
        if (options && !managing) {
            item(key = "source-settings") { WorldOutsideSourceSettings(source.id, refresh?.busy != true && !changing, used) {
                if (schedule != null && sourceId != it) permission(true, it) else sourceId = it
            } }
            item(key = "schedule-settings") { WorldOutsideScheduleSettings(ta, schedule, source.id, changing, error) { permission(it, source.id) } }
            item(key = "topic-settings") { WorldPanel { WorldTopicConsent(ta) } }
            item(key = "picture-settings") { WorldPanel { WorldPictureConsent(ta, articles, reading) } }
        }
        when (tab) {
            0 -> {
                item(key = "management") { WorldOutsideManagement(ta, outside, shown, navigation, busy || refresh?.busy == true) }
                if (!managing && outside.isNotEmpty()) item(key = "source-filter") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf("") + outside.map { it.source }.distinct().sorted()).forEach { sourceName -> FilterChip(selected = filter == sourceName,
                            onClick = { filter = sourceName }, label = { Text(sourceName.ifEmpty { "全部来源" }) }) }
                    }
                }
                if (outside.isEmpty()) item(key = "empty") { WorldPanel {
                    Text("窗外还没有见闻", color = p.content, fontSize = 18.sp)
                    Text("刷新可以带回来源提供的摘要；获取与 TA 已经读过，会分别显示。", color = p.contentSecondary, fontSize = 14.sp, lineHeight = 23.sp)
                } }
                else if (shown.isEmpty()) item(key = "empty-filter") { WorldPanel { Text("这个来源没有见闻，可以切回全部来源。", color = p.contentSecondary) } }
                shown.groupBy { outsideDate(it.createdAt) }.forEach { (day, rows) ->
                    item(key = "day:$day") { Text("保存于 $day", color = p.contentSecondary, fontSize = 12.sp) }
                    items(rows, key = { "article:${it.id}" }) { entry -> WorldPanel {
                        if (managing) Row(Modifier.fillMaxWidth().toggleable(value = entry.id in selected, enabled = !removing, role = Role.Checkbox,
                            onValueChange = { checked -> selected = ArrayList(if (checked) (selected + entry.id).distinct() else selected - entry.id) })
                            .semantics { contentDescription = "选择见闻：${entry.title}" }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = entry.id in selected, onCheckedChange = null, enabled = !removing)
                            Text(if (entry.id in selected) "已选择" else "选择这条见闻", color = p.contentSecondary, fontSize = 13.sp)
                        }
                        WorldOutsideCardData.from(ta?.id, entry, notes)?.let { data -> WorldOutsideCard(entry, data, navigation.outsideExpanded.value == entry.id,
                            { navigation.outsideExpanded.value = if (navigation.outsideExpanded.value == entry.id) 0L else entry.id },
                            managing, backdrop, { onJournal(entry.id) }, { onOpen(entry.id, entry.readOffset) }) }
                    } }
                }
            }
            1 -> {
                item(key = "trail-scope") { Text("依据现有资料回看最近 ${WorldOutsideViews.TRAIL_LIMIT} 条记录，并非完整活动日志。阅读只显示最近保存的进度；删除资料后，关联记录也不再出现在这里。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp) }
                if (trail.isEmpty()) item(key = "trail-empty") { WorldPanel { Text("还没有可回看的窗外足迹", color = p.content, fontSize = 17.sp); Text("保存见闻、阅读摘要或留下笔记后，这里会按实际记录显示。", color = p.contentSecondary, fontSize = 14.sp, lineHeight = 23.sp) } }
                trail.groupBy { outsideDate(it.at) }.forEach { (day, rows) ->
                    item(key = "trail-day:$day") { Text(day, color = p.contentSecondary, fontSize = 12.sp) }
                    items(rows, key = { it.key }) { step -> WorldOutsideStepCard(step) { onJournal(step.article.id) } }
                }
            }
            2 -> {
                item(key = "note-scope") { Text("这里汇集这位 TA 与窗外见闻关联的摘要笔记、曾留的看图感想和问题，与随手本共用已有记录。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp) }
                if (publicNotes.isEmpty()) item(key = "note-empty") { WorldPanel { Text("还没有留下窗外笔记", color = p.content, fontSize = 17.sp); Text("TA 可以看过以后留几句，也可以不写。书房与私人随笔仍在随手本。", color = p.contentSecondary, fontSize = 14.sp, lineHeight = 23.sp) } }
                items(publicNotes, key = { it.key }) { note -> WorldOutsideNoteCard(note, { onJournal(note.article.id) }, onLookup) }
            }
        }
    }
}
