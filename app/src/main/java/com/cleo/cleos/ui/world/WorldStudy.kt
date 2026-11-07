package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.WorldWishes
import com.cleo.cleos.data.WorldShelfData
import com.cleo.cleos.data.WorldBookVolumes
import com.cleo.cleos.data.WorldShelfSummary
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import com.cleo.cleos.ui.LocalPageShown

@Composable
internal fun WorldStudy(
    ta: CompanionEntity?, articles: List<WorldArticleEntity>, state: WorldReadingStateEntity?, busy: Boolean,
    top: Dp, bottom: Dp, backdrop: Backdrop,
    tab: Int, onTab: (Int) -> Unit, category: String, onCategory: (String) -> Unit,
    showReadingOptions: Boolean, onReadingOptions: (Boolean) -> Unit,
    onAdd: () -> Unit, onOpen: (Long, Int) -> Unit,
    navigation: WorldNavigationModel, ready: Boolean,
) {
    val c = appContainer(); val p = LocalGlassPalette.current
    val wishRows by remember(ta?.id) { ta?.let { c.db.world().observeWishes(it.id) } ?: flowOf(emptyList()) }.collectAsStateWithLifecycle<List<WorldWishEntity>?>(null)
    val wishes = wishRows.orEmpty()
    val now by produceState(System.currentTimeMillis(), ta?.id) { while (true) { delay(60_000); value = System.currentTimeMillis() } }
    Column(Modifier.fillMaxSize().padding(top = top)) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = p.content) {
            Tab(selected = tab == 0, onClick = { onTab(0) }, text = { Text("书架") })
            Tab(selected = tab == 1, onClick = { onTab(1) }, text = { Text("想读的" + wishes.count { it.status == "pending" }.takeIf { it > 0 }?.let { " · $it" }.orEmpty()) })
            Tab(selected = tab == 2, onClick = { onTab(2) }, text = { Text("我的书签") })
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) WorldShelf(ta, articles, state, busy, 0.dp, bottom, backdrop, onAdd, onOpen, WorldWishes.mayAsk(wishes, now), category, onCategory, showReadingOptions, onReadingOptions, navigation, ready)
            else if (tab == 1) WorldWishlist(ta, wishes, articles, bottom, onAdd, onOpen, navigation, ready && wishRows != null)
            else WorldBookmarks(ta, articles, bottom, onOpen, navigation, ready)
        }
    }
}

@Composable
private fun WorldShelf(
    ta: CompanionEntity?, articles: List<WorldArticleEntity>, state: WorldReadingStateEntity?, busy: Boolean,
    top: Dp, bottom: Dp, backdrop: Backdrop, onAdd: () -> Unit, onOpen: (Long, Int) -> Unit, mayWish: Boolean,
    category: String, onCategory: (String) -> Unit, showReadingOptions: Boolean, onReadingOptions: (Boolean) -> Unit,
    navigation: WorldNavigationModel, ready: Boolean,
) {
    val c = appContainer(); val p = LocalGlassPalette.current
    val schedule by remember(ta?.id) { ta?.let { c.db.world().observeSchedule(it.id) } ?: flowOf(null) }.collectAsStateWithLifecycle(null)
    val metadataRows by remember(ta?.id) { ta?.let { c.db.world().observeShelf(it.id) } ?: flowOf(emptyList()) }.collectAsStateWithLifecycle<List<WorldShelfEntity>?>(null)
    val metadata = metadataRows.orEmpty()
    val byId = remember(metadata) { metadata.associateBy { it.articleId } }
    val summary = remember(ta?.id, articles) { WorldShelfSummary.from(ta?.id, articles) }
    val books = summary.books
    val shown = remember(books, byId, category) { books.filter { book -> book.parts.any {
            category == "all" || byId[it.id]?.kind == category || category == "unfiled" && it.id !in byId
        } }.sortedWith(compareBy<WorldBookVolumes.Book> { if (category == "magazine") byId[it.id]?.series.orEmpty() else "" }
            .thenComparator { a, b -> if (category == "magazine") WorldShelfData.compareIssues(byId[a.id]?.issue.orEmpty(), byId[b.id]?.issue.orEmpty()) else 0 }.thenByDescending { it.createdAt }) }
    var changingSchedule by remember(ta?.id) { mutableStateOf(false) }
    var scheduleError by remember(ta?.id) { mutableStateOf<String?>(null) }
    val recent = summary.recent
    val used = state?.takeIf { it.day == LocalDate.now().toEpochDay() }?.let { (it.attempts - if (it.token != null) 1 else 0).coerceAtLeast(0) } ?: 0
    val list = worldPageScroll(navigation, "shelf", ta?.id, ready && metadataRows != null)
    LazyColumn(Modifier.fillMaxSize().fadeUnderTopBar(top, bottom), state = list, contentPadding = worldPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "reading") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("留一点时间给阅读", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = p.content, modifier = Modifier.weight(1f))
                    Text("${books.size} 份读物", color = p.contentSecondary, fontSize = 12.sp)
                }
                Text("让 TA 自己挑一份，接着上次的地方慢慢读。", color = p.contentSecondary, fontSize = 14.sp, lineHeight = 23.sp)
                Text("阅读会把标题、正文片段、相关想读理由和这本书的旧笔记发送给这位 TA 的模型。开启「记忆」时也会带上少量相关读书记忆。今日已用 $used / ${WorldReading.DAILY} 次，失败或停止退回次数。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(onClick = {
                        ta?.id?.let { if (busy) c.world.cancel(it) else c.world.start(it) }
                    }, backdrop = backdrop, enabled = ta != null && (busy || (articles.any { it.readOffset < it.content.length } || mayWish) && used < WorldReading.DAILY)) { Text(if (busy) "停止阅读" else if (!state?.failureCode.isNullOrBlank()) "重试阅读" else if (articles.none { it.readOffset < it.content.length }) "让 TA 逛逛书房" else "让 TA 读一会") }
                    TextButton(onClick = onAdd, enabled = ta != null) { Text("添加读物") }
                    TextButton(onClick = { onReadingOptions(!showReadingOptions) }) {
                        Text(if (showReadingOptions) "收起阅读安排" else "阅读安排 · ${if (schedule == null) "自主关闭" else "自主开启"}")
                    }
                }
                if (state?.space == "study" && state.status.isNotBlank()) Text(if (busy) "TA 正在书房里，未完成的片段不会提前计入进度" else state.status, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
            }
        }
        if (showReadingOptions) item(key = "reading-options") {
            WorldPanel {
                Text("与窗外共用每天 ${WorldReading.DAILY} 次机会；TA 可以记几句、放下，也可以在「想读的」留一本书架上没有的书。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("让 TA 自己读", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = p.content)
                        Text(if (schedule == null) "为这位 TA 开启自主阅读" else "已开启，不需要一直停留在书房", color = p.contentSecondary, fontSize = 13.sp)
                    }
                    Switch(checked = schedule != null, enabled = ta != null && !changingSchedule,
                        modifier = Modifier.semantics { contentDescription = "自主阅读" },
                        onCheckedChange = { enabled ->
                            val target = ta ?: return@Switch
                            changingSchedule = true; scheduleError = null
                            c.appScope.launch {
                                try { c.worldReading.setEnabled(target.id, target.createdAt, enabled) }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { scheduleError = "自主阅读设置没有完成，请稍后重试" }
                                finally { changingSchedule = false }
                            }
                        })
                }
                Text("开启后约 15 分钟给第一次机会，之后每 4 小时左右一次。与手动阅读共用每天 ${WorldReading.DAILY} 次机会，失败不扣次；TA 也可以不读，不会因此发消息。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                if (schedule != null) {
                    val next = remember(schedule?.nextAt) { Instant.ofEpochMilli(schedule!!.nextAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) }
                    Text("下次机会：$next 起，由系统安排；省电或网络可能让它晚一些。停止阅读只结束本次，关闭这里才取消后续机会。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                }
                Text("仅对这位 TA 生效；恢复备份后需重新开启。", color = p.contentSecondary, fontSize = 12.sp)
                scheduleError?.let { Text(it, color = p.error, fontSize = 13.sp) }
            }
        }
        if (recent != null) item(key = "recent") { WorldShelfRecent(recent.part, onOpen, recent.book.takeIf { it.grouped }) }
        if (articles.isEmpty()) item { WorldPanel { Text("书架还空着", color = p.content); Text("从手机里选一本 PDF、EPUB 或 TXT，也可以在「添加读物」中查找在线文库或粘贴文章。", color = p.contentSecondary) } }
        item(key = "shelf-heading") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("书架", color = p.content, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("${shown.size} 份", color = p.contentSecondary, fontSize = 12.sp)
        } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (linkedMapOf("all" to "全部") + WorldShelfData.kinds + mapOf("unfiled" to "未分类")).forEach { (id, text) ->
                WorldFilterChip(text, selected = category == id, onClick = { onCategory(id) })
            }
        } }
        if (category == "magazine") item { Text("按你填写的刊名和期号排列；分类不会改写原文或 TA 的阅读位置。", color = p.contentSecondary, fontSize = 12.sp) }
        if (articles.isNotEmpty() && shown.isEmpty()) item { WorldPanel { Text("这里还没有读物", color = p.content); Text("打开原文后，在「整理读物」中选择分类；杂志可以填写刊名与期号。", color = p.contentSecondary) } }
        items(shown, key = { it.id }) { book ->
            if (book.grouped) WorldShelfBook(book, onOpen)
            else WorldShelfEntry(book.parts.first(), byId[book.id], onOpen)
        }
    }
}

@Composable
internal fun WorldPasteForm(ta: CompanionEntity, top: Dp, bottom: Dp, backdrop: Backdrop, saved: (Long) -> Unit) {
    val c = appContainer(); val p = LocalGlassPalette.current; val scope = rememberCoroutineScope()
    var title by rememberSaveable(ta.id) { mutableStateOf("") }
    var source by rememberSaveable(ta.id) { mutableStateOf("") }
    var body by rememberSaveable(ta.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().imePadding().fadeUnderTopBar(top, bottom), contentPadding = worldPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("放进${ta.name.ifBlank { "TA" }}的书架", color = p.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        item { Text("适合短文章。来源可以写作者、刊物或链接；填写链接不会自动抓取网页。保存文章不会调用模型。", color = p.contentSecondary, lineHeight = 23.sp) }
        item { OutlinedTextField(title, { if (it.length <= 120) title = it }, Modifier.fillMaxWidth(), label = { Text("文章标题") }, enabled = !saving, singleLine = true) }
        item { OutlinedTextField(source, { if (it.length <= 500) source = it }, Modifier.fillMaxWidth(), label = { Text("作者 / 来源（选填）") }, enabled = !saving) }
        item { OutlinedTextField(body, { if (it.length <= WorldReading.MAX_TEXT) body = it }, Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp), label = { Text("文章正文") }, enabled = !saving, maxLines = 16, supportingText = { Text("${body.length} / ${WorldReading.MAX_TEXT}") }) }
        item {
            GlassButton(onClick = {
                saving = true; error = null
                scope.launch {
                    try { saved(c.world.add(ta.id, ta.createdAt, title, source, body)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "没有保存，请重试" }
                    finally { saving = false }
                }
            }, backdrop = backdrop, enabled = !saving && title.isNotBlank() && body.isNotBlank()) { Text(if (saving) "正在放入…" else "放入书架") }
            error?.let { Text(it, color = p.error) }
        }
    }
}

@Composable
internal fun WorldArticlePage(article: WorldArticleEntity?, ta: CompanionEntity?, start: Int, top: Dp, bottom: Dp,
    book: WorldBookVolumes.Book?, onOpen: (Long, Int) -> Unit, onJournal: (Long) -> Unit, navigation: WorldNavigationModel, onDeleted: () -> Unit) {
    val p = LocalGlassPalette.current; val c = appContainer(); val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val outside = article?.space == "outside"
    var linkError by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showImportInfo by rememberSaveable(article?.id) { mutableStateOf(false) }
    var raw by rememberSaveable(article?.id) { mutableStateOf(false) }
    var pendingOffset by remember { mutableStateOf<Int?>(null) }
    val sections = remember(article?.id, article?.fingerprint) { article?.let { WorldTextLayout.sections(it.content) }.orEmpty() }
    val pieces = remember(article?.id, article?.fingerprint, raw) { article?.let { WorldTextLayout.parts(it.content, sections, raw) }.orEmpty() }
    val list = rememberLazyListState()
    val shown = LocalPageShown.current
    LaunchedEffect(article?.id, article?.fingerprint, start, shown) {
        val a = article ?: return@LaunchedEffect
        if (!shown || ta?.id != navigation.selectedId.value) return@LaunchedEffect
        navigation.prepareSourceScroll(a.id, a.fingerprint, start)
        val anchor = navigation.sourceScroll()
        if (anchor != null) list.scrollToItem(anchor.first, anchor.second)
        else list.scrollToItem(if (start <= 0 || pieces.isEmpty()) 0 else 1 + WorldTextLayout.indexAt(pieces, start))
        snapshotFlow { if (pendingOffset == null) Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.layoutInfo.totalItemsCount) else null }
            .collect { position ->
                if (position != null && position.third > 0 && navigation.selectedId.value == ta.id)
                    navigation.saveSourceScroll(position.first, position.second)
            }
    }
    LaunchedEffect(article?.id, article?.fingerprint, raw, pendingOffset) {
        pendingOffset?.let { offset ->
            list.scrollToItem(if (offset <= 0 || pieces.isEmpty()) 0 else 1 + WorldTextLayout.indexAt(pieces, offset))
            pendingOffset = null
        }
    }
    fun displayOriginal(value: Boolean) {
        if (raw != value) {
            pendingOffset = pieces.getOrNull(list.firstVisibleItemIndex - 1)?.from ?: 0
            raw = value
        }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding().fadeUnderTopBar(top, bottom), state = list, contentPadding = worldPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            WorldPanel {
                Text(book?.title ?: article?.title ?: "读物已不在书架上", color = p.content, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                article?.let { a ->
                    if (book?.grouped == true) {
                        val index = book.parts.indexOfFirst { it.id == a.id }
                        Text("第 ${index + 1} 卷 · 共 ${book.parts.size} 卷", color = p.contentSecondary, fontSize = 13.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            book.parts.getOrNull(index - 1)?.let { previous ->
                                TextButton(onClick = { onOpen(previous.id, previous.readOffset) }) { Text("上一卷") }
                            }
                            book.parts.getOrNull(index + 1)?.let { next ->
                                TextButton(onClick = { onOpen(next.id, next.readOffset) }) { Text("下一卷") }
                            }
                        }
                    }
                    if (outside) {
                        TextButton(onClick = { onJournal(a.id) }) { Text("打开图文见闻") }
                        Text("${if (a.searchQuery.isEmpty()) "订阅摘要" else "百科导言摘要"} · TA 的摘要阅读不包含网页全文或配图", color = p.contentSecondary, fontSize = 13.sp)
                        if (a.searchQuery.isNotEmpty()) Text("最初检索词：${a.searchQuery}", color = p.contentSecondary, fontSize = 12.sp)
                        else Text("来源发布时间：${a.published.ifBlank { "未提供" }}", color = p.contentSecondary, fontSize = 12.sp)
                        if (a.searchQuery.isNotEmpty()) {
                            Text("维基百科导言经纯文字提取与截短，按 CC BY-SA 4.0 等条款提供；作者、修订记录及特殊署名见来源页面。", color = p.contentSecondary, fontSize = 12.sp)
                            SelectionContainer { Text("内容许可：https://creativecommons.org/licenses/by-sa/4.0/", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp) }
                        }
                        Text("获取于 " + Instant.ofEpochMilli(a.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")), color = p.contentSecondary, fontSize = 12.sp)
                        SelectionContainer { Text(a.originUrl, color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp) }
                        TextButton(onClick = { linkError = runCatching { check(com.cleo.cleos.data.WorldOutside.validUrl(a.originUrl)); uri.openUri(a.originUrl) }.isFailure }) { Text("在浏览器打开来源") }
                        if (linkError) Text("没有打开来源，可以复制链接后使用浏览器查看", color = p.error, fontSize = 12.sp)
                    }
                    val imported = a.source.startsWith("EPUB 提取文字") || a.source.startsWith("PDF 提取文字") || a.source.startsWith("TXT / Markdown · ")
                    val onlineReference = if (!outside) com.cleo.cleos.data.WorldOnlineText.reference(a.source) else null
                    Text((if (onlineReference != null) "维基文库 · 页面文字" else if (imported) a.source.substringBefore(" · ") else a.source).ifBlank { "用户提供" }, color = p.contentSecondary,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    onlineReference?.let { link ->
                        Text("文库单页文字 · CC BY-SA 4.0 等条款；作品声明、作者与修订历史见固定来源。不代表已取得整本书。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                        TextButton(onClick = { linkError = runCatching { uri.openUri(link) }.isFailure }) { Text("打开固定来源") }
                        if (linkError) Text("没有打开来源，请稍后重试", color = p.error, fontSize = 12.sp)
                    }
                    Text(if (outside) (if (a.readOffset == a.content.length) "TA 已看过这份摘要" else "TA 尚未看完这份摘要") else "TA 已读 ${a.readOffset} / ${a.content.length} 字符", color = p.contentSecondary, fontSize = 12.sp)
                    Row {
                        if (sections.isNotEmpty()) TextButton(onClick = { showContents = true }) { Text("阅读目录") }
                        TextButton(onClick = { pendingOffset = a.readOffset }) { Text("回到 TA 的位置") }
                    }
                    if (!outside && ta?.id == a.companionId) {
                        WorldStudyReadAction(ta, a)
                        WorldOrganize(ta, a)
                    }
                    if (sections.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WorldFilterChip("整理显示", selected = !raw, onClick = { displayOriginal(false) })
                            WorldFilterChip("原始文字", selected = raw, onClick = { displayOriginal(true) })
                        }
                        Text("收紧多余空白，保留书内目录；显示方式不会改写原文、阅读位置或笔记引用。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                    Row {
                        TextButton(onClick = { showImportInfo = !showImportInfo }) { Text(if (showImportInfo) "收起导入信息" else "导入信息") }
                        TextButton(onClick = { confirm = true }) { Text(if (outside) "移除见闻" else "移出书架", color = p.error) }
                    }
                    if (showImportInfo) {
                        SelectionContainer { Text(a.source.ifBlank { "用户提供" }, color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp) }
                        Text("正文版本 ${a.fingerprint.take(8)} · ${a.content.length} 字符。显示排版不更改这份原文。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                    }
                    if (outside && ta?.id == a.companionId && com.cleo.cleos.data.WorldTopicSearch.validUrl(a.originUrl)) WorldPicturePanel(ta, a)
                }
            }
        }
        items(pieces, key = { it.from }) { part ->
            if (part.heading) Text(part.text, color = p.contentSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
            else WorldPanel {
                if (raw || sections.isEmpty()) Text("第 ${part.from + 1}–${part.to} 字符", fontSize = 11.sp, color = p.contentSecondary)
                SelectionContainer { Text(part.text, fontSize = 16.sp, lineHeight = 28.sp, color = p.content) }
                if (!outside && article != null && ta?.id == article.companionId) WorldBookmarkAction(ta, article, part.from)
            }
        }
    }
    if (showContents) AlertDialog(onDismissRequest = { showContents = false }, title = { Text("阅读目录") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("按分段开头预览跳转，书内目录仍保留在正文中。")
            LazyColumn(Modifier.heightIn(max = 360.dp)) { items(sections, key = { it.from }) { section ->
                TextButton(onClick = { pendingOffset = section.from; showContents = false }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(section.preview.ifBlank { section.label }, color = p.content)
                        if (section.preview.isNotBlank()) Text(section.label, color = p.contentSecondary, fontSize = 12.sp)
                    }
                }
            } }
        } }, confirmButton = { TextButton(onClick = { showContents = false }) { Text("关闭") } })
    if (confirm && article != null) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(if (outside) "移除这条见闻？" else "移出这份读物？") },
        text = { Text(if (outside) "摘要、配图和关联的随手本笔记会一起删除，无法恢复。" else "读物、阅读位置、书签、整理信息和关联的随手本笔记会一起删除，无法恢复。") },
        confirmButton = { TextButton(onClick = { confirm = false; scope.launch { c.world.deleteArticle(article.companionId, article.id); onDeleted() } }) { Text("删除", color = p.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

internal fun worldPadding(top: Dp, bottom: Dp) = PaddingValues(start = 20.dp, end = 20.dp, top = top + 22.dp, bottom = bottom + 24.dp)

@Composable
internal fun WorldPanel(content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), shape = GlassShape.Rounded(28.dp), contentPadding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
