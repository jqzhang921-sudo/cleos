package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.WorldJourneyTopics
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Collection, editing and reading are full-page siblings within the existing journey route. */
@Composable
internal fun WorldJourneys(ta: CompanionEntity?, articles: List<WorldArticleEntity>, notes: List<WorldNoteEntity>,
    looks: List<WorldPictureLookEntity>, pictures: List<WorldPictureEntity>, ready: Boolean,
    top: Dp, bottom: Dp, backdrop: Backdrop, depth: Boolean, navigation: WorldNavigationModel,
    onJournal: (Long) -> Unit, onSource: (Long, Int) -> Unit, onLookup: (String) -> Unit,
) {
    val c = appContainer(); val p = LocalGlassPalette.current; val scope = rememberCoroutineScope()
    val topicsRow by key(ta?.id) { remember { ta?.let { c.db.world().observeJourneyTopics(it.id) } ?: flowOf(emptyList()) }.collectAsStateWithLifecycle<List<WorldJourneyTopicEntity>?>(null) }
    val itemsRow by key(ta?.id) { remember { ta?.let { c.db.world().observeJourneyItems(it.id) } ?: flowOf(emptyList()) }.collectAsStateWithLifecycle<List<WorldJourneyItemEntity>?>(null) }
    val topics = topicsRow.orEmpty().filter { it.companionId == ta?.id }
    val entries = itemsRow.orEmpty().filter { it.companionId == ta?.id }
    val topic = topics.firstOrNull { it.id == navigation.journeyTopicId.value }
    val draft = navigation.journeyEditor.value
    val list = rememberLazyListState()
    var deleting by remember(ta?.id) { mutableStateOf<WorldJourneyTopicEntity?>(null) }
    var error by remember(ta?.id) { mutableStateOf<String?>(null) }
    val revision = remember(ta?.id, ta?.createdAt) { c.world.revision() }
    val loaded = ready && topicsRow != null && itemsRow != null
    val library = draft == null && navigation.journeyTopicId.value == 0L && !navigation.journeyRecent.value
    LaunchedEffect(ta?.id, navigation.selectedId.value, loaded, library) {
        val owner = ta?.id ?: return@LaunchedEffect
        if (!loaded || !library || navigation.selectedId.value != owner) return@LaunchedEffect
        list.scrollToItem(navigation.journeyLibraryIndex.value, navigation.journeyLibraryOffset.value)
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.layoutInfo.totalItemsCount) }.collect { (index, offset, count) ->
            if (count > 0 && navigation.selectedId.value == owner) { navigation.journeyLibraryIndex.value = index; navigation.journeyLibraryOffset.value = offset }
        }
    }
    fun edit(row: WorldJourneyTopicEntity?) {
        val owner = ta ?: return
        navigation.journeyEditor.value = WorldJourneyDraft(owner.id, owner.createdAt, c.world.revision(), row,
            entries.filter { it.topicId == row?.id }.sortedBy { it.position })
    }
    if (!loaded) {
        Box(Modifier.fillMaxSize().padding(worldPadding(top, bottom))) { Text("正在打开本地专题…", color = p.contentSecondary, fontSize = 14.sp) }; return
    }
    if (draft != null) {
        WorldJourneyEditor(draft, ta, articles, top, bottom, backdrop, { navigation.journeyEditor.value = null }) {
            if (draft.saving) return@WorldJourneyEditor
            draft.saving = true; draft.error = null
            scope.launch {
                try {
                    val id = c.world.saveJourneyTopic(draft.owner, draft.ownerCreatedAt, draft.revision, draft.expected, draft.expectedItems,
                        draft.title, draft.kind, draft.selected)
                    if (navigation.selectedId.value == draft.owner && navigation.journeyEditor.value === draft) {
                        navigation.journeyEditor.value = null; navigation.openJourneyTopic(id)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { draft.error = e.message ?: "专题未保存，请稍后重试" }
                finally { draft.saving = false }
            }
        }; return
    }
    if (navigation.journeyTopicId.value != 0L || navigation.journeyRecent.value) {
        if (navigation.journeyTopicId.value != 0L && topic == null) {
            Column(Modifier.padding(worldPadding(top, bottom))) { WorldPanel {
                Text("这份专题暂不可查看", color = p.content, fontSize = 18.sp)
                Text("资料可能已移除或正在更新，可以返回列表核对。", color = p.contentSecondary, fontSize = 14.sp)
                TextButton(onClick = { navigation.backJourney() }) { Text("返回专题列表") }
            } }; return
        }
        WorldJourney(ta, articles, notes, looks, pictures, top, bottom, depth, onJournal, onSource, onLookup,
            navigation, topic, entries, { edit(topic) }); return
    }
    LazyColumn(Modifier.fillMaxSize().fadeUnderTopBar(top, bottom), state = list, contentPadding = worldPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "intro") { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("把好奇连成一条线", color = p.content, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text("围绕一个地方或主题，把这位 TA 已保存的公开见闻按顺序放在一起。图片、摘要与感想各有出处，浏览不调用模型。", color = p.contentSecondary, fontSize = 13.sp, lineHeight = 22.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(onClick = { edit(null) }, enabled = ta != null, backdrop = backdrop) { Text("新建专题") }
                TextButton(onClick = { navigation.openRecentJourney() }, enabled = ta != null) { Text("随意漫游") }
                TextButton(onClick = { onLookup("") }, enabled = ta != null) { Text("继续查阅") }
            }
            error?.let { Text(it, color = p.error, fontSize = 13.sp) }
        } }
        if (topics.isEmpty()) item(key = "empty") { WorldPanel {
            Text("还没有保存专题", color = p.content, fontSize = 17.sp)
            Text("可以先新建一份，也可以随意翻阅近期见闻。没有配图的资料仍可加入。", color = p.contentSecondary, fontSize = 14.sp, lineHeight = 23.sp)
        } }
        items(topics, key = { it.id }) { row ->
            val sources = WorldJourneyTopics.articles(ta?.id, row, entries, articles)
            val complete = sources.count { WorldOutsideCardData.from(ta?.id, it, emptyList())?.reading == WorldOutsideCardData.Reading.Complete }
            WorldPanel {
                Text("${WorldJourneyTopics.kinds[row.kind] ?: "主题"} · ${outsideTime(row.updatedAt)}", color = p.contentSecondary, fontSize = 12.sp)
                Text(row.title, color = p.content, fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium)
                Text("${sources.size} 则见闻 · TA 已读完 $complete 份摘要", color = p.contentSecondary, fontSize = 13.sp)
                if (sources.isEmpty()) Text("原见闻已移除，可重新整理加入内容。", color = p.contentSecondary, fontSize = 13.sp)
                else Text(sources.take(2).joinToString(" · ") { it.title }, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 22.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { navigation.openJourneyTopic(row.id) }) { Text("打开专题") }
                    TextButton(onClick = { edit(row) }) { Text("整理专题") }
                    TextButton(onClick = { deleting = row }) { Text("移除专题", color = p.error) }
                }
            }
        }
    }
    deleting?.let { expected -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除这份专题？") },
        text = { Text("《${expected.title}》的分组和顺序将被移除。原始见闻、配图、阅读进度和笔记会保留。") },
        confirmButton = { TextButton(onClick = {
            deleting = null; val owner = ta ?: return@TextButton
            scope.launch { try { c.world.deleteJourneyTopic(owner.id, owner.createdAt, revision, expected) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "专题未移除，请重新打开后核对" } }
        }) { Text("移除", color = p.error) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun WorldJourneyEditor(draft: WorldJourneyDraft, ta: CompanionEntity?, articles: List<WorldArticleEntity>,
    top: Dp, bottom: Dp, backdrop: Backdrop, onCancel: () -> Unit, onSave: () -> Unit,
) {
    val p = LocalGlassPalette.current
    if (ta == null || draft.owner != ta.id || draft.ownerCreatedAt != ta.createdAt) return
    val sources = WorldOutsideViews.articles(ta.id, articles)
    LazyColumn(Modifier.fillMaxSize().fadeUnderTopBar(top, bottom), contentPadding = worldPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "form") { WorldPanel {
            Text(if (draft.expected == null) "新建图文专题" else "整理图文专题", color = p.content, fontSize = 18.sp)
            OutlinedTextField(draft.title, { if (it.length <= WorldJourneyTopics.MAX_TITLE) draft.title = it }, Modifier.fillMaxWidth(),
                label = { Text("专题名称") }, enabled = !draft.saving, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { WorldJourneyTopics.kinds.forEach { (key, label) ->
                FilterChip(selected = draft.kind == key, onClick = { draft.kind = key }, enabled = !draft.saving, label = { Text(label) })
            } }
            Text("从已有窗外见闻中选择，再调整顺序。保存不复制正文、不请求模型。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(onClick = onSave, backdrop = backdrop, enabled = !draft.saving && draft.title.isNotBlank() && draft.selected.isNotEmpty()) { Text(if (draft.saving) "正在保存…" else "保存专题") }
                TextButton(onClick = onCancel, enabled = !draft.saving) { Text("取消整理") }
            }
            draft.error?.let { Text(it, color = p.error, fontSize = 13.sp, lineHeight = 21.sp) }
        } }
        item(key = "order-title") { Text("专题顺序 · ${draft.selected.size} 则", color = p.content, fontSize = 15.sp) }
        items(draft.selected, key = { "chosen:$it" }) { id ->
            val index = draft.selected.indexOf(id); val source = sources.firstOrNull { it.id == id }
            WorldPanel {
                Text("${index + 1} · ${source?.title ?: "这条见闻已移除，请重新选择"}", color = p.content, fontSize = 14.sp, lineHeight = 23.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { draft.selected = draft.selected.toMutableList().apply { add(index - 1, removeAt(index)) } }, enabled = !draft.saving && index > 0,
                        modifier = Modifier.semantics { contentDescription = "上移见闻$id" }) { Text("上移") }
                    TextButton(onClick = { draft.selected = draft.selected.toMutableList().apply { add(index + 1, removeAt(index)) } }, enabled = !draft.saving && index < draft.selected.lastIndex,
                        modifier = Modifier.semantics { contentDescription = "下移见闻$id" }) { Text("下移") }
                    TextButton(onClick = { draft.selected = draft.selected - id }, enabled = !draft.saving,
                        modifier = Modifier.semantics { contentDescription = "从专题移除见闻$id" }) { Text("移出专题") }
                }
            }
        }
        item(key = "candidates-title") { Text("选择现有见闻", color = p.content, fontSize = 15.sp) }
        if (sources.isEmpty()) item(key = "no-sources") { Text("窗外还没有见闻，可以返回后先刷新或查阅。", color = p.contentSecondary, fontSize = 14.sp) }
        items(sources, key = { "candidate:${it.id}" }) { source -> WorldPanel {
            Row(Modifier.fillMaxWidth().toggleable(value = source.id in draft.selected, enabled = !draft.saving, role = Role.Checkbox,
                onValueChange = { checked -> draft.selected = if (checked) (draft.selected + source.id).distinct() else draft.selected - source.id })
                .semantics { contentDescription = "选择专题见闻：${source.title}" }) {
                Checkbox(checked = source.id in draft.selected, onCheckedChange = null, enabled = !draft.saving)
                Column(Modifier.weight(1f)) {
                    Text(source.title, color = p.content, fontSize = 14.sp, lineHeight = 23.sp)
                    Text(source.source, color = p.contentSecondary, fontSize = 12.sp)
                }
            }
        } }
    }
}
