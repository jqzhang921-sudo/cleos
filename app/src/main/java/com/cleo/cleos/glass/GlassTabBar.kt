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
    val lensSource = rememberBackdrop()

    val held = remember { Animatable(0f) }
    val lensX = remember { Animatable(Float.NaN) }
    var stretch by remember { mutableFloatStateOf(0f) }
    var rowWidth by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    // Where the lens last came to rest. It stays behind as a lump the lens is still
    // joined to, so leaving a tab pulls a thread out of it; see `lensMotion.blob`.
    var anchorIndex by remember { mutableIntStateOf(selectedIndex) }
    val lensMotion = remember { GlassMotion() }

    val count = tabs.size
    val insetPx = with(density) { Inset.toPx() }
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)

    fun itemWidth(): Float = if (rowWidth > 0) (rowWidth - 2 * insetPx) / count else 0f

    LaunchedEffect(selectedIndex, rowWidth) {
        val iw = itemWidth()
        if (iw <= 0f || dragging) return@LaunchedEffect
        val target = selectedIndex * iw
        if (lensX.value.isNaN()) {
            lensX.snapTo(target)
        } else {
            lensX.animateTo(target, spring(dampingRatio = 0.7f, stiffness = 420f)) {
                stretch = abs(velocity) * 0.012f
            }
            stretch = 0f
        }
        // Only once it has arrived: until then the thread has to trail from the tab it left.
        anchorIndex = selectedIndex
    }

    // Which tab the lens is over right now; labels light up as it passes.
    val hovered by remember {
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

    val lensStyle = lerp(palette.lens, palette.lensPressed, held.value.coerceIn(0f, 1f))

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
                    .pointerInput(count) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            dragging = true
                            val iw = itemWidth()
                            if (iw <= 0f) return@awaitEachGesture
                            var target = lensX.value
                            fun follow(x: Float) {
                                target = (x - insetPx - iw / 2f).coerceIn(0f, iw * (count - 1))
                                scope.launch {
                                    lensX.animateTo(target, spring(dampingRatio = 0.82f, stiffness = 900f)) {
                                        stretch = abs(velocity) * 0.012f
                                    }
                                }
                            }
                            scope.launch { held.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f)) }
                            follow(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                follow(change.position.x)
                                change.consume()
                            }
                            val index = ((target + iw / 2f) / iw).toInt().coerceIn(0, count - 1)
                            dragging = false
                            scope.launch { held.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 300f)) }
                            scope.launch {
                                lensX.animateTo(index * iw, spring(dampingRatio = 0.62f, stiffness = 380f)) {
                                    stretch = abs(velocity) * 0.012f
                                }
                                stretch = 0f
                                anchorIndex = index
                            }
                            if (index != currentSelected) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnSelect(index)
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
