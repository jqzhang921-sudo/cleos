package com.cleo.cleos.ui.world

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.ui.LocalPageShown
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.GlassSearchField
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

/** View-only selection. Private album records are deliberately not a notebook input. */
internal object WorldNotebookEntries {
    val filters = linkedMapOf("all" to "全部", "writing" to "随笔", "draft" to "草稿", "story" to "故事", "reading" to "阅读", "picture" to "看图", "questions" to "问题")
    data class Entry(val key: String, val article: Long, val at: Long, val content: String, val question: String, val quote: String,
        val source: WorldArticleEntity?, val note: WorldNoteEntity?, val look: WorldPictureLookEntity?, val writing: WorldWritingEntity? = null)
    fun all(owner: Long?, notes: List<WorldNoteEntity>, looks: List<WorldPictureLookEntity>, articles: List<WorldArticleEntity>, writings: List<WorldWritingEntity> = emptyList()): List<Entry> {
        if (owner == null) return emptyList()
        val sources = articles.filter { it.companionId == owner }.associateBy { it.id }
        return (notes.filter { it.companionId == owner }.map { Entry("note:${it.id}", it.articleId, it.createdAt, it.content, it.question, it.quote, sources[it.articleId], it, null) } +
            looks.filter { it.companionId == owner }.map { Entry("look:${it.id}", it.articleId, it.createdAt, it.content, it.question, "", sources[it.articleId], null, it) } +
            writings.filter { it.companionId == owner }.map { Entry("writing:${it.id}", 0, it.createdAt, it.content, "", "", null, null, null, it) })
            .sortedWith(compareByDescending<Entry> { it.at }.thenBy { it.key })
    }
    fun select(entries: List<Entry>, query: String, filter: String): List<Entry> {
        val term = query.trim()
        return entries.filter { e ->
            (when (filter) { "writing" -> e.writing != null; "draft", "story" -> e.writing?.kind == filter; "reading" -> e.note != null; "picture" -> e.look != null; "questions" -> e.question.isNotBlank(); else -> true }) &&
                (term.isBlank() || listOf(e.content, e.question, e.quote, e.source?.title.orEmpty(), e.writing?.let { it.title + " 自由随笔 " + com.cleo.cleos.data.WorldWritingData.kinds[it.kind] } ?: if (e.look != null) "看图记录" else "阅读笔记").any { it.contains(term, true) })
        }
    }
}

@Composable
internal fun WorldNotebook(writings: List<WorldWritingEntity>, writingOptions: Boolean, onWritingOptions: (Boolean) -> Unit, notes: List<WorldNoteEntity>, looks: List<WorldPictureLookEntity>, ta: CompanionEntity?, articles: List<WorldArticleEntity>, top: Dp, bottom: Dp,
    query: String, onQuery: (String) -> Unit, filter: String, onFilter: (String) -> Unit,
    onSource: (Long, Int) -> Unit, onLookup: (String) -> Unit, onJournal: (Long) -> Unit,
    navigation: WorldNavigationModel, ready: Boolean,
) {
    val c = appContainer(); val p = LocalGlassPalette.current; val scope = rememberCoroutineScope()
    var deletingWriting by remember(ta?.id) { mutableStateOf<WorldWritingEntity?>(null) }
    var deleting by remember(ta?.id) { mutableStateOf<WorldNoteEntity?>(null) }
    var deletingLook by remember(ta?.id) { mutableStateOf<WorldPictureLookEntity?>(null) }
    var error by remember(ta?.id) { mutableStateOf<String?>(null) }
    val entries = remember(notes, looks, articles, ta?.id, writings) { WorldNotebookEntries.all(ta?.id, notes, looks, articles, writings) }
    val serialRows by key(ta?.id, ta?.createdAt) { remember { c.db.worldSerials().observe(ta?.id ?: 0L) }.collectAsStateWithLifecycle<List<WorldSerialEntity>?>(null) }
    val chapterRows by key(ta?.id, ta?.createdAt) { remember { c.db.worldSerials().observeChapters(ta?.id ?: 0L) }.collectAsStateWithLifecycle<List<WorldSerialChapterEntity>?>(null) }
    val libraryReady = ready && serialRows != null && chapterRows != null
    val books = remember(entries, serialRows, chapterRows, ta?.id) { NotebookSerialViews.books(ta?.id, serialRows.orEmpty(), chapterRows.orEmpty(), entries) }
    val membership = remember(books) { books.flatMap { b -> b.entries.map { it.key to b.book.id } }.toMap() }
    val bookId = navigation.notebookBook.value
    val book = books.firstOrNull { it.book.id == bookId }
    val scopeEntries = remember(entries, bookId, books, membership) { if (bookId > 0) book?.entries.orEmpty() else entries.filter { it.key !in membership } }
    val filtered = remember(scopeEntries, query, filter) { WorldNotebookEntries.select(scopeEntries, query, filter) }
    val matchingBooks = remember(entries, serialRows, chapterRows, ta?.id, query, filter) { NotebookSerialViews.books(ta?.id, serialRows.orEmpty(), chapterRows.orEmpty(), entries, query, filter) }
    val zone = ZoneId.systemDefault()
    val days = remember(filtered, zone) { filtered.map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() } }
    val mainList = worldPageScroll(navigation, "notebook", ta?.id, libraryReady)
    val list = if (bookId == 0L) mainList else key(bookId) { rememberLazyListState() }
    val context = LocalContext.current
    val anchors = remember(ta?.id, ta?.createdAt) { NotebookAnchorStore(context, ta?.id ?: 0L, ta?.createdAt ?: 0L) }
    var positionRevision by remember(ta?.id, ta?.createdAt) { mutableIntStateOf(0) }
    var newEntries by remember(ta?.id, ta?.createdAt) { mutableIntStateOf(0) }
    var writerShown by rememberSaveable(ta?.id, ta?.createdAt) { mutableStateOf(writingOptions) }
    val selectedKey = navigation.notebookEntry.value
    val selected = filtered.firstOrNull { it.key == selectedKey }
    val shown = LocalPageShown.current
    val last = remember(positionRevision, entries, bookId, scopeEntries) {
        val candidate = if (bookId > 0) anchors.lastInBook(bookId) ?: anchors.last() else anchors.last()
        candidate?.takeIf { a -> (if (bookId > 0) scopeEntries else entries).any { it.key == a.key && it.at == a.at } }
    }
    fun editBook(view: NotebookBookView?, added: WorldWritingEntity? = null) {
        navigation.notebookSerialEditor.value = NotebookSerialDraft(view?.book, view?.entries?.mapNotNull { it.writing }.orEmpty(), added)
    }
    val organize: (WorldWritingEntity) -> Unit = { row ->
        val existing = membership["writing:${row.id}"]?.let { id -> books.firstOrNull { it.book.id == id } }
        if (existing != null) editBook(existing) else navigation.notebookSerialPick.value = row
    }
    LaunchedEffect(libraryReady, bookId, books) {
        if (libraryReady && bookId > 0 && book == null && ta?.id == navigation.selectedId.value) {
            val actual = c.db.worldSerials().get(bookId)
            if (actual == null || actual.companionId != ta?.id) { navigation.notebookBook.value = 0; navigation.notebookEntry.value = "" }
        }
    }
    BackHandler(enabled = shown && selectedKey.isNotBlank()) { navigation.notebookEntry.value = "" }
    LaunchedEffect(libraryReady, entries, ta?.id, selectedKey.isBlank(), bookId) {
        if (libraryReady && ta?.id == navigation.selectedId.value && selectedKey.isBlank() && bookId == 0L) {
            val previous = anchors.seenAt()
            newEntries = if (previous > 0) entries.count { it.at > previous } else 0
            anchors.observeNewest(entries.maxOfOrNull { it.at } ?: 0)
        }
    }
    LaunchedEffect(libraryReady, entries, ta?.id, scopeEntries, bookId) {
        if (libraryReady && ta?.id == navigation.selectedId.value && selectedKey.isNotBlank() && scopeEntries.none { it.key == selectedKey }) {
            if (entries.none { it.key == selectedKey }) anchors.forget(selectedKey)
            navigation.notebookEntry.value = ""
        }
    }
    val requestDelete: (WorldNotebookEntries.Entry) -> Unit = { n ->
        error = null
        if (n.writing != null) deletingWriting = n.writing else if (n.look != null) deletingLook = n.look else deleting = n.note
    }
    if (selected != null && libraryReady) {
        NotebookScrollReader(filtered, selectedKey, anchors, top, bottom, error,
            onAnchor = { anchor ->
                if (ta?.id == navigation.selectedId.value) {
                    anchors.save(anchor, bookId); navigation.notebookEntry.value = anchor.key; positionRevision++
                }
            }, onDirectory = { navigation.notebookEntry.value = "" }, onDelete = requestDelete,
            onSource = onSource, onLookup = onLookup, onJournal = onJournal, serialTitle = book?.book?.title, onOrganize = organize)
    } else LazyColumn(Modifier.fillMaxSize().imePadding().fadeUnderTopBar(top, bottom), state = list,
        contentPadding = worldPadding(top, bottom), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "intro") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book?.book?.title ?: if (bookId > 0) "正在打开连载…" else "翻开随手本", color = p.content, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                    Text(if (bookId > 0) "${scopeEntries.size} 章 · 按你整理的顺序翻阅" else "${entries.size} 篇 · ${books.size} 本连载", color = p.contentSecondary, fontSize = 12.sp)
                }
                if (bookId > 0) TextButton(enabled = book != null, onClick = { editBook(book) }) { Text("整理") }
                else TextButton(onClick = { writerShown = !writerShown }) { Text(if (writerShown) "收起写作" else "写作") }
            }
        }
        if (bookId == 0L && writings.isNotEmpty()) item(key = "new-serial") {
            TextButton(enabled = libraryReady, onClick = { editBook(null) }) { Text("整理成册") }
        }
        if (bookId > 0) item(key = "all-notebook") { TextButton(onClick = {
            navigation.notebookBook.value = 0; navigation.notebookEntry.value = ""; onQuery(""); onFilter("all")
        }) { Text("全部随手本") } }
        if (bookId == 0L && writerShown) item(key = "writing") { WorldWritingPanel(ta, writingOptions, onWritingOptions) }
        if (last != null) item(key = "resume") {
            val title = entries.first { it.key == last.key }.notebookTitle()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { onQuery(""); onFilter("all"); navigation.notebookBook.value = membership[last.key] ?: 0L; navigation.notebookEntry.value = last.key }) {
                    Text("继续阅读 · $title", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (bookId == 0L && newEntries > 0) Text("较上次翻阅新增 $newEntries 篇", color = p.contentSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp))
            }
        }
        item(key = "search") { GlassSearchField(query, { onQuery(it.take(100)) },
            placeholder = "翻找标题或内容", description = "搜索随手本", clearDescription = "清除笔记搜索") }
        item(key = "filters") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorldNotebookEntries.filters.forEach { (id, label) ->
                    WorldFilterChip(label, selected = filter == id || id == "all" && filter !in WorldNotebookEntries.filters,
                        onClick = { onFilter(id) })
                }
            }
            val found = filtered.size + if (bookId == 0L) matchingBooks.sumOf { it.matches.size } else 0
            Text(if (query.isBlank() && filter == "all") (if (bookId > 0) "章节目录 · ${scopeEntries.size} 章" else "${books.size} 本连载 · ${scopeEntries.size} 篇散页") else "找到 $found 篇",
                color = p.contentSecondary, fontSize = 12.sp)
        } }
        error?.let { item(key = "error") { Text(it, color = p.error) } }
        if (bookId == 0L) matchingBooks.forEach { b -> item(key = "serial:${b.book.id}") {
            GlassSurface(Modifier.fillMaxWidth().clickable {
                onQuery(""); onFilter("all"); navigation.notebookBook.value = b.book.id; navigation.notebookEntry.value = ""
            }.semantics { contentDescription = "打开连载：${b.book.title}" }, style = p.card,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("连载 · ${b.entries.size} 章", color = p.contentSecondary, fontSize = 11.sp)
                    Text(b.book.title, color = p.content, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(b.entries.take(2).mapIndexed { i, row -> "${i+1}. ${row.notebookTitle()}" }.joinToString("\n").ifBlank { "暂时没有章节，可打开后整理加入随笔。" },
                        color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("翻开目录  ›", color = p.accentContent, fontSize = 11.sp)
                }
            }
        } }
        if (filtered.isEmpty() && (bookId > 0 || matchingBooks.isEmpty())) item(key = "empty") { WorldPanel {
            Text(if (!libraryReady || bookId > 0 && book == null) "正在打开随手本…" else if (bookId > 0 && scopeEntries.isEmpty()) "这本还没有章节" else if (entries.isEmpty()) "本子里还没有笔记" else "没有找到这几句话", color = p.content, fontSize = 18.sp)
            Text(if (entries.isEmpty()) "TA 可以随手写写，或在阅读和看图后留下想法。相册的私人感想仍留在相册。" else "试试其他关键词，或切回全部记录。",
                color = p.contentSecondary, lineHeight = 23.sp)
        } }
        filtered.forEachIndexed { index, n ->
            val day = days[index]
            if (bookId == 0L && (index == 0 || day != days[index - 1])) item(key = "day:$day") {
                Text(day.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), color = p.contentSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            }
            item(key = n.key) {
                GlassSurface(Modifier.fillMaxWidth().clickable { if (bookId > 0) { onQuery(""); onFilter("all") }; navigation.notebookEntry.value = n.key }
                    .semantics { contentDescription = "打开篇目：${n.notebookTitle()}" }, style = p.card,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text((if (bookId > 0) "第 ${scopeEntries.indexOfFirst { it.key == n.key } + 1} 章 · " else "") + n.notebookKind() + " · " + Instant.ofEpochMilli(n.at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")),
                            color = p.contentSecondary, fontSize = 11.sp)
                        Text(n.notebookTitle(), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.content,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(n.content.ifBlank { n.question.ifBlank { n.quote } }, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (anchors.read(n.key) != null) "接着翻阅  ›" else "翻开这一篇  ›", fontSize = 11.sp, color = p.accentContent)
                    }
                }
            }
        }
    }
    if (ta != null) {
        navigation.notebookSerialPick.value?.let { row -> NotebookSerialPicker(row, books.map { it.book }, onPick = { target ->
            navigation.notebookSerialPick.value = null
            editBook(target?.let { t -> books.firstOrNull { it.book.id == t.id } }, row)
        }, onDismiss = { navigation.notebookSerialPick.value = null }) }
        navigation.notebookSerialEditor.value?.let { draft -> NotebookSerialEditor(ta, draft, writings, chapterRows.orEmpty(), onSaved = { id ->
            navigation.notebookSerialEditor.value = null
            if (navigation.selectedId.value == ta.id) { navigation.notebookEntry.value = ""; navigation.notebookBook.value = id; onQuery(""); onFilter("all"); positionRevision++ }
        }, onDismiss = { navigation.notebookSerialEditor.value = null }) }
    }
    deletingWriting?.let { w -> AlertDialog(onDismissRequest = { deletingWriting = null }, title = { Text("删除这篇随笔？") }, text = { Text(w.title.ifBlank { "这段随笔" } + "将从当前 TA 的随手本移除，阅读笔记、原文和照片保留。") },
        confirmButton = { TextButton(onClick = { deletingWriting = null; val target = ta; if (target != null) scope.launch {
            try { c.world.deleteWriting(target.id, target.createdAt, w); anchors.forget("writing:${w.id}"); error = null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "随笔或角色已经变化，请重新打开后再试。" }
        } }) { Text("删除", color = p.error) } }, dismissButton = { TextButton(onClick = { deletingWriting = null }) { Text("取消") } }) }
    deleting?.let { n -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条笔记？") }, text = { Text("原文章和阅读位置会保留。") },
        confirmButton = { TextButton(onClick = { deleting = null; scope.launch { c.world.deleteNote(n.companionId, n.id); anchors.forget("note:${n.id}") } }) { Text("删除", color = p.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    deletingLook?.let { n -> AlertDialog(onDismissRequest = { deletingLook = null }, title = { Text("删除这条看图记录？") }, text = { Text("这次看图的感想、问题和确认一起删除，原配图和摘要保留。") },
        confirmButton = { TextButton(onClick = { deletingLook = null; scope.launch {
            try { ta?.let { c.world.deletePictureLook(it.id, it.createdAt, n) }; anchors.forget("look:${n.id}"); error = null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "记录或角色已经变化，请重新打开后操作" }
        } }) { Text("删除", color = p.error) } }, dismissButton = { TextButton(onClick = { deletingLook = null }) { Text("取消") } }) }
}
