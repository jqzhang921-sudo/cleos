package com.cleo.cleos.ui.settings

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.max
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
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var pan by remember(uri) { mutableStateOf(Offset.Zero) }
    var frameWidth by remember(uri) { mutableFloatStateOf(0.5f) }
    var frameHeight by remember(uri) { mutableFloatStateOf(0.3f) }
    val handlePx = with(LocalDensity.current) { 40.dp.toPx() }
    val minimumPx = with(LocalDensity.current) { 48.dp.toPx() }
    var selection by remember(uri) { mutableStateOf(Rect.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var submitting by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (!submitting) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF202127)).systemBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (preview == null) "1 · 裁剪装饰" else "2 · 确认预览", color = Color.White)
            Text(if (preview == null) "双指放大、单指移动图片；拖动四角调整选框。" else "确认保留下来的部分。背景也会保留，可返回重新裁剪。", color = Color.LightGray)
            val bitmap = source?.getOrNull()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (preview != null) {
                    Image(preview!!.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
                } else if (bitmap != null) {
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    fun frame() = Rect((size.width * (1 - frameWidth)) / 2, (size.height * (1 - frameHeight)) / 2,
                        (size.width * (1 + frameWidth)) / 2, (size.height * (1 + frameHeight)) / 2)
                    fun scale(): Float = max(min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height) * zoom,
                        max(frame().width / bitmap.width, frame().height / bitmap.height))
                    fun clampPan(candidate: Offset): Offset {
                        val s = scale()
                        val f = frame()
                        return Offset(candidate.x.coerceIn(-(bitmap.width * s - f.width).coerceAtLeast(0f) / 2,
                            (bitmap.width * s - f.width).coerceAtLeast(0f) / 2),
                            candidate.y.coerceIn(-(bitmap.height * s - f.height).coerceAtLeast(0f) / 2,
                                (bitmap.height * s - f.height).coerceAtLeast(0f) / 2))
                    }
                    fun sourceRect(): Rect {
                        val s = scale().coerceAtLeast(0.0001f)
                        val left = size.width / 2f + pan.x - bitmap.width * s / 2
                        val top = size.height / 2f + pan.y - bitmap.height * s / 2
                        val f = frame()
                        return Rect(((f.left - left) / s).coerceIn(0f, bitmap.width - 1f),
                            ((f.top - top) / s).coerceIn(0f, bitmap.height - 1f),
                            ((f.right - left) / s).coerceIn(1f, bitmap.width.toFloat()),
                            ((f.bottom - top) / s).coerceIn(1f, bitmap.height.toFloat()))
                    }
                    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
                        Canvas(Modifier.fillMaxSize().pointerInput(bitmap) {
                            detectTransformGestures { centroid, delta, factor, _ ->
                                val oldScale = scale()
                                zoom = (zoom * factor).coerceIn(1f, 20f)
                                val ratio = scale() / oldScale.coerceAtLeast(0.0001f)
                                val centre = Offset(size.width / 2f, size.height / 2f)
                                pan = clampPan((pan + centre - centroid) * ratio + centroid - centre + delta)
                            }
                        }) {
                            val s = scale()
                            drawImage(image, dstOffset = IntOffset((size.width / 2f + pan.x - bitmap.width * s / 2).roundToInt(),
                                (size.height / 2f + pan.y - bitmap.height * s / 2).roundToInt()),
                                dstSize = IntSize((bitmap.width * s).roundToInt().coerceAtLeast(1), (bitmap.height * s).roundToInt().coerceAtLeast(1)))
                            val f = frame()
                            val shade = Color.Black.copy(alpha = 0.6f)
                            drawRect(shade, Offset.Zero, androidx.compose.ui.geometry.Size(size.width.toFloat(), f.top))
                            drawRect(shade, Offset(0f, f.bottom), androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height - f.bottom))
                            drawRect(shade, Offset(0f, f.top), androidx.compose.ui.geometry.Size(f.left, f.height))
                            drawRect(shade, Offset(f.right, f.top), androidx.compose.ui.geometry.Size(size.width - f.right, f.height))
                            drawRect(Color.White, f.topLeft, f.size, style = Stroke(2.dp.toPx()))
                        }
                        if (size != IntSize.Zero) {
                            for (horizontal in listOf(-1, 1)) for (vertical in listOf(-1, 1)) {
                                val f = frame()
                                val point = Offset(if (horizontal < 0) f.left else f.right, if (vertical < 0) f.top else f.bottom)
                                Box(Modifier.offset { IntOffset((point.x - handlePx / 2).roundToInt(), (point.y - handlePx / 2).roundToInt()) }
                                    .size(40.dp).pointerInput(bitmap, horizontal, vertical) {
                                        detectDragGestures { change, delta ->
                                            change.consume()
                                            frameWidth = (frameWidth + 2 * horizontal * delta.x / size.width)
                                                .coerceIn((minimumPx / size.width).coerceAtMost(0.9f), 0.9f)
                                            frameHeight = (frameHeight + 2 * vertical * delta.y / size.height)
                                                .coerceIn((minimumPx / size.height).coerceAtMost(0.9f), 0.9f)
                                            pan = clampPan(pan)
                                        }
                                    }, contentAlignment = Alignment.Center) {
                                    Canvas(Modifier.size(10.dp)) { drawCircle(Color.White) }
                                }
                            }
                        }
                    }
                    // Update the source selection used by both live preview and the confirm button.
                    SideEffect { selection = sourceRect() }
                } else Text(if (source == null) "正在打开图片…" else "图片打不开，请换一张试试。", color = Color.White)
            }
            if (preview == null && bitmap != null && selection.width > 0 && selection.height > 0) {
                Text("选区放大预览", color = Color.LightGray)
                Canvas(Modifier.fillMaxWidth().height(88.dp).background(Color(0xFF393A40))) {
                    val r = selection
                    val w = r.width.roundToInt().coerceAtLeast(1)
                    val h = r.height.roundToInt().coerceAtLeast(1)
                    val factor = min(this.size.width / w, this.size.height / h)
                    val target = IntSize((w * factor).roundToInt().coerceAtLeast(1), (h * factor).roundToInt().coerceAtLeast(1))
                    drawImage(bitmap.asImageBitmap(), srcOffset = IntOffset(r.left.toInt(), r.top.toInt()),
                        srcSize = IntSize(w.coerceAtMost(bitmap.width - r.left.toInt()), h.coerceAtMost(bitmap.height - r.top.toInt())),
                        dstOffset = IntOffset(((this.size.width - target.width) / 2).roundToInt(), ((this.size.height - target.height) / 2).roundToInt()), dstSize = target)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Chip(if (preview == null) "取消" else "重新裁剪", false) {
                    if (!submitting) { if (preview == null) onDismiss() else preview = null }
                }
                if (bitmap != null) Chip(if (preview == null) "预览选区" else "应用装饰", true) {
                    if (!submitting) {
                        if (preview != null) { submitting = true; onApply(preview!!) }
                        else {
                            val left = selection.left.toInt().coerceIn(0, bitmap.width - 1)
                            val top = selection.top.toInt().coerceIn(0, bitmap.height - 1)
                            val right = selection.right.roundToInt().coerceIn(left + 1, bitmap.width)
                            val bottom = selection.bottom.roundToInt().coerceIn(top + 1, bitmap.height)
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
