package com.cleo.cleos.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import com.cleo.cleos.R
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

/** Decorations occupy reserved space and are drawn in dp, never scaled with the message. */
@Composable
fun ChatBubbleSurface(
    modifier: Modifier = Modifier,
    mine: Boolean = false,
    theme: String = if (mine) LocalBubbleThemes.current.first else LocalBubbleThemes.current.second,
    horizontalPadding: Int = LocalBubblePadding.current.first,
    verticalPadding: Int = LocalBubblePadding.current.second,
    content: @Composable (Color) -> Unit,
) {
    val palette = LocalGlassPalette.current
    val id = BubbleThemes.valid(theme)
    val style = if (mine) palette.bubbleMine else palette.bubble
    val glassInk = if (mine) palette.mineContent else palette.content
    val padding = PaddingValues(horizontal = horizontalPadding.coerceIn(6, 24).dp, vertical = verticalPadding.coerceIn(4, 16).dp)
    if (id == "glass") {
        GlassSurface(modifier = modifier, style = style, shape = GlassShape.Rounded(16.dp),
            contentPadding = padding) { content(glassInk) }
        return
    }
    val ink = if (id == "clear") glassInk else Color(0xFF424854)
    Box(modifier.padding(top = if (id == "clear") 4.dp else 18.dp, bottom = 5.dp, start = 6.dp, end = 6.dp)) {
        if (id == "clear") {
            GlassSurface(style = style, shape = GlassShape.Rounded(16.dp),
                contentPadding = padding) { content(ink) }
        } else {
            Canvas(Modifier.matchParentSize()) {
                val tint = if (id == "blue") Color(0xFFDBE7F2) else Color(0xFFF5E2E7)
                // Several translucent strokes feather only the edge; text and ornaments stay sharp.
                for (i in 6 downTo 1) drawRoundRect(tint.copy(alpha = 0.025f),
                    topLeft = Offset(-i.dp.toPx() / 2, -i.dp.toPx() / 2),
                    size = Size(size.width + i.dp.toPx(), size.height + i.dp.toPx()),
                    cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(i.dp.toPx()))
                val gradient = Brush.linearGradient(listOf(tint, Color(0xFFF9F8F4)),
                    start = Offset.Zero, end = Offset(size.width, size.height))
                // Nested translucent fills make the body fade at its boundary instead of a hard cut.
                for (i in 0..6) {
                    val inset = (i * 0.5f).dp.toPx()
                    drawRoundRect(gradient, topLeft = Offset(inset, inset),
                        size = Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius((16.dp.toPx() - inset).coerceAtLeast(0f)), alpha = 0.48f)
                }
            }
            Box(Modifier.padding(padding)) { content(ink) }
        }
        Canvas(Modifier.matchParentSize()) {
            val line = if (id == "clear") ink.copy(alpha = 0.65f) else Color(0xFF838493)
            if (id == "clear") {
                drawCircle(line, 3.dp.toPx(), Offset(4.dp.toPx(), 1.dp.toPx()), style = Stroke(1.dp.toPx()))
                drawCircle(line, 1.dp.toPx(), Offset(13.dp.toPx(), -2.dp.toPx()))
                drawArc(line, 5f, 70f, false, Offset(size.width - 20.dp.toPx(), size.height - 18.dp.toPx()),
                    Size(20.dp.toPx(), 20.dp.toPx()), style = Stroke(1.dp.toPx()))
            } else {
                // Faces hang outside the body. The text padding also keeps long first/last lines clear.
                val x = if (id == "blue") 9.dp.toPx() else size.width - 8.dp.toPx()
                val y = size.height - 2.dp.toPx()
                drawCircle(line.copy(alpha = 0.45f), 1.4.dp.toPx(), Offset(x, y))
                drawCircle(if (id == "blue") Color(0xFFAFCDEB) else Color(0xFFE8B6C4),
                    2.dp.toPx(), Offset(x + 7.dp.toPx(), y - 2.dp.toPx()))
                val star = Path().apply {
                    moveTo(x - 8.dp.toPx(), y - 8.dp.toPx()); lineTo(x - 6.dp.toPx(), y - 3.dp.toPx())
                    lineTo(x - 2.dp.toPx(), y); lineTo(x - 6.dp.toPx(), y + 2.dp.toPx())
                    lineTo(x - 8.dp.toPx(), y + 6.dp.toPx()); lineTo(x - 10.dp.toPx(), y + 2.dp.toPx())
                    lineTo(x - 14.dp.toPx(), y); lineTo(x - 10.dp.toPx(), y - 3.dp.toPx()); close()
                }
                drawPath(star, line, style = Stroke(1.dp.toPx()))
            }
        }
        if (id == "blue" || id == "peach") Image(
            painter = painterResource(if (id == "blue") R.drawable.bubble_sleep else R.drawable.bubble_bunny),
            contentDescription = null,
            modifier = Modifier.align(if (id == "blue") Alignment.TopEnd else Alignment.TopStart)
                .offset(y = (-18).dp).size(24.dp),
        )
    }
}
