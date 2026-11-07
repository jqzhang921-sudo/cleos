package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleo.cleos.data.WorldTopicSearch
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import java.time.*
import java.time.format.DateTimeFormatter

/** A local, scrollable source + picture + public impressions. Scrolling never fetches or invokes a model. */
@Composable
internal fun WorldJournal(ta: CompanionEntity?, article: WorldArticleEntity?, notes: List<WorldNoteEntity>, looks: List<WorldPictureLookEntity>,
    top: Dp, bottom: Dp, backdrop: Backdrop, depth: Boolean, onSource: (Long, Int) -> Unit, onLookup: (String) -> Unit,
) {
    val c = appContainer(); val p = LocalGlassPalette.current; val list = rememberLazyListState()
    if (ta == null || article == null || article.companionId != ta.id || article.space != "outside") {
        Column(Modifier.padding(worldPadding(top, bottom))) { WorldPanel { Text("这则见闻已不在这里", color = p.content); Text("返回窗外看看其他见闻。", color = p.contentSecondary) } }; return
    }
    val picture by remember(ta.id, article.id) { c.db.world().observePicture(article.id) }.collectAsStateWithLifecycle(null)
    val image = picture?.takeIf { it.companionId == ta.id && it.fingerprint == article.fingerprint }
    val fetches by c.worldPictures.states.collectAsStateWithLifecycle()
    val fetch = fetches[article.id]
    val sourceNotes = notes.filter { it.companionId == ta.id && it.articleId == article.id }
    val imageNotes = looks.filter { it.companionId == ta.id && it.articleId == article.id && it.pictureSha256 == image?.sha256 }
    var allNotes by rememberSaveable(ta.id, article.id) { mutableStateOf(false) }
    var failed by remember(image?.file) { mutableStateOf(false) }
    val paragraphs = remember(article.fingerprint) { article.content.split(Regex("\n\\s*\n")).filter(String::isNotBlank) }
    LazyColumn(Modifier.fillMaxSize().fadeUnderTopBar(top, bottom), state = list,
        contentPadding = PaddingValues(top = top + 20.dp, bottom = bottom + 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(key = "title") { Column(Modifier.padding(horizontal = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("窗外 · 一则见闻", color = p.contentSecondary, fontSize = 12.sp)
            Text(article.title, color = p.content, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp)
            Text("${article.source} · 获取于 " + Instant.ofEpochMilli(article.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")), color = p.contentSecondary, fontSize = 12.sp)
        } }
        if (image != null) item(key = "photo") {
            val pic = image
            Column(Modifier.padding(start = if (article.id % 2L == 0L) 14.dp else 26.dp, end = if (article.id % 2L == 0L) 26.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))) {
                    if (!failed) AsyncImage(model = c.images.file(pic.file), contentDescription = "见闻配图：${pic.title}；作者 ${pic.author}", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio((pic.width.toFloat() / pic.height).coerceIn(.75f, 2f)).graphicsLayer {
                            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "photo" }
                            val offset = item?.let { -it.offset.toFloat() / it.size.coerceAtLeast(1) } ?: 0f
                            val drift = WorldJournalMotion.at(offset, depth)
                            scaleX = if (depth) 1.04f else 1f; scaleY = scaleX; translationY = size.height * drift
                        }, onError = { failed = true })
                    else Text("本地图片暂时无法显示，原有文字和来源仍可查看。", color = p.contentSecondary, modifier = Modifier.padding(18.dp))
                }
                Text("${pic.author} · ${pic.license} · 已缩放转存", color = p.contentSecondary, fontSize = 11.sp, lineHeight = 18.sp)
            }
        }
        item(key = "scope") { Column(Modifier.padding(horizontal = 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (article.searchQuery.isNotEmpty()) "从百科导言里读到" else "从订阅摘要里读到", color = p.contentSecondary, fontSize = 12.sp)
            Text("以下保留来源提供的文字，未读取网页全文；配图与 TA 的感想分别标注。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        } }
        items(paragraphs.indices.toList(), key = { "paragraph:$it" }) { index ->
            SelectionContainer(Modifier.padding(start = 26.dp, end = 30.dp)) { Text(paragraphs[index], color = p.content, fontSize = 16.sp, lineHeight = 28.sp) }
        }
        item(key = "thoughts") { Column(Modifier.padding(horizontal = 26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HorizontalDivider(color = p.contentSecondary.copy(alpha = .16f))
            Text("TA 的几句话", color = p.content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (sourceNotes.isEmpty() && imageNotes.none { it.content.isNotBlank() || it.question.isNotBlank() })
                Text(if (imageNotes.isEmpty()) "还没有留下感想。可以给 TA 一次看图的机会，也可以先放在这里。" else "TA 看过图片，这次没有留下笔记。", color = p.contentSecondary, fontSize = 14.sp, lineHeight = 23.sp)
            val entries = (sourceNotes.map { Triple(it.createdAt, "摘要笔记", it.content to it.question) } +
                imageNotes.filter { it.content.isNotBlank() || it.question.isNotBlank() }.map { Triple(it.createdAt, "看图感想", it.content to it.question) }).sortedByDescending { it.first }
            (if (allNotes) entries else entries.take(3)).forEach { n ->
                WorldPanel {
                    Text(n.second, color = p.contentSecondary, fontSize = 12.sp)
                    if (n.third.first.isNotBlank()) SelectionContainer { Text(n.third.first, color = p.content, fontSize = 15.sp, lineHeight = 25.sp) }
                    if (n.third.second.isNotBlank()) {
                        Text("还想知道 · ${n.third.second}", color = p.accentContent, fontSize = 14.sp, lineHeight = 23.sp)
                        TextButton(onClick = { onLookup(n.third.second) }) { Text("继续查阅") }
                    }
                }
            }
            if (!allNotes && entries.size > 3) TextButton(onClick = { allNotes = true }) { Text("展开其余 ${entries.size - 3} 条感想") }
        } }
        item(key = "actions") { Column(Modifier.padding(horizontal = 20.dp)) { WorldPanel {
            if (image != null) WorldPictureLookAction(ta, image)
            else if (WorldTopicSearch.validUrl(article.originUrl)) {
                Text("给见闻添一张配图", color = p.content, fontSize = 17.sp)
                Text("手动取得条目关联的一张公开图片，最多 2 MB；获取本身不调用模型。", color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
                GlassButton(onClick = { if (fetch?.busy == true) c.worldPictures.cancel(article.id) else c.worldPictures.fetch(ta.id, ta.createdAt, article.id, article.fingerprint) }, backdrop = backdrop) { Text(if (fetch?.busy == true) "停止获取配图" else "获取条目配图") }
                fetch?.message?.takeIf(String::isNotBlank)?.let { Text(it, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp) }
            }
            TextButton(onClick = { onSource(article.id, 0) }) { Text("查看原始摘要与完整来源") }
        } } }
    }
}

internal object WorldJournalMotion {
    /** At most 1.5% of frame height; the ordinary-scroll mode is exactly stationary. */
    fun at(offset: Float, enabled: Boolean): Float = if (!enabled || !offset.isFinite()) 0f else offset.coerceIn(-1f, 1f) * .015f
}
