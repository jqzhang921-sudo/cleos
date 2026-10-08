package com.cleo.cleos.ui.feed

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette

/** Lives next to the ellipsis so the popup follows that button, even when the title changes. */
@Composable
internal fun FeedOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
    busy: Boolean,
    browseEnabled: Boolean,
    onBrowse: () -> Unit,
    onEditBio: () -> Unit,
    onCover: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    val transition = updateTransition(visibility, label = "feed menu")
    val scale by transition.animateFloat(
        transitionSpec = { if (targetState) spring(dampingRatio = 0.72f, stiffness = 450f) else tween(140) },
        label = "scale",
    ) { if (it) 1f else 0.86f }
    val opacity by transition.animateFloat(transitionSpec = { tween(if (targetState) 120 else 100) }, label = "opacity") {
        if (it) 1f else 0f
    }
    // Finish closing before presenting the next dialog or expanding the cover.
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(expanded, visibility.isIdle) {
        if (expanded) pendingAction = null
        else if (visibility.isIdle) {
            val action = pendingAction
            pendingAction = null
            action?.invoke()
        }
    }
    val paddingPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(paddingPx, gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width + paddingPx else anchorBounds.left - paddingPx
                val y = anchorBounds.bottom + gapPx - paddingPx
                return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            }
        }
    }
    if (visibility.currentState || visibility.targetState || !visibility.isIdle) {
        Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
            Box(Modifier.padding(12.dp)) {
                GlassSurface(
                    modifier = Modifier.width(224.dp).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = opacity
                        transformOrigin = TransformOrigin(1f, 0f)
                    },
                    backdrop = backdrop,
                    style = palette.card,
                    shape = GlassShape.Rounded(20.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    fun choose(action: () -> Unit) { pendingAction = action; onDismiss() }
                    Column {
                        FeedOptionRow(Icons.Rounded.Explore, if (busy) "TA 正在写…" else "让 TA 逛逛", expanded && browseEnabled) { choose(onBrowse) }
                        HorizontalDivider(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = palette.content.copy(alpha = 0.10f))
                        FeedOptionRow(Icons.Rounded.EditNote, "编辑主页简介", expanded && !busy) { choose(onEditBio) }
                        FeedOptionRow(Icons.Rounded.Image, "查看与更换封面", expanded && !busy) { choose(onCover) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedOptionRow(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = palette.contentSecondary.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(20.dp))
        Text(label, color = palette.content.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 14.sp)
    }
}
