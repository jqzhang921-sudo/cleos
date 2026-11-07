package com.cleo.cleos.ui.world

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.cleo.cleos.glass.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

internal data class NotebookDeckCard(val key: String, val title: String, val kind: String, val preview: String, val detail: String,
    val book: Long = 0, val entry: String = "")

internal object NotebookDeckCards {
    fun all(books: List<NotebookBookView>, entries: List<WorldNotebookEntries.Entry>, chapterOrder: List<WorldNotebookEntries.Entry> = emptyList()): List<NotebookDeckCard> =
        books.map { b -> NotebookDeckCard("serial:${b.book.id}", b.book.title, "连载", b.entries.take(5).mapIndexed { i, row -> "${i + 1}. ${row.notebookTitle()}" }.joinToString("\n"),
            "${b.entries.size} 章 · 打开目录", book = b.book.id) } + entries.map { e ->
            val chapter = chapterOrder.indexOfFirst { it.key == e.key }
            NotebookDeckCard(e.key, e.notebookTitle(), (if (chapter >= 0) "第 ${chapter + 1} 章 · " else "") + e.notebookKind(), preview(e.notebookBody()),
                "打开后上下阅读", entry = e.key)
        }
    fun opening(keys: List<String>, saved: String): Int = keys.indexOf(saved).coerceAtLeast(0)
    private fun preview(body: String): String {
        val end = minOf(body.length, 240)
        return body.take(if (end > 0 && end < body.length && body[end - 1].isHighSurrogate()) end - 1 else end)
    }
}

/** Finite stack; the glass is drawn outside a scrolling viewport so its corners are never sliced. */
@Composable
internal fun NotebookDeck(cards: List<NotebookDeckCard>, savedKey: String, motion: Boolean, onSettled: (String) -> Unit, onOpen: (NotebookDeckCard) -> Unit) {
    if (cards.isEmpty()) return
    val p = LocalGlassPalette.current
    val keys = cards.map { it.key }
    key(keys) {
        var index by remember { mutableIntStateOf(NotebookDeckCards.opening(keys, savedKey)) }
        var drag by remember { mutableFloatStateOf(0f) }
        var held by remember { mutableStateOf(false) }
        var settling by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val onPosition by rememberUpdatedState(onSettled)
        LaunchedEffect(index) { onPosition(cards[index].key) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("左右翻阅", color = p.contentSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("${index + 1} / ${cards.size}", color = p.contentSecondary, fontSize = 12.sp)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val height = (maxWidth * 1.23f).coerceIn(280.dp, 450.dp)
                val width = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                suspend fun settle(target: Int) {
                    if (settling) return
                    settling = true
                    try {
                        val destination = (index - target) * width
                        Animatable(drag).animateTo(destination, tween(if (motion) 240 else 100, easing = FastOutSlowInEasing)) { drag = value }
                        index = target; drag = 0f
                    } finally { settling = false }
                }
                val gestures = rememberDraggableState { delta ->
                    drag = (drag + delta).coerceIn(if (index == cards.lastIndex) 0f else -width, if (index == 0) 0f else width)
                }
                val position = index - drag / width
                Box(Modifier.fillMaxWidth().height(height).semantics {
                    contentDescription = "篇目叠卡，左右滑动"
                    horizontalScrollAxisRange = ScrollAxisRange({ position }, { cards.lastIndex.toFloat() })
                    scrollBy { x, _ ->
                        if (settling || x == 0f) false else {
                            val target = (index + if (x > 0) 1 else -1).coerceIn(cards.indices)
                            if (target == index) false else { scope.launch { settle(target) }; true }
                        }
                    }
                }.draggable(gestures, Orientation.Horizontal, enabled = !settling,
                    onDragStarted = { held = true }, onDragStopped = { velocity ->
                        held = false
                        val step = when { drag < -width * .18f || velocity < -800f -> 1; drag > width * .18f || velocity > 800f -> -1; else -> 0 }
                        val target = (index + step).coerceIn(cards.indices)
                        scope.launch { settle(target) }
                    })) {
                  for (i in (index - 1..index + 2).filter { it in cards.indices }) key(cards[i].key) {
                    val distance = i - position
                    val depth = distance.coerceIn(0f, 2f)
                    val leaving = (-distance).coerceIn(0f, 1f)
                    val bow = sin(leaving * Math.PI).toFloat()
                    val textAlpha = (1f - abs(distance) * 2f).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxSize().zIndex(if (distance < 0) 4f + distance else 3f - distance).graphicsLayer {
                        translationX = if (distance < 0) -bow * 12.dp.toPx() else depth * 8.dp.toPx()
                        translationY = if (distance < 0) leaving * 8.dp.toPx() else depth * 5.dp.toPx()
                        val shrink = if (distance < 0) leaving * .035f else depth * .035f
                        scaleX = 1f - shrink; scaleY = 1f - shrink
                        rotationZ = if (motion) (if (distance < 0) -bow * 1.4f else depth * .7f) else 0f
                        alpha = if (distance < 0) 1f - leaving else if (distance <= 2f) 1f else 0f
                    }.padding(horizontal = 24.dp, vertical = 18.dp).then(if (i != index || held || settling) Modifier.clearAndSetSemantics {} else Modifier)) {
                        NotebookCover(cards[i], Modifier.fillMaxSize(), false, i == index && !held && !settling && abs(drag) < .5f, textAlpha) { onOpen(cards[i]) }
                    }
                  }
                }
            }
            Text(when {
                cards.size == 1 -> "只有这一张 · 点击打开"
                index == cards.lastIndex -> "已经翻到最后 · 可以向右滑回来"
                else -> "向左翻下一张 · 点击打开"
            }, color = p.contentSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
internal fun NotebookCover(card: NotebookDeckCard, modifier: Modifier = Modifier, overview: Boolean = true, enabled: Boolean = true, contentAlpha: Float = 1f, onOpen: () -> Unit) {
    val p = LocalGlassPalette.current
    val interaction = remember { MutableInteractionSource() }
    val style = p.card.copy(shadowAlpha = p.card.shadowAlpha * .55f, shadowRadius = 12.dp, shadowOffsetY = 4.dp)
    GlassSurface(modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onOpen)
        .semantics { contentDescription = if (card.book > 0) "打开连载：${card.title}" else "打开篇目：${card.title}" },
        style = style, shape = GlassShape.Rounded(if (overview) 16.dp else 24.dp), contentPadding = PaddingValues(if (overview) 14.dp else 24.dp)) {
        Column(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha }, verticalArrangement = Arrangement.spacedBy(if (overview) 8.dp else 14.dp)) {
            Text(card.kind, color = p.contentSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(card.title, color = p.content, fontWeight = FontWeight.Medium, fontSize = if (overview) 15.sp else 20.sp,
                lineHeight = if (overview) 22.sp else 29.sp, maxLines = if (overview) 3 else 4, overflow = TextOverflow.Ellipsis)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val leading = if (overview) 19.sp else 24.sp
                val lineHeight = with(LocalDensity.current) { leading.toDp() }
                val lines = ((maxHeight - 1.dp) / lineHeight).toInt().coerceIn(0, 12)
                if (lines > 0) Text(card.preview.ifBlank { "暂时没有章节，可打开后整理。" }, color = p.contentSecondary, fontSize = if (overview) 12.sp else 14.sp,
                    lineHeight = leading, maxLines = lines, overflow = TextOverflow.Ellipsis)
            }
            Text(card.detail, color = p.accentContent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
