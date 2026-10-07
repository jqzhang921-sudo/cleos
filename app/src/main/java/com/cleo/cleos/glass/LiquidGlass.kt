package com.cleo.cleos.glass

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws this node's background as liquid glass looking through [backdrop].
 * The node's own content (icons, text) is drawn on top, untouched.
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    style: GlassStyle,
    shape: GlassShape = GlassShape.Capsule,
    motion: GlassMotion? = null,
): Modifier = this then LiquidGlassElement(backdrop, style, shape, motion)

/**
 * Lays the content out [extra] larger on every side than the space it is given, centred
 * on it, while reporting the given size to the parent. The wallpaper uses this so there
 * is real wallpaper beyond the screen edges for blur near the edges to take in.
 */
fun Modifier.overscan(extra: Dp): Modifier = layout { measurable, constraints ->
    val e = extra.roundToPx()
    val placeable = measurable.measure(
        Constraints.fixed(constraints.maxWidth + e * 2, constraints.maxHeight + e * 2),
    )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-e, -e) }
}

/** How far the wallpaper extends past the screen: more than any built-in style's reach. */
val WallpaperOverscan = 56.dp

private data class LiquidGlassElement(
    val backdrop: Backdrop,
    val style: GlassStyle,
    val shape: GlassShape,
    val motion: GlassMotion?,
) : ModifierNodeElement<LiquidGlassNode>() {
    override fun create() = LiquidGlassNode(backdrop, style, shape, motion)
    override fun update(node: LiquidGlassNode) = node.update(backdrop, style, shape, motion)
    override fun InspectorInfo.inspectableProperties() {
        name = "liquidGlass"
    }
}

private class LiquidGlassNode(
    private var backdrop: Backdrop,
    private var style: GlassStyle,
    private var shape: GlassShape,
    private var motion: GlassMotion?,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var layer: GraphicsLayer? = null
    private var shaderEffect: Any? = null
    private var lastPosition = Offset.Unspecified

    fun update(backdrop: Backdrop, style: GlassStyle, shape: GlassShape, motion: GlassMotion?) {
        this.backdrop = backdrop
        this.style = style
        this.shape = shape
        this.motion = motion
        invalidateDraw()
    }

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
        shaderEffect = null
        lastPosition = Offset.Unspecified
    }

    // A list item that scrolls is only re-placed, not re-drawn; its old recording would
    // keep showing the backdrop from where it used to be. Moving must mean redrawing.
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        if (p != lastPosition) {
            lastPosition = p
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawGlass()
        drawContent()
    }

    private fun ContentDrawScope.drawGlass() {
        backdrop.version // subscribe: redraw when the source attaches or moves
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return

        val m = motion
        val swellX = (m?.swellX ?: 0f) * density
        val swellY = (m?.swellY ?: 0f) * density
        val glassW = w + swellX * 2f
        val glassH = h + swellY * 2f
        val radius = when (shape) {
            GlassShape.Capsule -> min(glassW, glassH) / 2f
            else -> (shape.radiusPx(size, this) + min(swellX, swellY)).coerceAtMost(min(glassW, glassH) / 2f)
        }

        val source = backdrop.layer
        val sourceCoords = backdrop.coordinates
        val l = layer
        if (source == null || sourceCoords == null || !sourceCoords.isAttached || l == null) {
            drawRoundRect(
                color = style.tint.copy(alpha = max(style.tint.alpha, 0.55f)),
                topLeft = Offset(-swellX, -swellY),
                size = Size(glassW, glassH),
                cornerRadius = CornerRadius(radius),
            )
            return
        }
        val glassCoords = requireLayoutCoordinates()
        // Popups have their own layout root. Screen coordinates bridge the windows;
        // localPositionOf remains preferable within a root (it accounts for transforms).
        val offset = if (sourceCoords.findRootCoordinates() == glassCoords.findRootCoordinates()) {
            sourceCoords.localPositionOf(glassCoords, Offset.Zero)
        } else {
            glassCoords.localToScreen(Offset.Zero) - sourceCoords.localToScreen(Offset.Zero)
        }
        if (!offset.x.isFinite() || !offset.y.isFinite()) return

        val blurPx = style.blur.toPx()
        val refractionPx = style.refraction.toPx()
        val reach = abs(refractionPx) * (1f + style.dispersion)
        val shadowReach =
            if (style.shadowAlpha > 0f) style.shadowRadius.toPx() + abs(style.shadowOffsetY.toPx()) else 0f
        // NESTED_MARGIN: this layer is also where any glass *inside the backdrop* gets
        // rendered again, and that glass cannot see past this layer's edge either. The
        // margin keeps its reach inside the layer (e.g. the bar seen through the tab lens).
        // A flat backdrop has no glass in it, so nothing to make room for.
        val nested = if (backdrop.flat) 0f else NESTED_MARGIN.toPx()
        // A second shape sits outside this node, usually well outside it, and the waist
        // between them bulges past both by up to the reach. None of that is drawn unless
        // the layer covers it, so the padding has to grow by however far the blob sticks
        // out on its furthest side. Padding is the same on all four sides, so one number.
        val blob = m?.blob?.takeIf { it.merge > 0f && it.width > 0f && it.height > 0f }
        val blobPad = if (blob == null) {
            0f
        } else {
            val halfW = blob.width / 2f
            val halfH = blob.height / 2f
            max(
                max(halfW - blob.centerX, blob.centerX + halfW - w),
                max(halfH - blob.centerY, blob.centerY + halfH - h),
            ).coerceAtLeast(0f) + blob.merge
        }
        // 1.5x the blur radius covers the kernel (sigma is about 0.58 x radius, and 3 sigma
        // is where the tail stops mattering) without paying for 2x.
        val pad =
            ceil(max(reach, shadowReach) + max(swellX, swellY) + blurPx * 1.5f + nested + blobPad + 2f).toInt()
        val layerSize = IntSize(ceil(w).toInt() + pad * 2, ceil(h).toInt() + pad * 2)

        l.topLeft = IntOffset(-pad, -pad)

        if (Build.VERSION.SDK_INT >= 33) {
            val tp = m?.touch ?: Offset.Unspecified
            val glow = m?.glow ?: 0f
            val touching = tp != Offset.Unspecified && glow > 0f

            // The on-screen part of the layer, in layer coordinates. A side is only
            // clamped when the layer actually crosses the window edge there; otherwise it
            // stays "unbounded" so that a bubble scrolling through the middle of the
            // screen keeps the same params and reuses its effect instead of rebuilding it.
            val coords = requireLayoutCoordinates()
            val inWindow = coords.positionInWindow()
            val window = coords.findRootCoordinates().size
            val left = inWindow.x - pad
            val top = inWindow.y - pad
            val visL = if (left < 0f) -left else -UNBOUNDED
            val visT = if (top < 0f) -top else -UNBOUNDED
            val visR = if (left + layerSize.width > window.width) window.width - left else UNBOUNDED
            val visB = if (top + layerSize.height > window.height) window.height - top else UNBOUNDED

            val params = GlassParams(
                visL = visL,
                visT = visT,
                visR = visR,
                visB = visB,
                originX = pad - swellX,
                originY = pad - swellY,
                width = glassW,
                height = glassH,
                radius = radius,
                bevel = style.bevel.toPx(),
                refraction = refractionPx,
                zoom = style.zoom,
                dispersion = style.dispersion,
                tint = style.tint,
                saturation = style.saturation,
                lift = style.lift,
                highlight = style.highlight,
                rimWidth = style.rimWidth.toPx(),
                shadowAlpha = style.shadowAlpha,
                shadowRadius = max(style.shadowRadius.toPx(), 1f),
                shadowOffsetY = style.shadowOffsetY.toPx(),
                touchX = if (touching) tp.x + pad else 0f,
                touchY = if (touching) tp.y + pad else 0f,
                touchRadius = max(max(glassW, glassH) * 0.45f, 1f),
                touchGlow = if (touching) glow else 0f,
                // Blob centre relative to the glass centre: the shader works in that
                // space, and the glass centre is the node centre however much it swelled.
                blobX = if (blob == null) 0f else blob.centerX - w / 2f,
                blobY = if (blob == null) 0f else blob.centerY - h / 2f,
                blobHalfW = if (blob == null) 0f else blob.width / 2f,
                blobHalfH = if (blob == null) 0f else blob.height / 2f,
                blobRadius = blob?.radius ?: 0f,
                blobMerge = blob?.merge ?: 0f,
                blur = blurPx,
            )
            val fx = (shaderEffect as? GlassShaderEffect) ?: GlassShaderEffect().also { shaderEffect = it }
            l.renderEffect = fx.effect(params)
            l.record(layerSize) {
                translate(pad - offset.x, pad - offset.y) { drawLayer(source) }
            }
            drawLayer(l)
        } else {
            drawFallbackGlass(l, source, offset, pad, layerSize, blurPx, radius, swellX, swellY)
        }
    }

    /**
     * Before API 33 there is no runtime shader, so no refraction: blur (API 31+), a tint
     * heavy enough to stand in for the missing vibrancy, and a painted rim.
     */
    private fun DrawScope.drawFallbackGlass(
        l: GraphicsLayer,
        source: GraphicsLayer,
        offset: Offset,
        pad: Int,
        layerSize: IntSize,
        blurPx: Float,
        radius: Float,
        swellX: Float,
        swellY: Float,
    ) {
        l.renderEffect =
            if (Build.VERSION.SDK_INT >= 31 && blurPx > 0.5f) BlurEffect(blurPx, blurPx, TileMode.Clamp) else null
        l.record(layerSize) {
            translate(pad - offset.x, pad - offset.y) { drawLayer(source) }
        }
        val rect = RoundRect(-swellX, -swellY, size.width + swellX, size.height + swellY, CornerRadius(radius))
        val outline = Path().apply { addRoundRect(rect) }
        val minTint = if (Build.VERSION.SDK_INT >= 31) 0.3f else 0.55f
        clipPath(outline) {
            drawLayer(l)
            drawRect(style.tint.copy(alpha = max(style.tint.alpha, minTint)))
        }
        drawRoundRect(
            brush = Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.7f * style.highlight),
                0.5f to Color.White.copy(alpha = 0.08f),
                1f to Color.White.copy(alpha = 0.35f * style.highlight),
            ),
            topLeft = Offset(-swellX, -swellY),
            size = Size(size.width + swellX * 2f, size.height + swellY * 2f),
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = style.rimWidth.toPx()),
        )
    }
}

private const val UNBOUNDED = 1e6f

/** Larger than the reach (refraction x (1 + dispersion)) of any built-in style. */
private val NESTED_MARGIN = 32.dp

private data class GlassParams(
    val visL: Float,
    val visT: Float,
    val visR: Float,
    val visB: Float,
    val originX: Float,
    val originY: Float,
    val width: Float,
    val height: Float,
    val radius: Float,
    val bevel: Float,
    val refraction: Float,
    val zoom: Float,
    val dispersion: Float,
    val tint: Color,
    val saturation: Float,
    val lift: Float,
    val highlight: Float,
    val rimWidth: Float,
    val shadowAlpha: Float,
    val shadowRadius: Float,
    val shadowOffsetY: Float,
    val touchX: Float,
    val touchY: Float,
    val touchRadius: Float,
    val touchGlow: Float,
    val blobX: Float,
    val blobY: Float,
    val blobHalfW: Float,
    val blobHalfH: Float,
    val blobRadius: Float,
    val blobMerge: Float,
    val blur: Float,
)

/**
 * One RuntimeShader per piece of glass. A RenderEffect copies the uniforms when it is
 * created, so new values need a new effect; while nothing changes (a bubble scrolling
 * by only changes where it samples, which lives in the layer's translation, not in the
 * uniforms) the previous effect is reused instead of allocating one per frame.
 */
@RequiresApi(33)
private class GlassShaderEffect {
    private val shader = RuntimeShader(LIQUID_GLASS_AGSL)
    private var lastParams: GlassParams? = null
    private var lastEffect: RenderEffect? = null

    fun effect(p: GlassParams): RenderEffect {
        val cached = lastEffect
        if (cached != null && p == lastParams) return cached
        shader.setFloatUniform("visible", p.visL, p.visT, p.visR, p.visB)
        shader.setFloatUniform("origin", p.originX, p.originY)
        shader.setFloatUniform("size", p.width, p.height)
        shader.setFloatUniform("radius", p.radius)
        shader.setFloatUniform("bevel", p.bevel)
        shader.setFloatUniform("refraction", p.refraction)
        shader.setFloatUniform("zoom", max(p.zoom, 0.01f))
        shader.setFloatUniform("dispersion", p.dispersion)
        shader.setFloatUniform("tint", p.tint.red, p.tint.green, p.tint.blue, p.tint.alpha)
        shader.setFloatUniform("saturation", p.saturation)
        shader.setFloatUniform("lift", p.lift)
        shader.setFloatUniform("highlight", p.highlight)
        shader.setFloatUniform("rimWidth", max(p.rimWidth, 0.01f))
        shader.setFloatUniform("light", LIGHT_X, LIGHT_Y)
        shader.setFloatUniform("shadow", p.shadowAlpha, p.shadowRadius, p.shadowOffsetY)
        shader.setFloatUniform("touch", p.touchX, p.touchY, p.touchRadius, p.touchGlow)
        shader.setFloatUniform("blob", p.blobX, p.blobY, p.blobHalfW, p.blobHalfH)
        shader.setFloatUniform("blobShape", p.blobRadius, p.blobMerge)
        val glass = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
        val combined = if (p.blur > 0.5f) {
            android.graphics.RenderEffect.createChainEffect(
                glass,
                android.graphics.RenderEffect.createBlurEffect(p.blur, p.blur, Shader.TileMode.CLAMP),
            )
        } else {
            glass
        }
        return combined.asComposeRenderEffect().also {
            lastParams = p
            lastEffect = it
        }
    }

    private companion object {
        // Light from the upper left, a little more from above than from the side.
        const val LIGHT_X = -0.5547f
        const val LIGHT_Y = -0.8321f
    }
}

