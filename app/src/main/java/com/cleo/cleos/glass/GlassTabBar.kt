package com.cleo.cleos.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
data class GlassTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

private val BarHeight = 64.dp
private val Inset = 4.dp

/** Space above and below the bar that the held lens may swell into. */
private val Overhang = 14.dp

/**
 * A floating glass tab bar whose selection is itself a piece of glass.
 *
 * At rest the lens is a lightly tinted pill behind the current tab. Press anywhere on the
 * bar and it comes to the finger, swells past the bar's edges and turns into a clear
 * magnifier; drag and it follows, stretching with its speed; let go and it springs onto
 * the nearest tab, which becomes the selection.
 *
 * The lens refracts the bar itself (glass, icons, labels) plus the page behind the bar,
 * so it needs its own source: [lensSource] records the page mirror, the bar and its labels,
 * and the lens, a sibling of that source, reads it.
 */
@Composable
fun GlassTabBar(
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val motionEnabled = com.cleo.cleos.ui.common.systemMotionEnabled()
    val currentMotion by rememberUpdatedState(motionEnabled)
    val lensSource = rememberBackdrop()

    val held = remember { Animatable(0f) }
    val lensX = remember { Animatable(Float.NaN) }
    var stretch by remember { mutableFloatStateOf(0f) }
    var rowWidth by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragVelocity by remember { mutableFloatStateOf(0f) }

    // Where the lens last came to rest. It stays behind as a lump the lens is still
    // joined to, so leaving a tab pulls a thread out of it; see `lensMotion.blob`.
    var anchorIndex by remember { mutableIntStateOf(selectedIndex) }
    val lensMotion = remember { GlassMotion() }

    val count = tabs.size
    val insetPx = with(density) { Inset.toPx() }
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)

    fun itemWidth(): Float = if (rowWidth > 0) (rowWidth - 2 * insetPx) / count else 0f

    // The only owner of settling, including releasing over the already-selected tab.
    LaunchedEffect(selectedIndex, rowWidth, dragging, motionEnabled) {
        val iw = itemWidth()
        if (iw <= 0f || dragging) return@LaunchedEffect
        val target = selectedIndex * iw
        if (lensX.value.isNaN() || !motionEnabled) {
            lensX.snapTo(target)
        } else {
            lensX.animateTo(target, spring(dampingRatio = 0.7f, stiffness = 420f), initialVelocity = dragVelocity) {
                stretch = TabDragMotion.stretch(velocity, iw)
            }
            stretch = 0f
        }
        // Only once it has arrived: until then the thread has to trail from the tab it left.
        anchorIndex = selectedIndex
        dragVelocity = 0f
    }

    // Which tab the lens is over right now; labels light up as it passes.
    val hovered by remember(count, insetPx) {
        derivedStateOf {
            val iw = if (rowWidth > 0) (rowWidth - 2 * insetPx) / count else 0f
            val x = lensX.value
            if (iw <= 0f || x.isNaN()) currentSelected
            else ((x + iw / 2f) / iw).toInt().coerceIn(0, count - 1)
        }
    }
    LaunchedEffect(Unit) {
        var last = -1
        snapshotFlow { hovered to dragging }.collect { (h, d) ->
            if (d && last != -1 && h != last) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            last = h
        }
    }

    Box(modifier.height(BarHeight + Overhang * 2)) {
        // A held lens at either end reaches a little past the bar, hence the overscan.
        Box(Modifier.fillMaxSize().backdropSource(lensSource, behind = backdrop, overscan = 20.dp)) {
            Row(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(BarHeight)
                    .liquidGlass(backdrop, palette.tabBar, GlassShape.Capsule)
                    .onSizeChanged { rowWidth = it.width }
                    .pointerInput(count, rowWidth, insetPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val iw = itemWidth()
                            if (iw <= 0f) return@awaitEachGesture
                            var target = TabDragMotion.target(down.position.x, insetPx, iw, count)
                            val targets = Channel<Float>(Channel.CONFLATED)
                            dragging = true
                            dragVelocity = 0f
                            scope.launch { held.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f)) }
                            // One follower for this gesture. Touch samples only replace the latest
                            // target; they never start/cancel an Animatable job for each sample.
                            val follower = scope.launch {
                                var frame = TabDragFrame(lensX.value.takeIf { it.isFinite() } ?: currentSelected * iw, 0f)
                                var goal = target
                                var previous = 0L
                                while (isActive) {
                                    if (TabDragMotion.atRest(frame, goal)) {
                                        lensX.snapTo(goal)
                                        stretch = 0f
                                        dragVelocity = 0f
                                        goal = targets.receive()
                                        previous = 0L
                                    }
                                    targets.tryReceive().getOrNull()?.let { goal = it }
                                    val now = withFrameNanos { it }
                                    // Read the newest sample after waiting for the display frame.
                                    targets.tryReceive().getOrNull()?.let { goal = it }
                                    val dt = if (previous == 0L) 1f / 60f else (now - previous) / 1_000_000_000f
                                    previous = now
                                    frame = if (currentMotion) TabDragMotion.follow(frame, goal, iw * (count - 1), dt) else TabDragFrame(goal, 0f)
                                    lensX.snapTo(frame.position)
                                    dragVelocity = frame.velocity
                                    stretch = TabDragMotion.stretch(frame.velocity, iw)
                                }
                            }
                            var released = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.isConsumed) break
                                    if (!change.pressed) {
                                        target = TabDragMotion.target(change.position.x, insetPx, iw, count)
                                        released = true
                                        break
                                    }
                                    val next = TabDragMotion.target(change.position.x, insetPx, iw, count)
                                    if (next != target) {
                                        target = next
                                        targets.trySend(target)
                                    }
                                    change.consume()
                                }
                            } finally {
                                follower.cancel()
                                targets.close()
                                dragging = false
                                scope.launch { held.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 300f)) }
                            }
                            if (released) {
                                val index = TabDragMotion.index(target, iw, count)
                                if (index != currentSelected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnSelect(index)
                                }
                            }
                        }
                    }
                    .padding(horizontal = Inset),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                tabs.forEachIndexed { i, tab ->
                    TabLabel(
                        tab = tab,
                        active = i == hovered,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics {
                                role = Role.Tab
                                selected = i == selectedIndex
                                contentDescription = tab.label
                            },
                    )
                }
            }
        }

        // The lens. Its size and position are read in the layout phase, so dragging it
        // re-lays-out and redraws this one node without recomposing the bar.
        @Composable
        fun Lens() {
            val lensStyle = lerp(palette.lens, palette.lensPressed, held.value.coerceIn(0f, 1f))
            Box(
                Modifier
                    .offset {
                        val iw = itemWidth()
                        val h = held.value
                        val baseH = (BarHeight - Inset * 2).toPx()
                        val w = iw * (1f + 0.14f * h) + stretch
                        val lh = baseH + 22.dp.toPx() * h - stretch * 0.3f
                        val cx = insetPx + (if (lensX.value.isNaN()) currentSelected * iw else lensX.value) + iw / 2f
                        val cy = (BarHeight + Overhang * 2).toPx() / 2f
                        // Written here rather than from the gesture: this runs every frame the
                        // lens moves, in the layout pass, so the thread is never a frame behind
                        // the lens it hangs off. The glass reads it when it draws, just after.
                        lensMotion.blob = trailingBlob(
                            anchorCx = insetPx + anchorIndex * iw + iw / 2f,
                            lensCx = cx,
                            lensLeft = cx - w / 2f,
                            lensHeight = lh,
                            span = iw,
                            restHeight = baseH,
                        )
                        IntOffset((cx - w / 2f).roundToInt(), (cy - lh / 2f).roundToInt())
                    }
                    .layout { measurable, _ ->
                        val iw = itemWidth()
                        val h = held.value
                        val baseH = (BarHeight - Inset * 2).toPx()
                        val w = (iw * (1f + 0.14f * h) + stretch).roundToInt().coerceAtLeast(0)
                        val lh = (baseH + 22.dp.toPx() * h - stretch * 0.3f).roundToInt().coerceAtLeast(0)
                        val placeable = measurable.measure(Constraints.fixed(w, lh))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .liquidGlass(lensSource, lensStyle, GlassShape.Capsule, lensMotion),
            )
        }
        Lens()
    }
}

/**
 * The lump still sitting on the tab the lens is leaving, given to the lens as its second
 * shape so the two are drawn as one melting thing.
 *
 * Both the lump and the reach between them fade out over one tab's width. The reach is
 * zero at *both* ends, which is the part worth keeping:
 *  - at the far end that is the snap;
 *  - at the near end it is what stops the lens from looking fat while it sits still.
 *    A soft minimum never returns quite the nearer of the two distances, so a lump parked
 *    inside the resting lens would quietly inflate its outline by a fraction of the reach.
 *
 * Returns null once there is nothing left to draw, which also spares the shader the work.
 */
internal fun trailingBlob(
    anchorCx: Float,
    lensCx: Float,
    lensLeft: Float,
    lensHeight: Float,
    span: Float,
    restHeight: Float,
): GlassBlob? {
    if (span <= 0f) return null
    val t = (abs(anchorCx - lensCx) / span).coerceIn(0f, 1f)
    val merge = restHeight * 0.5f * (4f * t * (1f - t))
    if (merge <= 0.5f) return null
    val left = 1f - t
    val w = span * 0.62f * left
    val h = restHeight * 0.62f * left
    if (w <= 1f || h <= 1f) return null
    return GlassBlob(
        centerX = anchorCx - lensLeft,
        centerY = lensHeight / 2f,
        width = w,
        height = h,
        radius = h / 2f,
        merge = merge,
    )
}

@Composable
private fun TabLabel(tab: GlassTab, active: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val color = if (active) palette.accentContent else palette.content.copy(alpha = 0.86f)
    // Selection changes only the tint/icon, never the content width or its centered position.
    // Full tab names remain on the parent accessibility node.
    Box(
        modifier,
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (active) tab.selectedIcon else tab.icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp),
        )
    }
}
