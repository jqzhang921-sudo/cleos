package com.cleo.cleos.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.GlassPalettes
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.theme.Oklab
import kotlin.math.PI

/**
 * Colours for the person's own bubbles that read well as glass: a few deep ones for white
 * text, a few light ones for dark text, and the neutrals.
 */
private val PRESETS = listOf(
    0xFF6D5BD0, 0xFF3E7BFA, 0xFF1F9E99, 0xFF2F7D5B, 0xFFD9467A, 0xFF2B2B2E,
    0xFF95EC69, 0xFFFFE08A, 0xFFFFB38A, 0xFFFFA8C8, 0xFFB8D8FF, 0xFFF4F4F6,
).map { it.toInt() }

private const val TAU = (2 * PI).toFloat()

/**
 * The colour of the person's own bubbles and their text contrast. [chosen] null follows
 * the wallpaper. Sliders move a preview at once and save
 * when let go.
 */
@Composable
fun MyBubbleColor(chosen: Int?, onPick: (Int?) -> Unit) {
    val palette = LocalGlassPalette.current
    var tuning by remember { mutableStateOf(false) }
    // Bumped when a colour is picked from the row: the sliders then start again from it. Not
    // when a slider saves: the colour saved is the one shown, with the chroma the screen can
    // show, and starting from it would drop what the slider was asked for.
    var picked by remember { mutableIntStateOf(0) }
    // Where the sliders are while one is being dragged; null when showing what is saved.
    var trying by remember { mutableStateOf<Color?>(null) }
    val current = trying ?: chosen?.let { Color(it) }
    val (style, ink) = if (current == null) palette.bubbleMine to palette.mineContent else GlassPalettes.mine(palette, current)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("我的气泡", color = palette.content, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            GlassSurface(style = style, shape = GlassShape.Rounded(20.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                Text("这是我的气泡", color = ink, fontSize = 16.sp)
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip("跟着壁纸", selected = chosen == null) {
                trying = null
                picked++
                onPick(null)
            }
            PRESETS.forEach { argb ->
                Swatch(Color(argb), selected = chosen == argb) {
                    trying = null
                    picked++
                    onPick(argb)
                }
            }
            Chip("自己调", selected = tuning) { tuning = !tuning }
        }
        if (tuning) {
            // Start from what is showing when opened (or picked since): the saved colour, or the wallpaper's.
            val from = current ?: palette.accent
            val start = remember(picked) { Oklab.fromSrgb(from.red, from.green, from.blue) }
            var l by remember(picked) { mutableFloatStateOf(start.l.coerceIn(L_MIN, L_MAX)) }
            var c by remember(picked) { mutableFloatStateOf(start.chroma.coerceIn(0f, C_MAX)) }
            var h by remember(picked) { mutableFloatStateOf(((start.hue % TAU) + TAU) % TAU) }
            fun now() = Oklab.lch(l, c, h)
            fun save() {
                onPick(now().toArgb())
                trying = null
            }
            TrackSlider("色相", h, 0f..TAU, List(13) { Oklab.lch(0.7f, 0.14f, it * TAU / 12) }, { h = it; trying = now() }, ::save)
            TrackSlider("深浅", l, L_MIN..L_MAX, listOf(Oklab.lch(L_MIN, c, h), Oklab.lch(L_MAX, c, h)), { l = it; trying = now() }, ::save)
            TrackSlider("鲜艳", c, 0f..C_MAX, listOf(Oklab.lch(l, 0f, h), Oklab.lch(l, C_MAX, h)), { c = it; trying = now() }, ::save)
        }
        Text(
            if (palette.autoTint) "浅色配深色字，深色配白字。自动补着色会让选的颜色和文字更容易看清。"
            else "浅色配深色字，深色配白字。着色强度沿用玻璃实验室的「聊天气泡」设置。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

private const val L_MIN = 0.28f
private const val L_MAX = 0.95f
private const val C_MAX = 0.2f

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) palette.accentContent else palette.content.copy(alpha = 0.18f), CircleShape)
            .padding(if (selected) 5.dp else 0.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
    )
}

/** A slider over a strip showing what its values look like. */
@Composable
private fun TrackSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    strip: List<Color>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = palette.contentSecondary, fontSize = 13.sp, modifier = Modifier.width(40.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(10.dp).padding(horizontal = 10.dp)) {
                drawRoundRect(Brush.horizontalGradient(strip), cornerRadius = CornerRadius(size.height / 2))
            }
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                onValueChangeFinished = onDone,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                ),
            )
        }
    }
}
