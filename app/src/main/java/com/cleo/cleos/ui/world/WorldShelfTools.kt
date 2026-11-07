package com.cleo.cleos.ui.world

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.WorldShelfData
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.GlassSearchField
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
internal fun WorldOrganize(ta: CompanionEntity, article: WorldArticleEntity) {
    val c = appContainer(); val p = LocalGlassPalette.current; val scope = rememberCoroutineScope()
    val rows by remember(ta.id) { c.db.world().observeShelf(ta.id) }.collectAsStateWithLifecycle(emptyList())
    val row = rows.firstOrNull { it.articleId == article.id }
    var expanded by rememberSaveable(article.id) { mutableStateOf(false) }
    var kind by remember(row) { mutableStateOf(row?.kind ?: "book") }
    var series by remember(row) { mutableStateOf(row?.series.orEmpty()) }
    var issue by remember(row) { mutableStateOf(row?.issue.orEmpty()) }
    var saving by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起整理" else "整理读物") }
    if (!expanded) row?.let { Text(if (it.kind == "magazine") "${it.series} · ${it.issue.ifBlank { "期号未填" }}" else WorldShelfData.kinds.getValue(it.kind), color = p.contentSecondary, fontSize = 12.sp) }
    if (expanded) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorldShelfData.kinds.forEach { (id, label) -> WorldFilterChip(label, selected = kind == id, enabled = !saving, onClick = { kind = id }) }
        }
        if (kind == "magazine") {
            OutlinedTextField(series, { if (it.length <= 80) series = it }, Modifier.fillMaxWidth(), label = { Text("刊名") }, singleLine = true, enabled = !saving)
            OutlinedTextField(issue, { if (it.length <= 80) issue = it }, Modifier.fillMaxWidth(), label = { Text("期号 / 日期（选填）") }, singleLine = true, enabled = !saving)
        }
        Text("只整理书架信息，原文和 TA 的阅读进度保持。杂志按填写的刊名与期号排列，未核实出版信息，也不会自动订阅。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        TextButton(enabled = !saving && (kind != "magazine" || series.isNotBlank()), onClick = {
            val selected = kind; val name = if (selected == "magazine") series else ""; val number = if (selected == "magazine") issue else ""
            saving = true; error = null
            scope.launch {
                try { c.world.organize(ta.id, ta.createdAt, article.id, article.fingerprint, row, selected, name, number); expanded = false }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "整理没有保存，读物可能已经变化，请重开后再试" }
                finally { saving = false }
            }
        }) { Text(if (saving) "正在保存…" else "保存整理") }
        error?.let { Text(it, color = p.error, fontSize = 12.sp) }
    }
}

@Composable
internal fun WorldBookmarkAction(ta: CompanionEntity, article: WorldArticleEntity, offset: Int) {
    val c = appContainer(); val scope = rememberCoroutineScope(); val p = LocalGlassPalette.current
    var show by remember { mutableStateOf(false) }; var label by rememberSaveable(article.id, offset) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    TextButton(onClick = { show = true }) { Text("在这里留书签") }
    if (message.isNotEmpty()) Text(message, color = p.contentSecondary, fontSize = 12.sp)
    if (show) AlertDialog(onDismissRequest = { if (!saving) show = false }, title = { Text("留一枚书签") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("从这段开头回看，只记录你的位置，不改变 TA 的阅读进度。")
            Text(WorldShelfData.preview(article.content, offset), fontSize = 13.sp)
            OutlinedTextField(label, { if (it.length <= 80) label = it }, label = { Text("书签名称（选填）") }, singleLine = true, enabled = !saving)
            Text("保存在这位 TA 的书房，可随备份带走；名称不会发给模型。同位置再次保存会更新名称。", fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        saving = true
        scope.launch {
            try { c.world.bookmark(ta.id, ta.createdAt, article.id, article.fingerprint, offset, label); message = "已留在「我的书签」"; show = false }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "书签没有保存，读物可能已变化或书签已满"; show = false }
            finally { saving = false }
        }
    }) { Text(if (saving) "正在保存…" else "保存书签") } }, dismissButton = { TextButton(enabled = !saving, onClick = { show = false }) { Text("取消") } })
}

@Composable
internal fun WorldBookmarks(ta: CompanionEntity?, articles: List<WorldArticleEntity>, bottom: Dp, onOpen: (Long, Int) -> Unit, navigation: WorldNavigationModel, ready: Boolean) {
    val c = appContainer(); val p = LocalGlassPalette.current; val scope = rememberCoroutineScope()
    val markRows by remember(ta?.id) { ta?.let { c.db.world().observeBookmarks(it.id) } ?: flowOf(emptyList()) }.collectAsStateWithLifecycle<List<WorldBookmarkEntity>?>(null)
    val marks = markRows.orEmpty()
    var query by rememberSaveable(ta?.id) { mutableStateOf("") }; var deleting by remember { mutableStateOf<WorldBookmarkEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val byId = remember(articles) { articles.associateBy { it.id } }
    val filtered = marks.filter { query.isBlank() || (it.label + byId[it.articleId]?.title.orEmpty()).contains(query.trim(), true) }
    val list = worldPageScroll(navigation, "bookmarks", ta?.id, ready && markRows != null)
    LazyColumn(Modifier.fillMaxSize().fadeUnderTopBar(0.dp, bottom), state = list, contentPadding = worldPadding(0.dp, bottom), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("你的书签，留给下次回看", color = p.content, fontSize = 18.sp) }
        item { Text("这里和 TA 的阅读位置分开。打开原文，在想留下的段落下添加书签。", color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp) }
        item { GlassSearchField(query, { if (it.length <= 100) query = it }, placeholder = "翻找书签", description = "搜索书签", clearDescription = "清除书签搜索") }
        if (filtered.isEmpty()) item { WorldPanel { Text(if (marks.isEmpty()) "还没有书签" else "没有找到这枚书签", color = p.content) } }
        error?.let { item { Text(it, color = p.error, fontSize = 12.sp) } }
        items(filtered, key = { it.id }) { mark ->
            val a = byId[mark.articleId]
            WorldPanel {
                Text(mark.label.ifEmpty { a?.title ?: "书签" }, color = p.content, fontSize = 17.sp)
                if (mark.label.isNotEmpty()) Text(a?.title ?: "读物已移出", color = p.contentSecondary, fontSize = 12.sp)
                if (a != null && a.fingerprint == mark.fingerprint) Text(WorldShelfData.preview(a.content, mark.offset), color = p.contentSecondary, fontSize = 14.sp, lineHeight = 23.sp)
                Text("原文第 ${mark.offset + 1} 个字符起", color = p.contentSecondary, fontSize = 12.sp)
                Row {
                    TextButton(enabled = a != null && a.fingerprint == mark.fingerprint, onClick = { onOpen(mark.articleId, mark.offset) }) { Text("回到书签") }
                    TextButton(onClick = { deleting = mark }) { Text("移除书签", color = p.error) }
                }
            }
        }
    }
    deleting?.let { mark -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除这枚书签？") }, text = { Text("原文、TA 的阅读位置和笔记保持。") },
        confirmButton = { TextButton(onClick = { deleting = null; ta?.let { target -> scope.launch {
            try { c.world.deleteBookmark(target.id, target.createdAt, mark) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "书签已经变化，请重新打开后再试" }
        } } }) { Text("移除", color = p.error) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
