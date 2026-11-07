package com.cleo.cleos.ui.world

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.WorldBookCatalog
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun WorldWishlist(ta: CompanionEntity?, wishes: List<WorldWishEntity>, articles: List<WorldArticleEntity>,
    bottom: Dp, onAdd: () -> Unit, onOpen: (Long, Int) -> Unit,
    navigation: WorldNavigationModel, ready: Boolean,
) {
    val c = appContainer(); val p = LocalGlassPalette.current; val scope = rememberCoroutineScope()
    val catalog = remember { WorldBookCatalog() }; val uri = LocalUriHandler.current
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lookup by remember { mutableStateOf<WorldWishEntity?>(null) }
    var results by remember { mutableStateOf<Pair<WorldWishEntity, List<WorldBookCatalog.Book>>?>(null) }
    var linking by remember { mutableStateOf<WorldWishEntity?>(null) }
    var ignoring by remember { mutableStateOf<WorldWishEntity?>(null) }
    val filtered = wishes.filter { when (filter) { 0 -> it.status == "pending"; 1 -> it.status == "later"; else -> it.status == "ignored" || it.status == "added" } }
    fun change(w: WorldWishEntity, status: String, article: Long? = null, book: WorldBookCatalog.Book? = null) {
        val target = ta ?: return
        if (working) return
        working = true; error = null
        scope.launch {
            try { c.world.handleWish(target.id, target.createdAt, w, status, article, book) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "这次操作没有完成，清单可能已变化，请重新打开后重试" }
            finally { working = false }
        }
    }
    val list = worldPageScroll(navigation, "wishes", ta?.id, ready)
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = worldPadding(0.dp, bottom), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            WorldPanel {
                Text("书架之外，还想读点什么", color = p.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("TA 在书房里可以留下一本想读的书和理由。每 24 小时最多提出一本，最多保留 10 条待添加或暂缓请求、100 条历史；你不需要立即回应，也不会自动购买、下载或发消息。", color = p.contentSecondary, fontSize = 13.sp, lineHeight = 22.sp)
                Text("书名和作者最初由模型提出，尚未核实。可以查询公开书目后自己确认；添加文件后，再关联书架里对应的读物。", color = p.contentSecondary, fontSize = 13.sp, lineHeight = 22.sp)
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("待添加", "以后再说", "已处理").forEachIndexed { i, label -> FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) }) }
            }
            if (working) Text("正在处理…", color = p.contentSecondary, fontSize = 13.sp)
            error?.let { Text(it, color = p.error, fontSize = 13.sp) }
        }
        if (filtered.isEmpty()) item {
            WorldPanel {
                Text(if (wishes.isEmpty()) "还没有想读的书" else "这里暂时没有请求", color = p.content)
                Text("回到书架，给 TA 一点阅读时间。TA 可以挑现有读物，也可以不提新书。", color = p.contentSecondary, lineHeight = 23.sp)
            }
        }
        items(filtered, key = { it.id }) { w ->
            val article = articles.firstOrNull { it.id == w.articleId }
            WorldPanel {
                Text(w.title, color = p.content, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(w.author.ifBlank { "作者未注明" } + " · " + Instant.ofEpochMilli(w.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日")), color = p.contentSecondary, fontSize = 12.sp)
                Text(w.reason, color = p.content, lineHeight = 24.sp)
                Text(when { w.status == "added" && article == null -> "原关联读物已移出书架"; w.status == "added" -> "已关联《${article?.title}》 · 不代表已经读过"; w.status == "later" -> "已暂缓，不会催促你添加"; w.status == "ignored" -> "已忽略，同名请求不会再次添加"; else -> "等待添加 · 尚未阅读正文" }, color = p.contentSecondary, fontSize = 12.sp)
                if (w.verifiedAt == null) {
                    Text("书目未核实", color = p.contentSecondary, fontSize = 12.sp)
                    TextButton(onClick = { lookup = w; error = null }, enabled = !working) { Text("核对书目") }
                } else {
                    Text("已选择公开书目：${w.catalogTitle}" + w.catalogAuthor.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), color = p.contentSecondary, fontSize = 12.sp)
                    TextButton(onClick = { runCatching { uri.openUri("https://openlibrary.org/works/${w.catalogKey}") }.onFailure { error = "暂时无法打开书目来源" } }) { Text("查看书目来源") }
                }
                if (w.status == "pending" || w.status == "added" && article == null) {
                    Row {
                        TextButton(onClick = onAdd, enabled = !working) { Text("添加读物") }
                        TextButton(onClick = { linking = w; error = null }, enabled = !working && articles.isNotEmpty()) { Text("关联书架") }
                    }
                }
                if (article != null && w.status == "added") TextButton(onClick = { onOpen(article.id, article.readOffset) }) { Text("查看关联读物") }
                Row {
                    if (w.status == "pending") TextButton(onClick = { change(w, "later") }, enabled = !working) { Text("以后再说") }
                    if (w.status == "later" || w.status == "ignored") TextButton(onClick = { change(w, "pending") }, enabled = !working) { Text("移回待添加") }
                    if (w.status != "ignored") TextButton(onClick = { ignoring = w }, enabled = !working) { Text("忽略") }
                }
            }
        }
    }
    ignoring?.let { w -> AlertDialog(onDismissRequest = { ignoring = null }, title = { Text("忽略这本想读的书？") },
        text = { Text("会保留在「已处理」中，避免重复提出。书架中的文件不受影响。") },
        confirmButton = { TextButton(onClick = { ignoring = null; change(w, "ignored") }) { Text("忽略") } },
        dismissButton = { TextButton(onClick = { ignoring = null }) { Text("取消") } }) }
    lookup?.let { w -> AlertDialog(onDismissRequest = { lookup = null }, title = { Text("查询公开书目") },
        text = { Text("将《${w.title}》和作者名发送到 Open Library 查询。不会发送角色设定、聊天、原文或密钥。结果需要你选择确认；找不到不代表这本书不存在。") },
        confirmButton = { TextButton(onClick = {
            lookup = null; working = true
            scope.launch {
                try { results = w to catalog.search(w.title, w.author) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "书目查询没有完成，仍保留为未核实；可以稍后再试或直接添加文件" }
                finally { working = false }
            }
        }) { Text("查询") } }, dismissButton = { TextButton(onClick = { lookup = null }) { Text("取消") } }) }
    results?.let { (w, books) -> AlertDialog(onDismissRequest = { results = null }, title = { Text("选择对应的书目") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (books.isEmpty()) "没有找到候选书目，书名仍标为未核实。你依然可以添加自己的文件。" else "只确认书目身份，不表示已取得或阅读正文。没有对应版本就取消。")
            LazyColumn(Modifier.heightIn(max = 300.dp)) { items(books, key = { it.key }) { book ->
                TextButton(onClick = { results = null; change(w, w.status, w.articleId, book) }) { Text(book.title + "\n" + book.author.ifBlank { "作者未注明" }) }
            } }
        } }, confirmButton = { TextButton(onClick = { results = null }) { Text("取消") } }) }
    linking?.let { w -> AlertDialog(onDismissRequest = { linking = null }, title = { Text("关联《${w.title}》") },
        text = { Column {
            Text("请选择书架中对应的正文文件。只有你确认后才会标记已添加，不会推进阅读位置。")
            LazyColumn(Modifier.heightIn(max = 320.dp)) { items(articles, key = { it.id }) { a ->
                TextButton(onClick = { linking = null; change(w, "added", a.id) }) { Text("关联《${a.title}》") }
            } }
        } }, confirmButton = { TextButton(onClick = { linking = null }) { Text("取消") } }) }
}
