package com.cleo.cleos.ui.world

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.WorldOnlineText
import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.LocalPageShown
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.*

/** Query is saved, fetched text is not put into Binder state. Leaving cancels requests; preview never saves. */
@Composable
internal fun WorldOnlineBooks(ta: CompanionEntity, top: Dp, bottom: Dp, backdrop: Backdrop,
    query: String, onQuery: (String) -> Unit, fullText: Boolean, onFullText: (Boolean) -> Unit,
    onExit: () -> Unit, saved: (Long) -> Unit,
) {
    val c = appContainer(); val p = LocalGlassPalette.current; val scope = rememberCoroutineScope()
    val client = remember { WorldOnlineText() }; val uri = LocalUriHandler.current
    val keyboard = LocalSoftwareKeyboardController.current; val shown = LocalPageShown.current
    val contentBottom = if (WindowInsets.ime.getBottom(LocalDensity.current) > 0) 0.dp else bottom
    var results by remember(ta.id) { mutableStateOf<List<WorldOnlineText.Page>?>(null) }
    var preview by remember(ta.id) { mutableStateOf<WorldOnlineText.Preview?>(null) }
    var job by remember(ta.id) { mutableStateOf<Job?>(null) }
    var saving by remember(ta.id) { mutableStateOf(false) }
    var message by remember(ta.id) { mutableStateOf<String?>(null) }
    val loading = job != null
    val list = rememberLazyListState()
    val selected = preview
    LaunchedEffect(selected?.page?.revision) { list.scrollToItem(0) }
    LaunchedEffect(shown) { if (!shown) job?.cancel() }
    BackHandler(shown) { when { saving -> Unit; loading -> job?.cancel(); preview != null -> preview = null; else -> onExit() } }
    fun load(searching: Boolean, block: suspend () -> Unit) {
        if (loading || saving || !shown) return
        keyboard?.hide(); message = null
        job = scope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            catch (e: CancellationException) { message = "已停止，本次没有加入书架"; throw e }
            catch (e: Exception) { message = WorldOnlineText.failureMessage(e, searching) }
            finally { job = null }
        }.also { it.start() }
    }
    fun open(url: String) { if (runCatching { uri.openUri(url) }.isFailure) message = "没有打开来源，请稍后重试" }
    fun search() { results = null; preview = null; load(searching = true) { results = client.search(query, fullText) } }
    val validQuery = runCatching { WorldOnlineText.searchTerm(query).also { if (!fullText) WorldOnlineText.titleUrl(it) } }.isSuccess
    LazyColumn(Modifier.fillMaxSize().imePadding().fadeUnderTopBar(top, contentBottom), state = list, contentPadding = worldPadding(top, contentBottom),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(if (selected == null) "从在线文库找读物" else "确认这份读物", color = p.content, fontSize = 21.sp, fontWeight = FontWeight.SemiBold) }
        if (selected == null) item { WorldPanel {
            Text("中文维基文库", color = p.content, fontSize = 18.sp)
            Text("先按书名或篇章标题查找，核对后再放进${ta.name.ifBlank { "TA" }}的书架。这里只搜索中文维基文库，不是全网书库，不自动拼接整本书或下载图片。", color = p.contentSecondary, fontSize = 14.sp, lineHeight = 23.sp)
            Text("点击查找，只把检索词发到 zh.wikisource.org；取回与预览不调用模型，也不扣阅读机会。加入书架后，TA 开始阅读时才会把标题、来源和本次片段发到其模型接口。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in listOf(false to "按标题", true to "全文线索")) FilterChip(selected = fullText == value,
                    onClick = { onFullText(value); results = null; preview = null; message = null },
                    enabled = !loading && !saving, label = { Text(label) })
            }
            Text(if (fullText) "全文线索会匹配正文中的提及，不代表找到了同名作品。可用作者或关键词继续找线索。" else "先查完整标题，支持简繁标题转换；没有同名页面时，再查标题中含这些字的篇章。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
            OutlinedTextField(query, { if (it.length <= 100) { onQuery(it); results = null; preview = null; message = null } }, Modifier.fillMaxWidth(),
                label = { Text(if (fullText) "作者 / 关键词" else "书名 / 篇章") }, enabled = !loading && !saving, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (validQuery) search() }))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(onClick = ::search, backdrop = backdrop,
                    enabled = !loading && !saving && validQuery) { Text("查找读物") }
                TextButton(onClick = onExit, enabled = !saving) { Text("改用文件") }
            }
        } }
        if (loading) item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(22.dp)); Text("正在取回文库资料…", color = p.content) }
            TextButton(onClick = { job?.cancel() }) { Text("停止取回") }
        }
        message?.let { text -> item {
            Text(text, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
            if (!loading && !saving) TextButton(onClick = { open(WorldOnlineText.HOME) }) { Text("用浏览器检查文库") }
        } }
        if (selected == null) {
            if (results?.isEmpty() == true) item { WorldPanel {
                Text(if (fullText) "这次没有找到全文线索" else "没有找到匹配标题的页面", color = p.content)
                Text(if (fullText) "可以换几个关键词，或从文件导入；没有结果不代表作品不存在。" else "不会用正文里偶然提到这些字的文章充当目标读物。可以换繁体名或其他译名、手动选择全文线索，或用 EPUB / PDF 导入。", color = p.contentSecondary, lineHeight = 22.sp)
            } }
            items(results.orEmpty(), key = { it.id }) { page -> WorldPanel {
                Text(page.title, color = p.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("${if (fullText) "全文线索 · 未确认是目标作品" else "标题匹配"} · 版本 ${page.revision} · 尚未加入书架", color = p.contentSecondary, fontSize = 12.sp)
                Text("可能是单篇、章节或目录，请先预览核对。", color = p.contentSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { load(searching = false) { preview = client.fetch(page) } }, enabled = !loading && !saving) { Text("取回并预览") }
                    TextButton(onClick = { open(page.url) }, enabled = !loading && !saving) { Text("查看来源") }
                }
            } }
        } else {
            item { WorldPanel {
                Text(selected.page.title, color = p.content, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("已取回 ${selected.content.length} 字符 · 仅此页面文字，尚未保存", color = p.contentSecondary, fontSize = 13.sp)
                Text("文字按 CC BY-SA 4.0 等条款提供；原作者、编辑历史和作品附加声明见固定来源。保留页面内版权说明；不包含原排版与图片。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                TextButton(onClick = { open(selected.page.url) }, enabled = !saving) { Text("打开固定来源") }
            } }
            val end = minOf(2_400, selected.content.length).let { if (WorldReading.boundary(selected.content, it)) it else it - 1 }
            val pieces = WorldTextLayout.parts(selected.content.take(end), emptyList(), true)
            items(pieces, key = { "preview:${it.from}" }) { part ->
                Text(part.text, modifier = Modifier.padding(horizontal = 8.dp), color = p.content, fontSize = 15.sp, lineHeight = 25.sp)
            }
            if (end < selected.content.length) item { Text("预览仅显示开头，加入时保留本页全部提取文字。", color = p.contentSecondary, fontSize = 12.sp) }
            item { WorldPanel {
                GlassButton(onClick = {
                    if (!saving) {
                        saving = true; message = null
                        scope.launch {
                            try { saved(c.world.add(ta.id, ta.createdAt, selected.page.title, selected.page.source, selected.content)) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { message = "没有加入书架，请确认这位 TA 仍在，或先整理已满的书架后重试" }
                            finally { saving = false }
                        }
                    }
                }, backdrop = backdrop, enabled = !saving && !loading && shown) { Text(if (saving) "正在加入…" else "确认加入书架") }
                TextButton(onClick = { preview = null }, enabled = !saving) { Text("返回结果") }
            } }
        }
        item { Text("每次最多 5 个候选；大页面请改用 PDF、EPUB 或 TXT 导入。来源不可用时保留已有读物；返回或停止取回不会自动保存。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp) }
    }
}
