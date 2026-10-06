package com.cleo.cleos.ui.settings

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cleo.cleos.ui.common.appContainer
import kotlin.math.min
import kotlin.math.roundToInt

/** Crop first, then inspect the actual component before saving it. */
@Composable
internal fun BubbleImageCrop(uri: Uri, onDismiss: () -> Unit, onApply: (Bitmap) -> Unit) {
    val store = appContainer().images
    val source by produceState<Result<Bitmap>?>(null, uri) { value = runCatching { store.decode(uri, 2048) } }
    var preview by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var start by remember(uri) { mutableStateOf(Offset.Zero) }
    var end by remember(uri) { mutableStateOf(Offset(1f, 1f)) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var submitting by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (!submitting) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF202127)).systemBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (preview == null) "1 · 裁剪装饰" else "2 · 确认预览", color = Color.White)
            Text(if (preview == null) "在图片上拖出方框，只保留想用的小组件。" else "确认保留下来的部分。背景也会保留，可返回重新裁剪。", color = Color.LightGray)
            val bitmap = source?.getOrNull()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (preview != null) {
                    Image(preview!!.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
                } else if (bitmap != null) {
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    // Input and overlay use the same fitted image rectangle, including letterboxing.
                    fun geometry(): Triple<Float, Float, Float> {
                        val scale = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
                        return Triple(scale, (size.width - bitmap.width * scale) / 2, (size.height - bitmap.height * scale) / 2)
                    }
                    fun point(p: Offset): Offset {
                        val (scale, left, top) = geometry()
                        return Offset(((p.x - left) / (bitmap.width * scale)).coerceIn(0f, 1f),
                            ((p.y - top) / (bitmap.height * scale)).coerceIn(0f, 1f))
                    }
                    Canvas(Modifier.fillMaxSize().pointerInput(bitmap) {
                        detectDragGestures(onDragStart = { start = point(it); end = start }) { change, _ ->
                            change.consume(); end = point(change.position)
                        }
                    }) {
                        size = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())
                        val (scale, left, top) = geometry()
                        drawImage(image, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt()))
                        val rect = Rect(left + minOf(start.x, end.x) * bitmap.width * scale,
                            top + minOf(start.y, end.y) * bitmap.height * scale,
                            left + maxOf(start.x, end.x) * bitmap.width * scale,
                            top + maxOf(start.y, end.y) * bitmap.height * scale)
                        drawRect(Color.White, rect.topLeft, rect.size, style = Stroke(2.dp.toPx()))
                    }
                } else Text(if (source == null) "正在打开图片…" else "图片打不开，请换一张试试。", color = Color.White)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Chip(if (preview == null) "取消" else "重新裁剪", false) {
                    if (!submitting) { if (preview == null) onDismiss() else preview = null }
                }
                if (bitmap != null) Chip(if (preview == null) "预览选区" else "应用装饰", true) {
                    if (!submitting) {
                        if (preview != null) { submitting = true; onApply(preview!!) }
                        else {
                            val left = (minOf(start.x, end.x) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                            val top = (minOf(start.y, end.y) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                            val right = (maxOf(start.x, end.x) * bitmap.width).roundToInt().coerceIn(left + 1, bitmap.width)
                            val bottom = (maxOf(start.y, end.y) * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height)
                            val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                            val ratio = min(1f, 512f / maxOf(cropped.width, cropped.height))
                            preview = if (ratio < 1f) Bitmap.createScaledBitmap(cropped,
                                (cropped.width * ratio).roundToInt().coerceAtLeast(1), (cropped.height * ratio).roundToInt().coerceAtLeast(1), true) else cropped
                        }
                    }
                }
            }
        }
    }
}
