package com.cleo.cleos.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
internal fun BubbleDecorationEditor(value: BubbleDecoration, onChange: (BubbleDecoration) -> Unit, onSave: (BubbleDecoration) -> Unit, onImport: (Uri, Boolean) -> Unit, busy: Boolean, error: String?) {
    val facePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { uri -> onImport(uri, true) } }
    val starPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { uri -> onImport(uri, false) } }
    val palette = LocalGlassPalette.current
    fun save(next: BubbleDecoration) { onChange(next); onSave(next) }
    Text("选择单个小脸或贴纸，透明图片效果更好。整张聊天截图需先裁剪处理，否则会整张缩小显示。",
        color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    Chip(if (busy) "正在导入…" else "从相册选择小脸", false) { if (!busy) facePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    if (value.faceImage != null) {
        Shapes(value.faceShape) { save(value.copy(faceShape = it)) }
        Chip("恢复内置小脸", false) { save(value.copy(faceImage = null, faceShape = "original")) }
    }
    ExplainedSwitch("显示小脸", "关闭后去掉小脸和它的外侧留白", null, value.faceEnabled) { save(value.copy(faceEnabled = it)) }
    if (value.faceEnabled) {
        Text("小脸大小：${value.faceSize}", color = palette.contentSecondary, fontSize = 12.sp)
        Slider(value = value.faceSize.toFloat(), valueRange = 16f..32f, steps = 15,
            onValueChange = { onChange(value.copy(faceSize = it.roundToInt())) }, onValueChangeFinished = { onSave(value) })
        Corners("小脸位置", value.faceCorner) { save(value.copy(faceCorner = it)) }
    }
    Chip(if (busy) "正在导入…" else "从相册选择星点装饰", false) { if (!busy) starPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    if (value.starImage != null) {
        Shapes(value.starShape) { save(value.copy(starShape = it)) }
        Chip("恢复内置星点", false) { save(value.copy(starImage = null, starShape = "original", starSize = 7)) }
    }
    error?.let { Text(it, color = palette.error, fontSize = 12.sp) }
    ExplainedSwitch("显示星点 / 几何装饰", "可单独保留小脸，或使用干净的渐变气泡", null, value.starsEnabled) { save(value.copy(starsEnabled = it)) }
    if (value.starsEnabled) {
        Text("星星大小：${value.starSize}", color = palette.contentSecondary, fontSize = 12.sp)
        Slider(value = value.starSize.toFloat(), valueRange = 4f..(if (value.starImage != null) 32f else 12f), steps = if (value.starImage != null) 27 else 7,
            onValueChange = { onChange(value.copy(starSize = it.roundToInt())) }, onValueChangeFinished = { onSave(value) })
        Corners("星点位置", value.starCorner) { save(value.copy(starCorner = it)) }
    }
    Chip("恢复默认装饰", selected = false) { save(BubbleDecoration()) }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Corners(label: String, chosen: String, onPick: (String) -> Unit) {
    Text(label, color = LocalGlassPalette.current.contentSecondary, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("auto" to "跟随主题", "tl" to "左上", "tr" to "右上", "bl" to "左下", "br" to "右下").forEach { (id, name) ->
            Chip(name, selected = id == chosen) { onPick(id) }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Shapes(chosen: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("original" to "原图", "circle" to "圆形", "rounded" to "圆角").forEach { (id, name) -> Chip(name, chosen == id) { onPick(id) } }
    }
}
