package com.cleo.cleos.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
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

/** Decorations occupy reserved space and are drawn in dp, never scaled with the message. */
@Composable
fun ChatBubbleSurface(
    modifier: Modifier = Modifier,
    mine: Boolean = false,
    theme: String = if (mine) LocalBubbleThemes.current.first else LocalBubbleThemes.current.second,
    content: @Composable (Color) -> Unit,
) {
    val palette = LocalGlassPalette.current
    val id = BubbleThemes.valid(theme)
    val style = if (mine) palette.bubbleMine else palette.bubble
    val glassInk = if (mine) palette.mineContent else palette.content
    if (id == "glass") {
        GlassSurface(modifier = modifier, style = style, shape = GlassShape.Rounded(20.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { content(glassInk) }
        return
    }
    val ink = if (id == "clear") glassInk else Color(0xFF424854)
    Box(modifier.padding(top = 14.dp, bottom = 8.dp, start = 14.dp, end = 14.dp)) {
        if (id == "clear") {
            GlassSurface(style = style, shape = GlassShape.Rounded(22.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) { content(ink) }
        } else {
            Canvas(Modifier.matchParentSize()) {
                val tint = if (id == "blue") Color(0xFFC7DAEE) else Color(0xFFF5D9DE)
                // Several translucent strokes feather only the edge; text and ornaments stay sharp.
                for (i in 6 downTo 1) drawRoundRect(tint.copy(alpha = 0.025f),
                    topLeft = Offset(-i.dp.toPx() / 2, -i.dp.toPx() / 2),
                    size = Size(size.width + i.dp.toPx(), size.height + i.dp.toPx()),
                    cornerRadius = CornerRadius(22.dp.toPx()), style = Stroke(i.dp.toPx()))
                drawRoundRect(Brush.linearGradient(listOf(tint, Color(0xFFF9F8F4)),
                    start = Offset.Zero, end = Offset(size.width, size.height)),
                    cornerRadius = CornerRadius(22.dp.toPx()))
            }
            Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) { content(ink) }
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
                translate(if (id == "blue") size.width - 25.dp.toPx() else -3.dp.toPx(), -8.dp.toPx()) {
                    face(line, rabbit = id == "peach")
                }
                val x = if (id == "blue") 1.dp.toPx() else size.width - 8.dp.toPx()
                val y = size.height + 1.dp.toPx()
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
    }
}

private fun DrawScope.face(line: Color, rabbit: Boolean) {
    fun d(value: Float) = value.dp.toPx()
    val p = Path().apply {
        moveTo(d(3f), d(14f))
        if (rabbit) {
            cubicTo(d(-2f), d(0f), d(5f), d(-4f), d(9f), d(9f))
            cubicTo(d(8f), d(-6f), d(17f), d(-5f), d(16f), d(11f))
        } else cubicTo(d(5f), d(6f), d(13f), d(7f), d(16f), d(10f))
        cubicTo(d(28f), d(8f), d(33f), d(22f), d(24f), d(25f))
        cubicTo(d(10f), d(28f), d(-5f), d(25f), d(3f), d(14f)); close()
    }
    drawPath(p, Color(0xFFF9F7F3))
    drawPath(p, line, style = Stroke(d(1.2f)))
    if (rabbit) {
        drawCircle(line, d(0.9f), Offset(d(10f), d(18f)))
        drawCircle(line, d(0.9f), Offset(d(20f), d(18f)))
    } else {
        drawLine(line, Offset(d(8f), d(18f)), Offset(d(12f), d(19f)), d(1.1f))
        drawLine(line, Offset(d(20f), d(19f)), Offset(d(24f), d(18f)), d(1.1f))
    }
    drawCircle(line, d(1f), Offset(d(16f), d(22f)), style = Stroke(d(0.8f)))
}
