package com.cleo.cleos.ui.feed

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.*
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(
        targetValue = if (expanded) 1f else 0.86f,
        animationSpec = if (expanded) spring(dampingRatio = 0.72f, stiffness = 450f) else tween(140),
        label = "feed menu scale",
    )
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(248.dp),
        offset = DpOffset(0.dp, 8.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        shape = RoundedCornerShape(20.dp),
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(1f, 0f)
                },
                backdrop = backdrop,
                style = palette.card,
                shape = GlassShape.Rounded(20.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                fun choose(action: () -> Unit) {
                    onDismiss()
                    scope.launch { delay(160); action() }
                }
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
