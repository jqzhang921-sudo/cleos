package com.cleo.cleos.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.BubbleBackground
import com.cleo.cleos.glass.LocalGlassPalette
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BubbleBackgroundEditor(value: BubbleBackground, onChange: (BubbleBackground) -> Unit,
    onSave: (BubbleBackground) -> Unit, onReset: () -> Unit) {
    val palette = LocalGlassPalette.current
    fun save(next: BubbleBackground) { onChange(next); onSave(next) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("玻璃", value.material == "glass") { save(value.copy(material = "glass")) }
        Chip("柔色", value.material == "soft") { save(value.copy(material = "soft")) }
        Chip("纯色", !value.gradient) { save(value.copy(gradient = false)) }
        Chip("双色渐变", value.gradient) { save(value.copy(gradient = true)) }
    }
    Text(if (value.material == "glass") "模糊、折射等沿用玻璃实验室，颜色叠在玻璃上。" else "柔色背景不使用玻璃模糊。",
        color = palette.contentSecondary, fontSize = 12.sp)
    ColorControls(if (value.gradient) "起始颜色" else "背景颜色", value.startColor,
        { onChange(value.copy(startColor = it)) }, { onSave(value) }, { save(value.copy(startColor = it)) })
    if (value.gradient) {
        ColorControls("结束颜色", value.endColor, { onChange(value.copy(endColor = it)) }, { onSave(value) }, { save(value.copy(endColor = it)) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("横向", "纵向", "左上到右下", "右上到左下").forEachIndexed { i, label ->
                Chip(label, value.direction == i) { save(value.copy(direction = i)) }
            }
        }
    }
    Text("不透明度：${value.opacity}%", color = palette.contentSecondary, fontSize = 12.sp)
    Slider(value.opacity.toFloat(), { onChange(value.copy(opacity = it.roundToInt())) }, valueRange = 15f..100f,
        onValueChangeFinished = { onSave(value) })
    Text("柔边强度：${value.softness}", color = palette.contentSecondary, fontSize = 12.sp)
    Slider(value.softness.toFloat(), { onChange(value.copy(softness = it.roundToInt())) }, valueRange = 0f..8f, steps = 7,
        onValueChangeFinished = { onSave(value) })
    Chip("恢复主题背景", false, onReset)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorControls(label: String, argb: Int, onChange: (Int) -> Unit, onSave: () -> Unit, onPick: (Int) -> Unit) {
    val palette = LocalGlassPalette.current
    Text(label, color = palette.content, fontSize = 14.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0xFFDBE7F2, 0xFFF5E2E7, 0xFFE3DBEF, 0xFFD9EEE5, 0xFFF4E8D0, 0xFFF9F8F4, 0xFF343644).forEach { raw ->
            val c = raw.toInt()
            Box(Modifier.size(34.dp).background(Color(c), CircleShape).clickable(onClickLabel = "选择颜色") { onPick(c) })
        }
    }
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
    listOf("色相", "饱和度", "明暗").forEachIndexed { i, name ->
        Text(name, color = palette.contentSecondary, fontSize = 12.sp)
        Slider(hsv[i], { component ->
            val next = hsv.copyOf(); next[i] = component
            onChange(android.graphics.Color.HSVToColor(next))
        }, valueRange = if (i == 0) 0f..360f else 0f..1f, onValueChangeFinished = onSave)
    }
}
