package com.cleo.cleos.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import coil3.compose.AsyncImage
import com.cleo.cleos.ui.common.appContainer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import com.cleo.cleos.R
import com.cleo.cleos.data.BubbleDecoration
import com.cleo.cleos.data.BubbleBackground
import androidx.compose.ui.graphics.luminance
import kotlin.math.pow
import kotlin.math.min
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

import androidx.compose.ui.graphics.drawscope.Stroke

import androidx.compose.ui.unit.dp
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette

/** Stable names, shared by preferences, previews and live/completed messages. */
object BubbleThemes {
    val choices = listOf("glass" to "默认玻璃", "blue" to "雾蓝小困", "peach" to "奶桃小闹", "clear" to "透明留白")
    fun valid(id: String) = id.takeIf { name -> choices.any { it.first == name } } ?: "glass"
}

val LocalBubbleThemes = compositionLocalOf { "glass" to "glass" }
val LocalBubblePadding = compositionLocalOf { 10 to 6 }
val LocalBubbleDecoration = compositionLocalOf { BubbleDecoration() }
val LocalBubbleBackgrounds = compositionLocalOf<Pair<BubbleBackground?, BubbleBackground?>> { null to null }

/** Decorations occupy reserved space and are drawn in dp, never scaled with the message. */
@Composable
fun ChatBubbleSurface(
    modifier: Modifier = Modifier,
    mine: Boolean = false,
    theme: String = if (mine) LocalBubbleThemes.current.first else LocalBubbleThemes.current.second,
    horizontalPadding: Int = LocalBubblePadding.current.first,
    verticalPadding: Int = LocalBubblePadding.current.second,
    decoration: BubbleDecoration = LocalBubbleDecoration.current,
    background: BubbleBackground? = if (mine) LocalBubbleBackgrounds.current.first else LocalBubbleBackgrounds.current.second,
    onDecorationDrag: ((Boolean, Float, Float) -> Unit)? = null,
    onComponentSelect: ((String) -> Unit)? = null,
    onComponentDrag: ((String, Float, Float) -> Unit)? = null,
    selectedComponent: String? = null,
    onDecorationDragEnd: (() -> Unit)? = null,
    content: @Composable (Color) -> Unit,
) {
    val palette = LocalGlassPalette.current
    val id = BubbleThemes.valid(theme)
    val style = if (mine) palette.bubbleMine else palette.bubble
    val glassInk = if (mine) palette.mineContent else palette.content
    val padding = PaddingValues(horizontal = horizontalPadding.coerceIn(6, 24).dp, vertical = verticalPadding.coerceIn(4, 16).dp)
    val decor = decoration.normalized()
    val faceCorner = if (decor.faceCorner == "auto") (if (id == "blue") "tr" else "tl") else decor.faceCorner
    val starCorner = if (decor.starCorner == "auto") (if (id == "blue") "bl" else "br") else decor.starCorner
    val images = appContainer().images
    val faceFile = decor.faceImage?.let { images.file(it) }?.takeIf { it.exists() }
    val starFile = decor.starImage?.let { images.file(it) }?.takeIf { it.exists() }
    val extra = decor.components.filter { it.enabled && (it.emoji.isNotEmpty() || it.image?.let { name -> images.file(name).exists() } == true) }
    val customStar = decor.starsEnabled && (starFile != null || decor.starEmoji.isNotEmpty())
    val faceShown = decor.faceEnabled && (decor.faceEmoji.isNotEmpty() || faceFile != null || id == "blue" || id == "peach")
    val raised = (decor.faceSize * 0.85f + decor.faceDistance).coerceAtLeast((decor.faceSize - verticalPadding).toFloat())
    val starRaised = (decor.starSize / 2f + decor.starDistance).coerceAtLeast((decor.starSize - verticalPadding).toFloat())
    if (id == "glass" && background == null && !faceShown && !customStar && extra.isEmpty()) {
        GlassSurface(modifier = modifier, style = style, shape = GlassShape.Rounded(16.dp),
            contentPadding = padding) { content(glassInk) }
        return
    }
    val bg = (background ?: BubbleBackground.defaults(id)).normalized()
    val first = Color(bg.startColor)
    val last = if (bg.gradient) Color(bg.endColor) else first
    val middle = Color((first.red + last.red) / 2, (first.green + last.green) / 2, (first.blue + last.blue) / 2)
    val whiteInk = if (bg.opacity < 50) palette.content.luminance() > 0.5f else middle.luminance() < 0.35f
    val ink = if ((id == "clear" || id == "glass") && background == null) glassInk else if (whiteInk) Color.White else Color(0xFF292D35)
    fun contrast(c: Color) = if (whiteInk) 1.05f / (c.luminance() + 0.05f) else (c.luminance() + 0.05f) / 0.08f
    val needsScrim = min(contrast(first), contrast(last)) < 4.5f
    @Composable fun Fill() {
        Box {
            Canvas(Modifier.matchParentSize()) {
                val start = if (bg.direction == 3) Offset(size.width, 0f) else Offset.Zero
                val end = when(bg.direction) { 0 -> Offset(size.width, 0f); 1 -> Offset(0f, size.height); 3 -> Offset(0f, size.height); else -> Offset(size.width, size.height) }
                val gradient = Brush.linearGradient(listOf(first, last), start, end)
                val targetAlpha = (bg.opacity / 100f * (if (bg.material == "glass") 0.45f else 1f)).coerceAtMost(0.995f)
                val passes = bg.softness * 2 + 1
                val alpha = 1f - (1f - targetAlpha).pow(1f / passes)
                for (i in 0 until passes) {
                    val inset = (i * 0.5f).dp.toPx()
                    drawRoundRect(gradient, topLeft = Offset(inset, inset),
                        size = Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius((16.dp.toPx() - inset).coerceAtLeast(0f)), alpha = alpha)
                }
                if (needsScrim) drawRoundRect(if (whiteInk) Color.Black else Color.White,
                    cornerRadius = CornerRadius(16.dp.toPx()), alpha = 0.55f)
            }
            Box(Modifier.padding(padding)) { content(ink) }
        }
    }
    var bodyWidth by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val drag by rememberUpdatedState(onDecorationDrag)
    val extraDrag by rememberUpdatedState(onComponentDrag)
    val select by rememberUpdatedState(onComponentSelect)
    val dragEnd by rememberUpdatedState(onDecorationDragEnd)
    fun draggable(face: Boolean): Modifier = if (onDecorationDrag == null) Modifier else Modifier.pointerInput(face) {
        var remainderX = 0f
        var remainderY = 0f
        detectDragGestures(onDragStart = { select?.invoke(if (face) "face" else "star"); remainderX = 0f; remainderY = 0f },
            onDragEnd = { dragEnd?.invoke() }, onDragCancel = { dragEnd?.invoke() }) { change, delta ->
            change.consume()
            remainderX += delta.x / density.density; remainderY += delta.y / density.density
            val dx = kotlin.math.round(remainderX); val dy = kotlin.math.round(remainderY)
            remainderX -= dx; remainderY -= dy
            drag?.invoke(face, dx, dy)
        }
    }
    fun selectable(id: String): Modifier {
        var mod: Modifier = if (onComponentSelect != null) Modifier.clickable { select?.invoke(id) } else Modifier
        if (selectedComponent == id) mod = mod.border(1.dp, Color(0xFF8C81C5), RoundedCornerShape(4.dp))
        return mod
    }
    fun extraDraggable(id: String): Modifier = if (onComponentDrag == null) Modifier else Modifier.pointerInput(id) {
        var remainderX = 0f
        var remainderY = 0f
        detectDragGestures(onDragStart = { select?.invoke(id); remainderX = 0f; remainderY = 0f },
            onDragEnd = { dragEnd?.invoke() }, onDragCancel = { dragEnd?.invoke() }) { change, delta ->
            change.consume()
            remainderX += delta.x / density.density
            remainderY += delta.y / density.density
            val dx = kotlin.math.round(remainderX)
            val dy = kotlin.math.round(remainderY)
            remainderX -= dx; remainderY -= dy
            extraDrag?.invoke(id, dx, dy)
        }
    }
    fun extraCorner(corner: String) = if (corner == "auto") "tl" else corner
    fun extraRaised(item: com.cleo.cleos.data.BubbleComponent) = (item.size * 0.85f + item.distance).coerceAtLeast((item.size - verticalPadding).toFloat())
    fun offsetX(value: Int, corner: String, size: Int): Float {
        val width = (bodyWidth - size).coerceAtLeast(0f)
        return if (corner.endsWith("l")) value.toFloat().coerceIn(-24f, width)
            else value.toFloat().coerceIn(-width, 24f)
    }
    val outerLeft = maxOf(6, if (faceShown && faceCorner.endsWith("l")) -decor.faceOffsetX.coerceIn(-24, 0) else 0,
        if (customStar && starCorner.endsWith("l")) -decor.starOffsetX.coerceIn(-24, 0) else 0)
    val outerRight = maxOf(6, if (faceShown && faceCorner.endsWith("r")) decor.faceOffsetX.coerceIn(0, 24) else 0,
        if (customStar && starCorner.endsWith("r")) decor.starOffsetX.coerceIn(0, 24) else 0)
    val extraTop = extra.filter { extraCorner(it.corner).startsWith("t") }.maxOfOrNull { extraRaised(it) } ?: 0f
    val extraBottom = extra.filter { extraCorner(it.corner).startsWith("b") }.maxOfOrNull { extraRaised(it) } ?: 0f
    val extraLeft = extra.filter { extraCorner(it.corner).endsWith("l") }.maxOfOrNull { -it.offsetX.coerceIn(-24, 0) } ?: 0
    val extraRight = extra.filter { extraCorner(it.corner).endsWith("r") }.maxOfOrNull { it.offsetX.coerceIn(0, 24) } ?: 0
    Box(modifier.padding(top = maxOf(extraTop, if (faceShown && faceCorner.startsWith("t")) raised else 4f, if (customStar && starCorner.startsWith("t")) starRaised else 0f).dp,
        bottom = maxOf(extraBottom, if (faceShown && faceCorner.startsWith("b")) raised else 7f, if (customStar && starCorner.startsWith("b")) starRaised else 0f).dp, start = maxOf(outerLeft, extraLeft).dp, end = maxOf(outerRight, extraRight).dp).onSizeChanged { bodyWidth = it.width / density.density }) {
        if ((id == "clear" || id == "glass") && background == null) {
            GlassSurface(style = style, shape = GlassShape.Rounded(16.dp),
                contentPadding = padding) { content(ink) }
        } else if (bg.material == "glass") {
            GlassSurface(style = style.copy(tint = middle.copy(alpha = (style.tint.alpha * bg.opacity / 100f).coerceAtLeast(0.28f))),
                shape = GlassShape.Rounded(16.dp)) { Fill() }
        } else Fill()
        Canvas(Modifier.matchParentSize()) {
            val line = if (id == "clear") ink.copy(alpha = 0.65f) else Color(0xFF838493)
            if (id == "clear" && decor.starsEnabled && !customStar) {
                drawCircle(line, 3.dp.toPx(), Offset(4.dp.toPx(), 1.dp.toPx()), style = Stroke(1.dp.toPx()))
                drawCircle(line, 1.dp.toPx(), Offset(13.dp.toPx(), -2.dp.toPx()))
                drawArc(line, 5f, 70f, false, Offset(size.width - 20.dp.toPx(), size.height - 18.dp.toPx()),
                    Size(20.dp.toPx(), 20.dp.toPx()), style = Stroke(1.dp.toPx()))
            } else if ((id == "blue" || id == "peach") && decor.starsEnabled && !customStar) {
                // Faces hang outside the body. The text padding also keeps long first/last lines clear.
                val radius = (decor.starSize / 2f).dp.toPx()
                val x = if (starCorner.endsWith("l")) (radius + 2.dp.toPx()) else size.width - radius - 2.dp.toPx()
                val y = if (starCorner.startsWith("t")) 0f else size.height
                drawCircle(line.copy(alpha = 0.45f), 1.4.dp.toPx(), Offset(x, y))
                drawCircle(if (id == "blue") Color(0xFFAFCDEB) else Color(0xFFE8B6C4),
                    1.5.dp.toPx(), Offset(x + (if (starCorner.endsWith("l")) 7 else -7).dp.toPx(), y - 2.dp.toPx()))
                val star = Path().apply {
                    moveTo(x, y - radius); lineTo(x + radius * 0.3f, y - radius * 0.3f)
                    lineTo(x + radius, y); lineTo(x + radius * 0.3f, y + radius * 0.3f)
                    lineTo(x, y + radius); lineTo(x - radius * 0.3f, y + radius * 0.3f)
                    lineTo(x - radius, y); lineTo(x - radius * 0.3f, y - radius * 0.3f); close()
                }
                drawPath(star, line, style = Stroke(1.dp.toPx()))
            }
        }
        fun corner(which: String) = when(which) {
            "tr" -> Alignment.TopEnd; "bl" -> Alignment.BottomStart; "br" -> Alignment.BottomEnd; else -> Alignment.TopStart
        }
        fun shaped(mod: Modifier, shape: String) = when(shape) {
            "circle" -> mod.clip(CircleShape)
            "rounded" -> mod.clip(RoundedCornerShape(6.dp))
            else -> mod
        }
        if (faceShown) {
            val faceModifier = Modifier.align(corner(faceCorner))
                .offset(x = offsetX(decor.faceOffsetX, faceCorner, decor.faceSize).dp, y = (if (faceCorner.startsWith("t")) -raised else raised).dp).size(decor.faceSize.dp).then(selectable("face")).then(draggable(true))
            if (decor.faceEmoji.isNotEmpty()) EmojiDecoration(decor.faceEmoji, decor.faceSize, faceModifier)
            else if (faceFile != null) AsyncImage(model = faceFile, contentDescription = null,
                contentScale = ContentScale.Fit, modifier = shaped(faceModifier, decor.faceShape))
            else Image(painterResource(if (id == "blue") R.drawable.bubble_sleep else R.drawable.bubble_bunny),
                contentDescription = null, modifier = faceModifier)
        }
        if (customStar) {
            val starModifier = Modifier.align(corner(starCorner)).offset(x = offsetX(decor.starOffsetX, starCorner, decor.starSize).dp, y =
                (if (starCorner.startsWith("t")) -starRaised else starRaised).dp).size(decor.starSize.dp).then(selectable("star")).then(draggable(false))
            if (decor.starEmoji.isNotEmpty()) EmojiDecoration(decor.starEmoji, decor.starSize, starModifier)
            else AsyncImage(model = starFile, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = shaped(starModifier, decor.starShape))
        }
        extra.forEach { item -> key(item.id) {
            val anchor = extraCorner(item.corner)
            val rise = extraRaised(item)
            val mod = Modifier.align(corner(anchor)).offset(x = offsetX(item.offsetX, anchor, item.size).dp,
                y = (if (anchor.startsWith("t")) -rise else rise).dp).size(item.size.dp)
                .then(selectable(item.id)).then(extraDraggable(item.id))
            if (item.emoji.isNotEmpty()) EmojiDecoration(item.emoji, item.size, mod)
            else AsyncImage(model = item.image?.let { images.file(it) }, contentDescription = null,
                contentScale = ContentScale.Fit, modifier = shaped(mod, item.shape))
        } }

    }
}

@Composable
private fun EmojiDecoration(emoji: String, size: Int, modifier: Modifier) {
    val font = with(LocalDensity.current) { (size * 0.8f).dp.toSp() }
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(emoji, maxLines = 1, softWrap = false, style = TextStyle(fontSize = font,
            lineHeight = font, platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}
