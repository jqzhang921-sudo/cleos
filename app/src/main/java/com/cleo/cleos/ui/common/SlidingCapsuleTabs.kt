package com.cleo.cleos.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.LocalGlassPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The highlight tracks the pager continuously, including during a swipe. */
@Composable
internal fun SlidingCapsuleTabs(
    labels: List<String>,
    pager: PagerState,
    motion: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var navigation by remember { mutableStateOf<Job?>(null) }
    SlidingCapsuleTabs(
        labels, pager.currentPage, { pager.currentPage + pager.currentPageOffsetFraction },
        motion, modifier.padding(horizontal = 20.dp), enabled = true, minTabWidth = 0.dp,
    ) { index ->
        navigation?.cancel()
        navigation = scope.launch {
            if (motion) pager.animateScrollToPage(index, animationSpec = tween(320, easing = FastOutSlowInEasing))
            else pager.scrollToPage(index)
        }
    }
}

@Composable
internal fun SlidingCapsuleTabs(
    labels: List<String>,
    selectedIndex: Int,
    motion: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    minTabWidth: Dp = 0.dp,
    onSelect: (Int) -> Unit,
) {
    val position by animateFloatAsState(
        selectedIndex.toFloat(),
        tween(if (motion) 320 else 0, easing = FastOutSlowInEasing),
        label = "capsule-tab-position",
    )
    SlidingCapsuleTabs(labels, selectedIndex, { position }, motion, modifier, enabled, minTabWidth, onSelect)
}

@Composable
private fun SlidingCapsuleTabs(
    labels: List<String>,
    selectedIndex: Int,
    position: () -> Float,
    motion: Boolean,
    modifier: Modifier,
    enabled: Boolean,
    minTabWidth: Dp,
    onSelect: (Int) -> Unit,
) {
    if (labels.isEmpty()) return
    val palette = LocalGlassPalette.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stripWidth = maxOf(maxWidth, minTabWidth * labels.size)
        val itemWidth = stripWidth / labels.size
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
        if (stripWidth > maxWidth && selectedIndex in labels.indices) {
            val viewport = with(density) { maxWidth.roundToPx() }
            val start = with(density) { (itemWidth * selectedIndex).roundToPx() }
            val end = with(density) { (itemWidth * (selectedIndex + 1)).roundToPx() }
            LaunchedEffect(start, end, viewport, motion) {
                val target = scroll.value.coerceIn((end - viewport).coerceAtMost(start).coerceAtLeast(0), start)
                if (target != scroll.value) {
                    if (motion) scroll.animateScrollTo(target, tween(320, easing = FastOutSlowInEasing)) else scroll.scrollTo(target)
                }
            }
        }
        val scrollModifier = if (stripWidth > maxWidth) Modifier.horizontalScroll(scroll) else Modifier
        Box(scrollModifier.width(stripWidth).alpha(if (enabled) 1f else .55f)) {
            if (selectedIndex in labels.indices) {
                Box(Modifier.align(Alignment.CenterStart).width((itemWidth - 4.dp).coerceAtLeast(0.dp)).height(36.dp)
                    .graphicsLayer {
                        translationX = direction * (2.dp.toPx() + position().coerceIn(0f, labels.lastIndex.toFloat()) * itemWidth.toPx())
                    }.background(palette.content.copy(alpha = .08f), RoundedCornerShape(50)))
            }
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                labels.forEachIndexed { index, label ->
                    val selected = selectedIndex == index
                    val ink by animateColorAsState(if (selected) palette.accentContent else palette.contentSecondary,
                        tween(if (motion) 140 else 0), label = "capsule-tab-ink")
                    Box(Modifier.weight(1f).heightIn(min = 44.dp)
                        .selectable(selected, interactionSource = null, indication = null, enabled = enabled,
                            role = Role.Tab, onClick = { onSelect(index) })
                        .padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = ink, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
