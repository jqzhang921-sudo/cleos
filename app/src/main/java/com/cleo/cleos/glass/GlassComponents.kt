package com.cleo.cleos.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Makes glass react to a finger: it lights up where it is touched and swells a little,
 * with a spring that overshoots, which is most of what makes it read as "liquid".
 * Only observes pointer events (Initial pass, never consumes), so it composes with
 * clickable or any other gesture handler on the same element.
 */
fun Modifier.glassPress(motion: GlassMotion, swell: Dp = 3.dp, enabled: Boolean = true): Modifier =
    if (!enabled) this else pointerInput(motion, swell) {
        val glow = Animatable(0f)
        val grow = Animatable(0f)
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                motion.touch = down.position
                launch { glow.animateTo(1f, tween(140)) { motion.glow = value } }
                launch {
                    grow.animateTo(swell.value, spring(dampingRatio = 0.42f, stiffness = 520f)) {
                        motion.swellX = value
                        motion.swellY = value
                    }
                }
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    motion.touch = change.position
                }
                launch { glow.animateTo(0f, tween(520)) { motion.glow = value } }
                launch {
                    grow.animateTo(0f, spring(dampingRatio = 0.38f, stiffness = 320f)) {
                        motion.swellX = value
                        motion.swellY = value
                    }
                }
            }
        }
    }

/**
 * The style round buttons use when none is passed. Containers set it for the buttons
 * inside them: the top bar provides its own tuned style, so every button up there
 * follows what was applied to "顶栏" in the glass lab without each screen passing it.
 */
val LocalGlassChrome = compositionLocalOf<GlassStyle?> { null }

/** A piece of glass that holds content. Defaults to the (text-safe) card style over the wallpaper. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    backdrop: Backdrop = LocalWallpaperBackdrop.current,
    style: GlassStyle = LocalGlassPalette.current.card,
    shape: GlassShape = GlassShape.Rounded(24.dp),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .liquidGlass(backdrop, style, shape)
            .padding(contentPadding),
        content = content,
    )
}

internal val ComposerHeight = 50.dp

@Composable
internal fun Modifier.composerGlass(backdrop: Backdrop): Modifier = fillMaxWidth()
    .padding(horizontal = 12.dp)
    .heightIn(min = ComposerHeight)
    .liquidGlass(backdrop, LocalGlassPalette.current.input, GlassShape.Rounded(ComposerHeight / 2))

@Composable
fun GlassButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    style: GlassStyle = LocalGlassChrome.current ?: LocalGlassPalette.current.topBar,
    shape: GlassShape = GlassShape.Capsule,
    enabled: Boolean = true,
    contentColor: Color = LocalGlassPalette.current.content,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val motion = remember { GlassMotion() }
    CompositionLocalProvider(LocalContentColor provides if (enabled) contentColor else contentColor.copy(alpha = 0.38f)) {
        Box(
            modifier
                .liquidGlass(backdrop, style, shape, motion)
                .glassPress(motion, enabled = enabled)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    style: GlassStyle = LocalGlassChrome.current ?: LocalGlassPalette.current.topBar,
    enabled: Boolean = true,
    tint: Color = LocalGlassPalette.current.content,
    size: Dp = 44.dp,
) {
    GlassButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.size(size),
        style = style,
        shape = GlassShape.Capsule,
        enabled = enabled,
        contentColor = tint,
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(icon, contentDescription, Modifier.size(size * 0.5f))
    }
}
