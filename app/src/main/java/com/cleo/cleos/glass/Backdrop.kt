package com.cleo.cleos.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Something glass can look through.
 *
 * The source records whatever it draws into a [GraphicsLayer]. A glass element then draws
 * that same layer again, under its own blur and refraction. Drawing a layer is drawing a
 * reference to a RenderNode, not copying pixels, so when the source's content changes
 * (a list scrolls) the glass sees the new content on the very same frame.
 *
 * The one hard rule: a glass element must not sit *inside* the source it reads from.
 * The source would then contain the glass, which draws the source, which contains the
 * glass… Chrome that floats over a list therefore lives beside the list, not in it.
 */
@Stable
class Backdrop(
    /**
     * True when nothing recorded here is glass (the wallpaper). Glass reading a flat
     * backdrop can skip the extra margin that nested glass needs (see LiquidGlass), which
     * matters because that is most glass: every bubble and card reads the wallpaper.
     */
    val flat: Boolean = false,
) {
    internal var layer: GraphicsLayer? = null
    internal var coordinates: LayoutCoordinates? = null

    /**
     * Bumped whenever the source is (re)attached or moves. Glass reads it while drawing,
     * which subscribes the glass to redraw when it changes; without that the first frame,
     * drawn before the source had a position, would stay empty until something else
     * happened to invalidate it.
     */
    internal var version by mutableIntStateOf(0)
}

@Composable
fun rememberBackdrop(flat: Boolean = false): Backdrop = remember(flat) { Backdrop(flat) }

/**
 * Records this node's content (everything drawn inside it) for glass elsewhere to read.
 *
 * [behind]: what is already on screen underneath this node (usually the wallpaper). It is
 * added to the *recording* only, under the content, so glass reading this source sees
 * the whole picture; on screen only the content is drawn, since the real [behind] is
 * already there. Drawing a visible copy instead would both paint the screen twice and,
 * worse, re-render any glass inside the copy under the copy's clip, where it cannot see
 * past that clip and shows transparent streaks.
 *
 * [overscan] widens the recording past the node's bounds on every side, so the part of
 * [behind] that lies beyond the screen edge (the wallpaper is drawn larger than the
 * screen) is there for blur near the edge to use. The layer's RenderNode clips to its
 * bounds, so without widening them that part would be dropped.
 */
fun Modifier.backdropSource(backdrop: Backdrop, behind: Backdrop? = null, overscan: Dp = 0.dp): Modifier =
    this then BackdropSourceElement(backdrop, behind, overscan)

private data class BackdropSourceElement(val backdrop: Backdrop, val behind: Backdrop?, val overscan: Dp) :
    ModifierNodeElement<BackdropSourceNode>() {
    override fun create() = BackdropSourceNode(backdrop, behind, overscan)
    override fun update(node: BackdropSourceNode) = node.update(backdrop, behind, overscan)
    override fun InspectorInfo.inspectableProperties() {
        name = "backdropSource"
    }
}

private class BackdropSourceNode(
    private var backdrop: Backdrop,
    private var behind: Backdrop?,
    private var overscan: Dp,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    /** What this node draws, which is also what goes on screen. */
    private var content: GraphicsLayer? = null

    /** [behind] + [content], never drawn on screen; only glass reads it. */
    private var composite: GraphicsLayer? = null

    private var lastPosition = Offset.Unspecified

    /** The layer glass should read: the composite when there is something behind. */
    private val published: GraphicsLayer? get() = if (behind != null) composite else content

    override fun onAttach() {
        val graphics = requireGraphicsContext()
        content = graphics.createGraphicsLayer()
        composite = graphics.createGraphicsLayer()
        publish()
    }

    override fun onDetach() {
        unpublish()
        val graphics = requireGraphicsContext()
        content?.let { graphics.releaseGraphicsLayer(it) }
        composite?.let { graphics.releaseGraphicsLayer(it) }
        content = null
        composite = null
        lastPosition = Offset.Unspecified
    }

    private fun publish() {
        backdrop.layer = published
        backdrop.version++
    }

    private fun unpublish() {
        val l = backdrop.layer
        if (l != null && (l === content || l === composite)) {
            backdrop.layer = null
            backdrop.coordinates = null
            backdrop.version++
        }
    }

    fun update(newBackdrop: Backdrop, newBehind: Backdrop?, newOverscan: Dp) {
        val changed = newBackdrop !== backdrop || newBehind !== behind
        if (changed && content != null) unpublish()
        backdrop = newBackdrop
        behind = newBehind
        overscan = newOverscan
        if (changed && content != null) {
            publish()
            lastPosition = Offset.Unspecified
        }
        invalidateDraw()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        backdrop.coordinates = coordinates
        val p = coordinates.positionInRoot()
        if (p != lastPosition) {
            lastPosition = p
            backdrop.version++
            // The composite holds [behind] at an offset that depends on where this node is.
            if (behind != null) invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val c = content ?: return drawContent()
        val e = overscan.roundToPx()
        val recordSize = IntSize(size.width.roundToInt() + e * 2, size.height.roundToInt() + e * 2)
        // Each layer is moved out by e and its content moved back in by e, so drawing the
        // layer still puts everything exactly where this node would have drawn it.
        c.topLeft = IntOffset(-e, -e)
        c.record(recordSize) {
            translate(e.toFloat(), e.toFloat()) { this@draw.drawContent() }
        }

        val b = behind
        val comp = composite
        if (b != null && comp != null) {
            b.version // subscribe: re-record when what is behind attaches or moves
            val behindLayer = b.layer
            val behindCoords = b.coordinates
            comp.topLeft = IntOffset(-e, -e)
            comp.record(recordSize) {
                translate(e.toFloat(), e.toFloat()) {
                    if (behindLayer != null && behindCoords != null && behindCoords.isAttached) {
                        backdropOffset(behindCoords, requireLayoutCoordinates())?.let { offset ->
                            translate(-offset.x, -offset.y) { drawLayer(behindLayer) }
                        }
                    }
                    drawLayer(c)
                }
            }
        }
        drawLayer(c)
    }
}
