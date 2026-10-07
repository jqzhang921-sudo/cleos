package com.cleo.cleos.ui.settings

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.BubbleComponent
import com.cleo.cleos.data.BubbleDecoration
import com.cleo.cleos.glass.LocalGlassPalette
import java.util.UUID
import kotlin.math.roundToInt

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun BubbleComponentsEditor(value: BubbleDecoration, selected: String?, onSelect: (String?) -> Unit,
    onChange: (BubbleDecoration) -> Unit, onSave: (BubbleDecoration) -> Unit,
    onImport: (Bitmap, String) -> Unit, busy: Boolean, error: String?) {
    var adding by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var newImageId by remember { mutableStateOf<String?>(null) }
    fun cancelNewImage() {
        val id = newImageId
        if (id != null) { val next = value.copy(components = value.components.filter { it.id != id }); onChange(next); onSave(next); onSelect(null) }
        newImageId = null
    }
    var pickerTarget by remember { mutableStateOf("face") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) cancelNewImage() else pending = uri to pickerTarget
    }
    pending?.let { (uri, target) -> BubbleImageCrop(uri, { pending = null; cancelNewImage() }, { bitmap -> pending = null; newImageId = null; onImport(bitmap, target) }) }
    fun save(next: BubbleDecoration) { onChange(next); onSave(next) }
    fun add(image: Boolean) {
        if (value.components.size >= 8 || busy) return
        val item = BubbleComponent(UUID.randomUUID().toString(), emoji = if (image) "" else "✨",
            corner = listOf("tr", "bl", "br", "tl")[value.components.size % 4])
        save(value.copy(components = value.components + item))
        onSelect(item.id)
        adding = false
        if (image) { newImageId = item.id; pickerTarget = item.id; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("小脸", selected == "face") { onSelect("face") }
        Chip("星点", selected == "star") { onSelect("star") }
        value.components.forEachIndexed { index, item ->
            Chip(item.emoji.ifEmpty { "图片 ${index + 1}" }, selected == item.id) { onSelect(item.id) }
        }
        Chip("＋ 添加装饰", adding) { adding = !adding }
    }
    if (adding) {
        if (value.components.size >= 8) Text("最多添加 8 个独立装饰。")
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Emoji", false) { add(false) }
            Chip("相册图片", false) { add(true) }
        }
    }
    val item = when(selected) {
        "face" -> BubbleComponent("face", value.faceEmoji, value.faceImage, value.faceShape, value.faceEnabled,
            value.faceSize, value.faceCorner, value.faceOffsetX, value.faceDistance)
        "star" -> BubbleComponent("star", value.starEmoji, value.starImage, value.starShape, value.starsEnabled,
            value.starSize, value.starCorner, value.starOffsetX, value.starDistance)
        else -> value.components.find { it.id == selected }
    }
    if (item != null) key(item.id) {
        fun updated(next: BubbleComponent): BubbleDecoration = when(item.id) {
            "face" -> value.copy(faceEmoji = next.emoji, faceImage = next.image, faceShape = next.shape,
                faceEnabled = next.enabled, faceSize = next.size, faceCorner = next.corner, faceOffsetX = next.offsetX, faceDistance = next.distance)
            "star" -> value.copy(starEmoji = next.emoji, starImage = next.image, starShape = next.shape,
                starsEnabled = next.enabled, starSize = next.size, starCorner = next.corner, starOffsetX = next.offsetX, starDistance = next.distance)
            else -> value.copy(components = value.components.map { if (it.id == item.id) next else it })
        }
        fun commit(next: BubbleComponent) = save(updated(next))
        var emojiDraft by remember(item.emoji) { mutableStateOf(item.emoji) }
        var emojiOpen by remember { mutableStateOf(false) }
        var fine by remember { mutableStateOf(false) }
        Text("只调整当前选中的装饰", color = LocalGlassPalette.current.contentSecondary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (emojiOpen) "收起 Emoji" else "换 Emoji", emojiOpen) { emojiOpen = !emojiOpen }
            Chip(if (busy) "正在导入…" else "换图片", false) { if (!busy) { pickerTarget = item.id; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
            Chip(if (item.enabled) "隐藏" else "显示", false) { commit(item.copy(enabled = !item.enabled)) }
            Chip("删除", false) {
                if (item.id == "face" || item.id == "star") commit(item.copy(enabled = false, image = null, emoji = ""))
                else { save(value.copy(components = value.components.filter { it.id != item.id })); onSelect(null) }
            }
        }
        if (emojiOpen) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("🥹", "🐰", "🌷", "🫧", "🎀", "✨", "🦋", "💗").forEach { emoji -> Chip(emoji, item.emoji == emoji) { commit(item.copy(emoji = emoji, image = null, enabled = true, size = item.size.coerceAtLeast(16))) } }
            }
            OutlinedTextField(emojiDraft, { if (it.length <= 64 && it.none { ch -> ch.isISOControl() }) emojiDraft = it },
                singleLine = true, label = { Text("输入一个 Emoji") }, modifier = Modifier.fillMaxWidth())
            Chip("应用 Emoji", false) { if (emojiDraft.isNotBlank()) commit(item.copy(emoji = emojiDraft.trim(), image = null, enabled = true, size = item.size.coerceAtLeast(16))) }
        }
        val minSize = if (item.id == "face") 16f else if (item.id == "star") 4f else 8f
        val maxSize = if (item.id == "star" && item.image == null && item.emoji.isEmpty()) 12f else if (item.id == "face" || item.id == "star") 32f else 40f
        Text("大小：${item.size}", color = LocalGlassPalette.current.contentSecondary, fontSize = 12.sp)
        Slider(item.size.toFloat().coerceIn(minSize, maxSize), { onChange(updated(item.copy(size = it.roundToInt()))) }, valueRange = minSize..maxSize,
            onValueChangeFinished = { onSave(value) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("auto" to "跟随主题", "tl" to "左上", "tr" to "右上", "bl" to "左下", "br" to "右下").forEach { (id, name) -> Chip(name, item.corner == id) { commit(item.copy(corner = id, offsetX = 0)) } }
        }
        Chip(if (fine) "收起精细调整" else "精细调整", fine) { fine = !fine }
        if (fine) {
            Text("左右偏移：${item.offsetX}", color = LocalGlassPalette.current.contentSecondary, fontSize = 12.sp)
            Slider(item.offsetX.toFloat(), { onChange(updated(item.copy(offsetX = it.roundToInt()))) }, valueRange = -64f..64f, onValueChangeFinished = { onSave(value) })
            Text("上下偏移：${item.distance}", color = LocalGlassPalette.current.contentSecondary, fontSize = 12.sp)
            Slider(item.distance.toFloat(), { onChange(updated(item.copy(distance = it.roundToInt()))) }, valueRange = -8f..16f, onValueChangeFinished = { onSave(value) })
            if (item.image != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("original" to "原图", "circle" to "圆形", "rounded" to "圆角").forEach { (id, name) -> Chip(name, item.shape == id) { commit(item.copy(shape = id)) } }
            }
            Chip("恢复位置", false) { commit(item.copy(corner = "auto", offsetX = 0, distance = 0)) }
        }
    }
    error?.let { Text(it, color = LocalGlassPalette.current.error, fontSize = 12.sp) }
}
