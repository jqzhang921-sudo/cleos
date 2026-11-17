package com.cleo.cleos.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.LruCache
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.MarkwonTheme
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image as MarkdownImage
import org.commonmark.node.SoftLineBreak
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable
import java.net.URI
import java.util.concurrent.Executors
import kotlin.math.roundToInt

internal data class MessageRendering(val enabled: Boolean = true, val colors: Boolean = false)
internal val LocalMessageRendering = staticCompositionLocalOf { MessageRendering() }

/** One bounded worker: the legacy math library has process-global font/parser caches. */
internal object MessageTypesetter {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "message-typesetter").apply { isDaemon = true } }.asCoroutineDispatcher()
    private data class Key(val tex: String, val size: Float, val color: Int)
    private val bitmaps = object : LruCache<Key, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }
    data class Picture(val raw: String, val bitmap: Bitmap)
    data class Result(val text: Spanned, val pictures: List<Picture>)

    private fun tint(name: String?, ink: Int, enabled: Boolean): Int {
        if (!enabled || name == null) return ink
        val darkInk = androidx.core.graphics.ColorUtils.calculateLuminance(ink) < .45
        val hues = mapOf("red" to 0f, "orange" to 28f, "yellow" to 45f, "green" to 135f,
            "blue" to 215f, "purple" to 275f, "violet" to 275f, "pink" to 325f, "teal" to 175f)
        val hue = hues[name.lowercase()] ?: if (Regex("#[0-9a-fA-F]{6}").matches(name)) {
            val hsl = FloatArray(3)
            androidx.core.graphics.ColorUtils.colorToHSL(android.graphics.Color.parseColor(name), hsl)
            hsl[0]
        } else return ink
        return androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, .52f, if (darkInk) .31f else .79f))
    }

    suspend fun render(context: Context, source: String, px: Float, ink: Int, colors: Boolean, width: Int): Result = withContext(worker) {
        val plan = MessageMarkup.scan(source)
        val markwon = Markwon.builder(context.applicationContext).usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.headingTextSizeMultipliers(floatArrayOf(1.12f, 1.08f, 1.04f, 1f, 1f, 1f))
                    .headingBreakHeight(0).blockMargin((px * .8f).roundToInt())
                    .codeTextColor(ink).codeBlockTextColor(ink).linkColor(ink)
            }
            override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                builder.on(MarkdownImage::class.java) { visitor, image -> visitor.visitChildren(image) }
                builder.on(HtmlInline::class.java) { visitor, node -> visitor.builder().append(node.literal) }
                builder.on(HtmlBlock::class.java) { visitor, node -> visitor.builder().append(node.literal) }
                builder.on(SoftLineBreak::class.java) { visitor, _ -> visitor.builder().append('\n') }
                builder.on(HardLineBreak::class.java) { visitor, _ -> visitor.builder().append('\n') }
            }
        }).build()
        val text = SpannableStringBuilder(markwon.toMarkdown(plan.markdown))
        val pictures = mutableListOf<Picture>()
        fun replaceStyle(at: Int, length: Int, fragment: LatexTextStyles.Fragment) {
            text.replace(at, at + length, fragment.text)
            var cursor = at
            fragment.runs.forEach { run ->
                val end = cursor + run.text.length
                val style = run.style
                if (style.bold || style.italic) text.setSpan(StyleSpan((if (style.bold) Typeface.BOLD else 0) or
                    (if (style.italic) Typeface.ITALIC else 0)), cursor, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (style.scale != 1f) text.setSpan(RelativeSizeSpan(style.scale), cursor, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (colors && style.color != null) text.setSpan(ForegroundColorSpan(tint(style.color, ink, true)), cursor, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                cursor = end
            }
        }
        plan.styles.forEach { region ->
            val at = text.indexOf(region.token)
            if (at >= 0) replaceStyle(at, region.token.length, region.fragment)
        }
        plan.formulas.forEach { formula ->
            val at = text.indexOf(formula.token)
            if (at < 0) return@forEach
            val styled = LatexTextStyles.whole(formula.tex)
            if (styled != null) {
                replaceStyle(at, formula.token.length, styled)
                return@forEach
            }
            text.replace(at, at + formula.token.length, formula.raw)
            val (tex, requested) = MessageMarkup.withoutMathColors(formula.tex)
            if (!MessageMarkup.safe(tex) || Regex("""\\(?:color|textcolor)\b""").containsMatchIn(tex)) return@forEach
            val bitmap = runCatching {
                val color = tint(requested, ink, colors)
                val key = Key(tex, px, color)
                bitmaps[key] ?: run {
                    JLatexMathAndroid.init(context.applicationContext)
                    val drawable = JLatexMathDrawable.builder(tex).textSize(px).color(color).padding(3).build()
                    val w = drawable.intrinsicWidth; val h = drawable.intrinsicHeight
                    // Check before allocating. Oversized/invalid math stays selectable original text.
                    require(w in 1..4096 && h in 1..1024 && w.toLong() * h <= 1_000_000)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        drawable.draw(Canvas(it)); bitmaps.put(key, it)
                    }
                }
            }.getOrNull() ?: return@forEach
            val picture = Picture(formula.raw, bitmap)
            pictures += picture
            val scale = minOf(1f, width.coerceAtLeast(1).toFloat() / bitmap.width)
            val drawable = BitmapDrawable(context.resources, bitmap).apply {
                setBounds(0, 0, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1))
            }
            text.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BASELINE), at, at + formula.raw.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(FormulaTap(picture), at, at + formula.raw.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        plan.tints.forEach { range ->
            val a = text.indexOf(range.start); val b = text.indexOf(range.end)
            if (a >= 0 && b >= a + range.start.length) {
                text.delete(b, b + range.end.length); text.delete(a, a + range.start.length)
                if (colors) text.setSpan(ForegroundColorSpan(tint(range.requested, ink, true)), a, b - range.start.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        fun sanitizeLinks() {
            text.getSpans(0, text.length, URLSpan::class.java).forEach { span ->
                val a = text.getSpanStart(span); val b = text.getSpanEnd(span)
                text.removeSpan(span)
                if (safeUrl(span.url)) text.setSpan(SafeLink(span.url), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // Linkify removes URLSpan, so first preserve labelled Markdown links as our own spans.
        sanitizeLinks()
        Linkify.addLinks(text, Linkify.WEB_URLS)
        sanitizeLinks()
        Result(text, pictures)
    }

    internal fun safeUrl(url: String): Boolean = runCatching {
        val u = URI(url)
        u.scheme?.lowercase() in setOf("http", "https") && !u.host.isNullOrBlank() && u.rawUserInfo == null
    }.getOrDefault(false)
}

private class FormulaTap(val picture: MessageTypesetter.Picture) : ClickableSpan() {
    override fun onClick(widget: android.view.View) = Unit
    override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
}
private class SafeLink(val url: String) : ClickableSpan() {
    override fun onClick(widget: android.view.View) = Unit
    override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = true }
}

/** Native text only: Compose owns gestures, including the parent bubble's long press. */
private class MessageTextView(context: Context) : TextView(context) {
    var open: (ClickableSpan) -> Unit = {}
    init { setPadding(0, 0, 0, 0); includeFontPadding = false; isFocusable = false; isLongClickable = false; setTextIsSelectable(false) }
    fun spanAt(touchX: Float, touchY: Float): ClickableSpan? {
        val l = layout ?: return null
        val text = text as? Spanned ?: return null
        val y = touchY.toInt() - totalPaddingTop + scrollY
        if (y !in 0 until l.height) return null
        val line = l.getLineForVertical(y)
        val x = touchX - totalPaddingLeft + scrollX
        if (x < l.getLineLeft(line) || x > l.getLineRight(line)) return null
        val offset = l.getOffsetForHorizontal(line, x)
        return text.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
    }
}

@Composable
internal fun RichMessageText(source: String, color: Color, style: TextStyle, modifier: Modifier, fallback: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val options = LocalMessageRendering.current
    val px = with(density) { style.fontSize.toPx() }
    val linePx = with(density) { style.lineHeight.toPx() }
    val uriHandler = LocalUriHandler.current
    var nativeView by remember { mutableStateOf<MessageTextView?>(null) }
    var enlarged by remember(source, color, px) { mutableStateOf<MessageTypesetter.Picture?>(null) }
    BoxWithConstraints(modifier) {
        val width = with(density) { maxWidth.toPx() }.let { if (it.isFinite()) it.toInt() else 1000 }
        val result by produceState<MessageTypesetter.Result?>(null, source, px, color, options.colors, width) {
            value = null
            value = runCatching { MessageTypesetter.render(context, source, px, color.toArgb(), options.colors, width) }.getOrNull()
        }
        val ready = result
        if (ready == null) fallback() else AndroidView(
            modifier = Modifier.pointerInput(ready) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val view = nativeView
                    val target = view?.spanAt(down.position.x, down.position.y)
                    if (target != null) {
                        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        if (up != null && up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis &&
                            target === view.spanAt(up.position.x, up.position.y)) {
                            up.consume()
                            view.open(target)
                        }
                    }
                }
            }, factory = { MessageTextView(it).also { nativeView = it } }, update = { view ->
            view.setTextColor(color.toArgb())
            view.setLinkTextColor(color.toArgb())
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            view.typeface = when (style.fontFamily) { FontFamily.Serif -> Typeface.SERIF; FontFamily.Monospace -> Typeface.MONOSPACE; else -> Typeface.DEFAULT }
            if (linePx.isFinite()) view.setLineSpacing((linePx - px).coerceAtLeast(0f), 1f)
            if (view.text !== ready.text) view.text = ready.text
            view.open = { span -> when (span) {
                is FormulaTap -> enlarged = span.picture
                is SafeLink -> runCatching { uriHandler.openUri(span.url) }.onFailure { android.widget.Toast.makeText(context, "链接暂时打不开，可以长按消息复制", android.widget.Toast.LENGTH_SHORT).show() }
            } }
        })
    }
    enlarged?.let { picture ->
        val palette = LocalGlassPalette.current
        Dialog(onDismissRequest = { enlarged = null }) {
            GlassSurface(style = palette.card, shape = GlassShape.Rounded(24.dp), contentPadding = PaddingValues(18.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("公式 · 左右滑动查看", color = palette.content)
                    Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                        Image(picture.bitmap.asImageBitmap(), contentDescription = picture.raw,
                            modifier = Modifier.size(with(density) { picture.bitmap.width.toDp() }, with(density) { picture.bitmap.height.toDp() }))
                    }
                    TextButton(onClick = { enlarged = null }) { Text("关闭", color = palette.content) }
                }
            }
        }
    }
}
