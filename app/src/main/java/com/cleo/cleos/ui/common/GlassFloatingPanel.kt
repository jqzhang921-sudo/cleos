package com.cleo.cleos.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette

/** Draws in the host window so the panel's glass can sample the supplied backdrop. */
@Composable
internal fun GlassFloatingPanel(
    visible: Boolean,
    backdrop: Backdrop,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: Rect? = null,
    alignToEnd: Boolean = false,
    width: Dp = 204.dp,
    maxPanelHeight: Dp = 352.dp,
    contentPadding: PaddingValues = PaddingValues(7.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val card = LocalGlassPalette.current.card
    val density = LocalDensity.current
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    var origin by remember { mutableStateOf(Offset.Zero) }
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(modifier) {
        if (visible || anchor == null) {
            AnimatedVisibility(visible, enter = fadeIn(tween(130)), exit = fadeOut(tween(150))) {
                Box(Modifier.fillMaxSize()
                    .background(if (anchor == null) Color.Black.copy(alpha = 0.24f) else Color.Transparent)
                    .clickable(interactionSource = null, indication = null, enabled = visible,
                        onClickLabel = "关闭$title", onClick = onDismiss))
            }
        }
        BoxWithConstraints(Modifier.matchParentSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
            .onGloballyPositioned { origin = it.positionInRoot() }) {
            val panelWidth = minOf(width, maxWidth)
            val fromLeft = ltr != alignToEnd
            val top = anchor?.let { with(density) { (it.bottom - origin.y).toDp() }.coerceAtLeast(0.dp) + 6.dp }
            val anchoredTop = top?.takeIf { maxHeight - it >= 116.dp }
            val belowAnchor = anchoredTop != null
            val height = minOf(maxPanelHeight, maxHeight - (anchoredTop ?: 0.dp))
            val pivot = if (belowAnchor) TransformOrigin(if (fromLeft) 0.18f else 0.82f, 0f) else TransformOrigin.Center
            val placement = if (anchor != null && anchoredTop != null) {
                val edge = if (fromLeft) anchor.left else anchor.right
                val x = with(density) { (edge - origin.x).toDp() } - if (fromLeft) 0.dp else panelWidth
                Modifier.absoluteOffset(x.coerceIn(0.dp, maxWidth - panelWidth), anchoredTop)
            } else Modifier.align(Alignment.Center)
            AnimatedVisibility(
                visible = visible && height > 0.dp && panelWidth > 0.dp,
                modifier = placement,
                enter = fadeIn(tween(130)) +
                    scaleIn(spring(dampingRatio = 0.68f, stiffness = 500f), initialScale = 0.91f, transformOrigin = pivot) +
                    slideInVertically(spring(dampingRatio = 0.72f, stiffness = 500f)) { if (belowAnchor) -it.coerceAtMost(72) / 6 else 0 },
                exit = fadeOut(tween(130)) +
                    scaleOut(tween(150), targetScale = 0.95f, transformOrigin = pivot) +
                    slideOutVertically(tween(150)) { if (belowAnchor) -it.coerceAtMost(60) / 6 else 0 },
                label = title,
            ) {
                GlassSurface(
                    modifier = Modifier.width(panelWidth).heightIn(max = height)
                        .semantics { paneTitle = title }
                        .pointerInput(Unit) { detectTapGestures { } },
                    backdrop = backdrop,
                    style = card.copy(blur = maxOf(card.blur, 16.dp), tint = card.tint.copy(alpha = maxOf(card.tint.alpha, 0.60f))),
                    shape = GlassShape.Rounded(24.dp),
                    contentPadding = contentPadding,
                    content = content,
                )
            }
        }
    }
}
