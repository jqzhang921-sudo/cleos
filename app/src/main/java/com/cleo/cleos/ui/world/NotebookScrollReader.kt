package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.cleo.cleos.data.db.WorldWritingEntity
import com.cleo.cleos.glass.*
import kotlinx.coroutines.flow.filterNotNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A chapter is one continuous, selectable text. Character anchors also accept the older paged reader. */
@Composable
internal fun NotebookScrollReader(
    entries: List<WorldNotebookEntries.Entry>, selectedKey: String, store: NotebookAnchorStore,
    top: Dp, bottom: Dp, error: String?, onAnchor: (NotebookAnchor) -> Unit,
    onDirectory: () -> Unit, onDelete: (WorldNotebookEntries.Entry) -> Unit,
    onSource: (Long, Int) -> Unit, onLookup: (String) -> Unit, onJournal: (Long) -> Unit,
    serialTitle: String? = null, onOrganize: (WorldWritingEntity) -> Unit = {},
) {
    val active = entries.firstOrNull { it.key == selectedKey } ?: return
    val body = active.notebookBody()
    key(active.key, active.at, NotebookPages.tag(body)) {
        val p = LocalGlassPalette.current
        val compact = LocalConfiguration.current.screenHeightDp < 500
        val scroll = rememberScrollState()
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        var ready by remember { mutableStateOf(false) }
        var anchor by remember { mutableStateOf(store.read(active.key)) }
        var menu by remember { mutableStateOf(false) }
        val index = entries.indexOfFirst { it.key == active.key }
        val unit = if (serialTitle == null) "篇" else "章"
        val saveAnchor by rememberUpdatedState(onAnchor)
        LaunchedEffect(layout) {
            val text = layout ?: return@LaunchedEffect
            ready = false
            val offset = anchor?.offsetFor(active.key, active.at, body) ?: 0
            val line = text.getLineForOffset(offset.coerceIn(0, body.lastIndex.coerceAtLeast(0)))
            scroll.scrollTo(text.getLineTop(line).toInt().coerceAtLeast(0))
            ready = true
            snapshotFlow { if (ready && !scroll.isScrollInProgress) scroll.value else null }.filterNotNull().collect { pixels ->
                val start = text.getLineStart(text.getLineForVerticalPosition(pixels.toFloat()))
                NotebookAnchor(active.key, active.at, NotebookPages.tag(body), start).let { value ->
                    anchor = value; saveAnchor(value)
                }
            }
        }
        fun open(at: Int) {
            val next = entries.getOrNull(at) ?: return
            val nextBody = next.notebookBody()
            val saved = store.read(next.key)
            saveAnchor(NotebookAnchor(next.key, next.at, NotebookPages.tag(nextBody), saved?.offsetFor(next.key, next.at, nextBody) ?: 0))
        }
        Column(Modifier.fillMaxSize().padding(top = top + if (compact) 0.dp else 8.dp, bottom = bottom + if (compact) 0.dp else 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDirectory) { Text("目录", color = p.accentContent) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (compact) Text(active.notebookTitle(), color = p.content, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("第 ${index + 1} / ${entries.size} $unit", color = p.contentSecondary, fontSize = if (compact) 10.sp else 12.sp)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "篇目操作", tint = p.contentSecondary) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        active.writing?.let { writing -> DropdownMenuItem(text = { Text(if (serialTitle == null) "归入连载" else "整理这本连载") }, onClick = { menu = false; onOrganize(writing) }) }
                        if (active.question.isNotBlank()) DropdownMenuItem(text = { Text("继续查阅") }, onClick = { menu = false; onLookup(active.question) })
                        if (active.writing == null) DropdownMenuItem(text = { Text(if (active.look != null) "回到图文见闻" else "回到来源") }, enabled = active.source != null, onClick = {
                            menu = false
                            if (active.look != null) onJournal(active.article) else active.note?.let { onSource(it.articleId, it.quoteStart ?: it.fromOffset) }
                        })
                        DropdownMenuItem(text = { Text("删除这篇", color = p.error) }, onClick = { menu = false; onDelete(active) })
                    }
                }
            }
            if (!compact) Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(active.notebookTitle(), color = p.content, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { contentDescription = "篇名：${active.notebookTitle()}" })
                Text((serialTitle?.let { "《$it》 · " } ?: "") + active.notebookKind() + (if (active.writing?.kind == "story") " · 虚构创作" else "") + " · " +
                    Instant.ofEpochMilli(active.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")),
                    color = p.contentSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            error?.let { Text(it, color = p.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
            val leaf = p.card.copy(shadowAlpha = p.card.shadowAlpha * .55f, shadowRadius = 12.dp, shadowOffsetY = 4.dp)
            GlassSurface(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (compact) 12.dp else 20.dp), style = leaf,
                shape = GlassShape.Rounded(24.dp), contentPadding = PaddingValues(if (compact) 12.dp else 18.dp)) {
                SelectionContainer(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Text(body, color = p.content, fontSize = 16.sp, lineHeight = 26.sp, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "阅读正文，上下滑动" },
                        onTextLayout = { result -> if (layout != result) { ready = false; layout = result } })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = index > 0, onClick = { open(index - 1) }) { Text("上一$unit") }
                Text("上下阅读", color = p.contentSecondary, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
                TextButton(enabled = index < entries.lastIndex, onClick = { open(index + 1) }) { Text("下一$unit") }
            }
        }
    }
}
