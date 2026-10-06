package com.cleo.cleos.ui.settings

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import com.cleo.cleos.data.BubbleBackgroundRemoval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.Slider
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.drawscope.clipRect
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
    var removalOpen by remember(uri) { mutableStateOf(false) }
    var removalEnabled by remember(uri) { mutableStateOf(false) }
    var backgroundSeed by remember(uri) { mutableStateOf<Int?>(null) }
    var tolerance by remember(uri) { mutableFloatStateOf(24f) }
    val processed by produceState<Result<Bitmap>?>(null, preview, backgroundSeed, tolerance.roundToInt(), removalEnabled) {
        value = null
        val original = preview
        val seed = backgroundSeed
        if (removalEnabled && original != null && seed != null) value = runCatching {
            withContext(Dispatchers.Default) {
                val pixels = IntArray(original.width * original.height)
                original.getPixels(pixels, 0, original.width, 0, 0, original.width, original.height)
                val result = BubbleBackgroundRemoval.remove(pixels, original.width, original.height, seed, tolerance.roundToInt())
                Bitmap.createBitmap(result, original.width, original.height, Bitmap.Config.ARGB_8888)
            }
        }
    }
    val displayed = processed?.getOrNull() ?: preview
    val processing = removalEnabled && backgroundSeed != null && processed == null
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
            Text(if (preview == null) "双指缩放、单指移动图片；拖动四角调整选框。框内空白会保存为透明。" else "确认保留下来的部分。背景也会保留，可返回重新裁剪。", color = Color.LightGray)
            val bitmap = source?.getOrNull()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (preview != null) {
                    val original = preview!!
                    var previewSize by remember { mutableStateOf(IntSize.Zero) }
                    Canvas(Modifier.fillMaxSize().onSizeChanged { previewSize = it }.pointerInput(original, removalOpen) {
                        if (removalOpen) detectTapGestures { point ->
                            val scale = min(previewSize.width.toFloat() / original.width, previewSize.height.toFloat() / original.height)
                            val x = ((point.x - (previewSize.width - original.width * scale) / 2) / scale).toInt()
                            val y = ((point.y - (previewSize.height - original.height * scale) / 2) / scale).toInt()
                            if (x in 0 until original.width && y in 0 until original.height && original.getPixel(x, y) ushr 24 != 0) {
                                backgroundSeed = y * original.width + x
                                removalEnabled = true
                            }
                        }
                    }) {
                        val scale = min(this.size.width / original.width, this.size.height / original.height)
                        val left = (this.size.width - original.width * scale) / 2
                        val top = (this.size.height - original.height * scale) / 2
                        val cell = 12.dp.toPx()
                        clipRect(left, top, left + original.width * scale, top + original.height * scale) {
                            for (row in 0..(this.size.height / cell).toInt()) for (col in 0..(this.size.width / cell).toInt())
                                drawRect(if ((row + col) % 2 == 0) Color(0xFF757575) else Color(0xFF515151),
                                    Offset(col * cell, row * cell), androidx.compose.ui.geometry.Size(cell, cell))
                            drawImage(displayed!!.asImageBitmap(), dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                                dstSize = IntSize((original.width * scale).roundToInt().coerceAtLeast(1), (original.height * scale).roundToInt().coerceAtLeast(1)))
                        }
                    }
                } else if (bitmap != null) {
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    fun frame() = Rect((size.width * (1 - frameWidth)) / 2, (size.height * (1 - frameHeight)) / 2,
                        (size.width * (1 + frameWidth)) / 2, (size.height * (1 + frameHeight)) / 2)
                    fun scale(): Float = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height) * zoom
                    fun clampPan(candidate: Offset): Offset {
                        val limitX = (bitmap.width * scale() + size.width) / 2
                        val limitY = (bitmap.height * scale() + size.height) / 2
                        return Offset(candidate.x.coerceIn(-limitX, limitX), candidate.y.coerceIn(-limitY, limitY))
                    }
                    fun sourceRect(): Rect {
                        val s = scale().coerceAtLeast(0.0001f)
                        val left = size.width / 2f + pan.x - bitmap.width * s / 2
                        val top = size.height / 2f + pan.y - bitmap.height * s / 2
                        val f = frame()
                        return Rect((f.left - left) / s,
                            (f.top - top) / s,
                            (f.right - left) / s,
                            (f.bottom - top) / s)
                    }
                    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
                        Canvas(Modifier.fillMaxSize().pointerInput(bitmap) {
                            detectTransformGestures { centroid, delta, factor, _ ->
                                val oldScale = scale()
                                zoom = (zoom * factor).coerceIn(0.25f, 20f)
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
                    val factor = min(this.size.width / r.width, this.size.height / r.height)
                    val dx = (this.size.width - r.width * factor) / 2
                    val dy = (this.size.height - r.height * factor) / 2
                    clipRect(dx, dy, dx + r.width * factor, dy + r.height * factor) {
                        drawImage(bitmap.asImageBitmap(), dstOffset = IntOffset((dx - r.left * factor).roundToInt(), (dy - r.top * factor).roundToInt()),
                            dstSize = IntSize((bitmap.width * factor).roundToInt().coerceAtLeast(1), (bitmap.height * factor).roundToInt().coerceAtLeast(1)))
                    }
                }
            }
            if (preview != null) {
                Chip(if (removalOpen) "收起去底" else "可选：去相近底色", removalOpen) { removalOpen = !removalOpen }
                if (removalOpen) {
                    Text("点一下图片中要去掉的底色，只处理相连区域。复杂照片、浅色轮廓可能需要保留原图。", color = Color.LightGray)
                    if (backgroundSeed != null) {
                        Text(if (processing) "正在处理…" else "容差：${tolerance.roundToInt()} · 越大去除范围越广", color = Color.White)
                        Slider(tolerance, { tolerance = it }, valueRange = 0f..100f)
                        Chip("恢复原图", false) { removalEnabled = false; backgroundSeed = null }
                    }
                    if (processed?.isFailure == true) Text("处理失败，可恢复原图重试。", color = Color.White)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Chip(if (preview == null) "取消" else "重新裁剪", false) {
                    if (!submitting) { if (preview == null) onDismiss() else { preview = null; backgroundSeed = null; removalEnabled = false } }
                }
                if (bitmap != null) Chip(if (preview == null) "预览选区" else "应用装饰", true) {
                    if (!submitting) {
                        if (preview != null) { if (!processing && processed?.isFailure != true) { submitting = true; onApply(displayed!!) } }
                        else {
                            val r = selection
                            if (r.width > 0 && r.height > 0) {
                                val ratio = min(1f, 512f / maxOf(r.width, r.height))
                                val cropped = Bitmap.createBitmap((r.width * ratio).roundToInt().coerceAtLeast(1),
                                    (r.height * ratio).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                android.graphics.Canvas(cropped).drawBitmap(bitmap, null,
                                    android.graphics.RectF(-r.left * ratio, -r.top * ratio,
                                        (bitmap.width - r.left) * ratio, (bitmap.height - r.top) * ratio),
                                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
                                preview = cropped
                            }
                        }
                    }
                }
            }
        }
    }
}
