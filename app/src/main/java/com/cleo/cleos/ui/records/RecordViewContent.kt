package com.cleo.cleos.ui.records

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** A single live content tree: fade through to the new layout without stacking glass pages. */
@Composable
internal fun RecordViewContent(
    calendar: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(calendar: Boolean) -> Unit,
) {
    var shownCalendar by remember { mutableStateOf(calendar) }
    val visibility = remember { Animatable(1f) }
    val travel = with(LocalDensity.current) { 6.dp.toPx() }
    LaunchedEffect(calendar) {
        if (shownCalendar != calendar) {
            visibility.animateTo(0f, tween(100, easing = FastOutSlowInEasing))
            shownCalendar = calendar
            // Let the new viewport be laid out at zero opacity before revealing it.
            withFrameNanos { }
        }
        visibility.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
    }
    // Only the layer reads every alpha sample; the content/semantics tree should
    // recompose when interaction is enabled/disabled, not on every animation frame.
    val changing by remember(calendar) {
        derivedStateOf { shownCalendar != calendar || visibility.isRunning || visibility.value < 1f }
    }
    Box(modifier.semantics {
        stateDescription = if (calendar) "月历视图" else "列表视图"
    }) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    alpha = visibility.value
                    translationY = (1f - visibility.value) * travel
                }
                .then(if (changing) Modifier.clearAndSetSemantics { } else Modifier)
                .pointerInput(changing) {
                    if (changing) awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                },
        ) { content(shownCalendar) }
    }
}

/** Reserve both title lines at larger accessibility font scales. */
@Composable
internal fun recordTopBarHeight(): androidx.compose.ui.unit.Dp =
    com.cleo.cleos.ui.common.TopBarHeight +
        (40f * (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)).dp
