package com.cleo.cleos.ui.world

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.cleo.cleos.glass.*
import com.cleo.cleos.data.db.WorldWritingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.sin

private data class NotebookText(val entry: WorldNotebookEntries.Entry, val body: String)
private data class NotebookSheet(val text: NotebookText, val span: NotebookPageSpan, val part: Int, val parts: Int)

/** Native selectable text and bounded adjacent pages; no HTML, rasterized prose or provider calls. */
@Composable
internal fun NotebookReader(
    entries: List<WorldNotebookEntries.Entry>, selectedKey: String, store: NotebookAnchorStore,
    top: Dp, bottom: Dp, motion: Boolean, error: String?, onAnchor: (NotebookAnchor) -> Unit,
    onDirectory: () -> Unit, onDelete: (WorldNotebookEntries.Entry) -> Unit,
    onSource: (Long, Int) -> Unit, onLookup: (String) -> Unit, onJournal: (Long) -> Unit,
    serialTitle: String? = null, onOrganize: (WorldWritingEntity) -> Unit = {},
) {
    val p = LocalGlassPalette.current
    val density = LocalDensity.current
    val compact = LocalConfiguration.current.screenHeightDp < 500
    val inset = if (compact) 12.dp else 18.dp
    val measurer = rememberTextMeasurer(cacheSize = 2)
    val style = MaterialTheme.typography.bodyLarge.copy(color = p.content, fontSize = 16.sp, lineHeight = 26.sp)
    val texts = remember(entries) { entries.map { NotebookText(it, it.notebookBody()) } }
    // Resolve the anchor before pagination starts; a configuration change must not save page zero.
    val openingKey = remember { selectedKey }
    val openingAnchor = remember { store.read(openingKey) }
    var active by remember { mutableStateOf(entries.first { it.key == openingKey }) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(top = top + if (compact) 0.dp else 8.dp, bottom = bottom + if (compact) 0.dp else 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDirectory) { Text("目录", color = p.accentContent) }
            if (compact) Column(Modifier.weight(1f)) {
                Text(active.notebookTitle(), color = p.content, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("第 ${entries.indexOfFirst { it.key == active.key }.coerceAtLeast(0) + 1} / ${entries.size} ${if (serialTitle == null) "篇" else "章"} · ${active.notebookKind()}",
                    color = p.contentSecondary, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text("第 ${entries.indexOfFirst { it.key == active.key }.coerceAtLeast(0) + 1} / ${entries.size} ${if (serialTitle == null) "篇" else "章"}",
                    color = p.contentSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "篇目操作", tint = p.contentSecondary) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    active.writing?.let { writing -> DropdownMenuItem(text = { Text(if (serialTitle == null) "归入连载" else "整理这本连载") }, onClick = { menu = false; onOrganize(writing) }) }
                    if (active.question.isNotBlank()) DropdownMenuItem(text = { Text("继续查阅") }, onClick = { menu = false; onLookup(active.question) })
                    if (active.writing == null) DropdownMenuItem(text = { Text(if (active.look != null) "回到图文见闻" else "回到来源") },
                        enabled = active.source != null, onClick = {
                            menu = false
                            if (active.look != null) onJournal(active.article)
                            else active.note?.let { onSource(it.articleId, it.quoteStart ?: it.fromOffset) }
                        })
                    DropdownMenuItem(text = { Text("删除这篇", color = p.error) }, onClick = { menu = false; onDelete(active) })
                }
            }
        }
        if (!compact) Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(active.notebookTitle(), color = p.content, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { contentDescription = "篇名：${active.notebookTitle()}" })
            Text((serialTitle?.let { "《$it》 · " } ?: "") + active.notebookKind() + (if (active.writing?.kind == "story") " · 虚构创作" else "") + " · " +
                Instant.ofEpochMilli(active.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")),
                color = p.contentSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            error?.let { Text(it, color = p.error, fontSize = 12.sp) }
        }
        if (compact) error?.let { Text(it, color = p.error, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (compact) 12.dp else 20.dp)) {
            // Reserve the real footer and insets before measuring lines, including short windows.
            val width = with(density) { (maxWidth - inset * 2).roundToPx() }.coerceAtLeast(1)
            val footerHeight = if (compact) 48.dp else maxOf(52.dp, with(density) { 34.sp.toDp() } + 4.dp)
            val height = with(density) { (maxHeight - footerHeight - inset * 2).toPx() }.coerceAtLeast(1f)
            val sheets by produceState<List<NotebookSheet>?>(null, texts, width, height, density.fontScale, style) {
                value = null
                value = withContext(Dispatchers.Default) {
                    buildList {
                        for (text in texts) {
                            yield()
                            val layout = measurer.measure(AnnotatedString(text.body), style, constraints = Constraints(maxWidth = width))
                            val lines = (0 until layout.lineCount).map {
                                NotebookLine(layout.getLineStart(it), layout.getLineEnd(it), layout.getLineTop(it), layout.getLineBottom(it))
                            }
                            val spans = NotebookPages.split(text.body.length, lines, height)
                            spans.forEachIndexed { part, span -> add(NotebookSheet(text, span, part, spans.size)) }
                        }
                    }
                }
            }
            val pages = sheets
            if (pages == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在整理页码…", color = p.contentSecondary, fontSize = 13.sp)
            } else key(pages) {
                // Re-paginate around the current entry/character when content or layout changes.
                val target = store.last()?.takeIf { it.key == selectedKey } ?: openingAnchor
                val entryPages = pages.withIndex().filter { it.value.text.entry.key == selectedKey }
                val wanted = entryPages.firstOrNull()?.value?.text
                val offset = wanted?.let { target?.offsetFor(it.entry.key, it.entry.at, it.body) } ?: 0
                val initial = entryPages.lastOrNull { it.value.span.start <= offset }?.index
                    ?: pages.indexOfFirst { it.text.entry.key == openingKey }.coerceAtLeast(0)
                val pager = rememberPagerState(initialPage = initial) { pages.size }
                val scope = rememberCoroutineScope()
                LaunchedEffect(pager) {
                    snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
                        pages.getOrNull(index)?.let { sheet ->
                            active = sheet.text.entry
                            onAnchor(NotebookAnchor(sheet.text.entry.key, sheet.text.entry.at, NotebookPages.tag(sheet.text.body), sheet.span.start))
                        }
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val shape = RoundedCornerShape(24.dp)
                        Box(Modifier.fillMaxSize().padding(start = 6.dp, top = 5.dp).background(p.card.tint.copy(alpha = .55f), shape)
                            .border(.5.dp, p.contentSecondary.copy(alpha = .12f), shape))
                        HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = 16.dp, key = { i ->
                            pages[i].let { "${it.text.entry.key}:${it.span.start}" }
                        }) { index ->
                            val sheet = pages[index]
                            val leafStyle = p.card.copy(tint = p.card.tint.copy(alpha = maxOf(p.card.tint.alpha, .86f)),
                                refraction = minOf(p.card.refraction, 1.dp), dispersion = p.card.dispersion * .2f,
                                shadowAlpha = p.card.shadowAlpha * .6f, shadowRadius = 18.dp)
                            GlassSurface(
                                Modifier.fillMaxSize().graphicsLayer {
                                    val delta = ((pager.currentPage - index) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                                    val bend = if (motion) sin(abs(delta) * Math.PI).toFloat() else 0f
                                    rotationY = if (motion) delta * -10f else 0f
                                    transformOrigin = TransformOrigin(if (delta >= 0) 0f else 1f, .5f)
                                    cameraDistance = 16f * density.density
                                    scaleY = 1f - bend * .012f
                                    this.shape = NotebookLeafShape(if (delta >= 0) bend else -bend, 24.dp.toPx())
                                    clip = true
                                }.drawWithContent {
                                    drawContent()
                                    val delta = ((pager.currentPage - index) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                                    val bend = if (motion) sin(abs(delta) * Math.PI).toFloat() else 0f
                                    if (bend > .001f) drawRect(Brush.horizontalGradient(listOf(
                                        Color.Black.copy(alpha = bend * .065f), Color.Transparent,
                                        Color.White.copy(alpha = bend * .10f), Color.Black.copy(alpha = bend * .04f))))
                                }, style = leafStyle, shape = GlassShape.Rounded(24.dp), contentPadding = PaddingValues(inset),
                            ) {
                                // Usually a page fits exactly. A single over-height line remains readable on tiny windows.
                                SelectionContainer(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    Text(sheet.text.body.substring(sheet.span.start, sheet.span.end), style = style,
                                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "阅读正文，第 ${sheet.part + 1} / ${sheet.parts} 页" })
                                }
                            }
                        }
                    }
                    val current = pages[pager.settledPage.coerceIn(pages.indices)]
                    Row(Modifier.fillMaxWidth().height(footerHeight).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(enabled = pager.settledPage > 0 && !pager.isScrollInProgress, onClick = {
                            scope.launch { if (motion) pager.animateScrollToPage(pager.settledPage - 1) else pager.scrollToPage(pager.settledPage - 1) }
                        }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "上一页", tint = if (pager.settledPage > 0) p.content else p.contentSecondary.copy(alpha = .35f)) }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("第 ${current.part + 1} / ${current.parts} 页", fontSize = 12.sp, lineHeight = 16.sp, color = p.contentSecondary)
                            if (!compact) Text("左右翻阅 · 长按正文可复制", fontSize = 10.sp, lineHeight = 14.sp, color = p.contentSecondary)
                        }
                        IconButton(enabled = pager.settledPage < pages.lastIndex && !pager.isScrollInProgress, onClick = {
                            scope.launch { if (motion) pager.animateScrollToPage(pager.settledPage + 1) else pager.scrollToPage(pager.settledPage + 1) }
                        }) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "下一页", tint = if (pager.settledPage < pages.lastIndex) p.content else p.contentSecondary.copy(alpha = .35f)) }
                    }
                }
            }
        }
    }
}

/** Slightly bows the free edge; it is a native leaf treatment, not a WebGL paper simulation. */
private data class NotebookLeafShape(val bend: Float, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = radius.coerceAtMost(minOf(size.width, size.height) / 2)
        val inset = abs(bend) * minOf(size.width * .035f, 12f * density.density)
        val w = size.width; val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(r, 0f); lineTo(w - r, 0f); quadraticTo(w, 0f, w, r)
            quadraticTo(w - if (bend >= 0) inset else 0f, h / 2, w, h - r)
            quadraticTo(w, h, w - r, h); lineTo(r, h); quadraticTo(0f, h, 0f, h - r)
            quadraticTo(if (bend < 0) inset else 0f, h / 2, 0f, r); quadraticTo(0f, 0f, r, 0f); close()
        })
    }
}
