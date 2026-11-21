package com.cleo.cleos.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.cleo.cleos.glass.LocalGlassPalette

/** Animate the visible circle independently of its larger touch target. */
@Composable
internal fun Modifier.circularPressFeedback(interaction: InteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .92f else 1f,
        animationSpec = tween(if (pressed) 85 else 140),
        label = "circular press scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
        .then(if (focused) Modifier.border(2.dp, LocalGlassPalette.current.content, CircleShape) else Modifier)
}
