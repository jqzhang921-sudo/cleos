package com.cleo.cleos.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.BubbleDecoration
import com.cleo.cleos.glass.LocalGlassPalette
import kotlin.math.roundToInt

@Composable
internal fun BubbleDecorationEditor(value: BubbleDecoration, onChange: (BubbleDecoration) -> Unit, onSave: (BubbleDecoration) -> Unit) {
    val palette = LocalGlassPalette.current
    fun save(next: BubbleDecoration) { onChange(next); onSave(next) }
    Text("装饰调整同时用于两边的主题。小脸和星点的位置、大小用于雾蓝与奶桃；透明留白可关闭几何装饰。",
        color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    ExplainedSwitch("显示小脸", "关闭后去掉小脸和它的外侧留白", null, value.faceEnabled) { save(value.copy(faceEnabled = it)) }
    if (value.faceEnabled) {
        Text("小脸大小：${value.faceSize}", color = palette.contentSecondary, fontSize = 12.sp)
        Slider(value = value.faceSize.toFloat(), valueRange = 16f..32f, steps = 15,
            onValueChange = { onChange(value.copy(faceSize = it.roundToInt())) }, onValueChangeFinished = { onSave(value) })
        Corners("小脸位置", value.faceCorner) { save(value.copy(faceCorner = it)) }
    }
    ExplainedSwitch("显示星点 / 几何装饰", "可单独保留小脸，或使用干净的渐变气泡", null, value.starsEnabled) { save(value.copy(starsEnabled = it)) }
    if (value.starsEnabled) {
        Text("星星大小：${value.starSize}", color = palette.contentSecondary, fontSize = 12.sp)
        Slider(value = value.starSize.toFloat(), valueRange = 4f..12f, steps = 7,
            onValueChange = { onChange(value.copy(starSize = it.roundToInt())) }, onValueChangeFinished = { onSave(value) })
        Corners("星点位置", value.starCorner) { save(value.copy(starCorner = it)) }
    }
    Chip("恢复默认装饰", selected = false) { save(BubbleDecoration()) }
}

@Composable
private fun Corners(label: String, chosen: String, onPick: (String) -> Unit) {
    Text(label, color = LocalGlassPalette.current.contentSecondary, fontSize = 12.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("auto" to "跟随主题", "tl" to "左上", "tr" to "右上", "bl" to "左下", "br" to "右下").forEach { (id, name) ->
            Chip(name, selected = id == chosen) { onPick(id) }
        }
    }
}
