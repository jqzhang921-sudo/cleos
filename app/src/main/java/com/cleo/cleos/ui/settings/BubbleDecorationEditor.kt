package com.cleo.cleos.ui.settings

import android.net.Uri
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.BubbleDecoration
import com.cleo.cleos.glass.LocalGlassPalette
import kotlin.math.roundToInt

@Composable
internal fun BubbleDecorationEditor(value: BubbleDecoration, onChange: (BubbleDecoration) -> Unit, onSave: (BubbleDecoration) -> Unit, onImport: (Bitmap, Boolean) -> Unit, busy: Boolean, error: String?) {
    var pending by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    pending?.let { (uri, face) -> BubbleImageCrop(uri, { pending = null }, { bitmap -> pending = null; onImport(bitmap, face) }) }
    val facePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { uri -> pending = uri to true } }
    val starPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { uri -> pending = uri to false } }
    val palette = LocalGlassPalette.current
    fun save(next: BubbleDecoration) { onChange(next); onSave(next) }
    Text("选图后先裁剪小组件，确认预览再应用。透明图片效果更好，裁剪不会自动去掉底色。",
        color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    Chip(if (busy) "正在导入…" else "从相册选择小脸", false) { if (!busy) facePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    EmojiPicker("小脸 Emoji", value.faceEmoji) { save(value.copy(faceEmoji = it, faceImage = null, faceShape = "original", faceEnabled = true)) }
    if (value.faceImage != null || value.faceEmoji.isNotEmpty()) {
        if (value.faceImage != null) Shapes(value.faceShape) { save(value.copy(faceShape = it)) }
        Chip("恢复内置小脸", false) { save(value.copy(faceImage = null, faceEmoji = "", faceShape = "original")) }
    }
    ExplainedSwitch("显示小脸", "关闭后去掉小脸和它的外侧留白", null, value.faceEnabled) { save(value.copy(faceEnabled = it)) }
    if (value.faceEnabled) {
        Text("小脸大小：${value.faceSize}", color = palette.contentSecondary, fontSize = 12.sp)
        Slider(value = value.faceSize.toFloat(), valueRange = 16f..32f, steps = 15,
            onValueChange = { onChange(value.copy(faceSize = it.roundToInt())) }, onValueChangeFinished = { onSave(value) })
        Text("小脸距离：${value.faceDistance} · 负值贴近，正值向外", color = palette.contentSecondary, fontSize = 12.sp)
        Slider(value.faceDistance.toFloat(), { onChange(value.copy(faceDistance = it.roundToInt())) }, valueRange = -8f..16f, steps = 23, onValueChangeFinished = { onSave(value) })
        Corners("小脸位置", value.faceCorner) { save(value.copy(faceCorner = it)) }
    }
    Chip(if (busy) "正在导入…" else "从相册选择星点装饰", false) { if (!busy) starPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    EmojiPicker("星点 Emoji", value.starEmoji) { save(value.copy(starEmoji = it, starImage = null, starShape = "original", starsEnabled = true, starSize = 24)) }
    if (value.starImage != null || value.starEmoji.isNotEmpty()) {
        if (value.starImage != null) Shapes(value.starShape) { save(value.copy(starShape = it)) }
        Chip("恢复内置星点", false) { save(value.copy(starImage = null, starEmoji = "", starShape = "original", starSize = 7)) }
    }
    error?.let { Text(it, color = palette.error, fontSize = 12.sp) }
    ExplainedSwitch("显示星点 / 几何装饰", "可单独保留小脸，或使用干净的渐变气泡", null, value.starsEnabled) { save(value.copy(starsEnabled = it)) }
    if (value.starsEnabled) {
        Text("星星大小：${value.starSize}", color = palette.contentSecondary, fontSize = 12.sp)
        Slider(value = value.starSize.toFloat(), valueRange = 4f..(if (value.starImage != null || value.starEmoji.isNotEmpty()) 32f else 12f), steps = if (value.starImage != null || value.starEmoji.isNotEmpty()) 27 else 7,
            onValueChange = { onChange(value.copy(starSize = it.roundToInt())) }, onValueChangeFinished = { onSave(value) })
        if (value.starImage != null || value.starEmoji.isNotEmpty()) {
            Text("装饰距离：${value.starDistance} · 负值贴近，正值向外", color = palette.contentSecondary, fontSize = 12.sp)
            Slider(value.starDistance.toFloat(), { onChange(value.copy(starDistance = it.roundToInt())) }, valueRange = -8f..16f, steps = 23, onValueChangeFinished = { onSave(value) })
        }
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

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun EmojiPicker(label: String, current: String, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var draft by remember(current) { mutableStateOf(current) }
    Chip(if (expanded) "收起 $label" else "选择 $label", current.isNotEmpty()) { expanded = !expanded }
    if (expanded) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("🥹", "🐰", "🐱", "🫧", "🌷", "🍓", "🦋", "✨", "🌙", "💗").forEach { emoji ->
                Chip(emoji, emoji == current) { onPick(emoji) }
            }
        }
        OutlinedTextField(draft, { if (it.length <= 64 && it.none { ch -> ch.isISOControl() }) draft = it },
            label = { Text("用键盘输入一个 Emoji") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Chip("应用 Emoji", false) { if (draft.isNotBlank()) onPick(draft.trim()) }
        Text("使用手机系统的表情样式，组合表情可直接粘贴。", color = LocalGlassPalette.current.contentSecondary, fontSize = 12.sp)
    }
}
