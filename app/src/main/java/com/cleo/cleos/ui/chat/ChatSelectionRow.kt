package com.cleo.cleos.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.circularPressFeedback

@Composable
internal fun ChatSelectionRow(
    selecting: Boolean,
    checked: Boolean,
    label: String,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = if (selecting) remember { MutableInteractionSource() } else null
    Box(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (interaction != null) {
                val palette = LocalGlassPalette.current
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(20.dp)
                            .alpha(if (enabled) 1f else .5f)
                            .circularPressFeedback(interaction)
                            .then(if (checked) Modifier.background(palette.accent, CircleShape)
                                else Modifier.border(1.5.dp, palette.contentSecondary, CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (checked) Icon(Icons.Rounded.Check, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
        if (interaction != null) {
            Box(Modifier.matchParentSize()
                .toggleable(checked, interactionSource = interaction, indication = null, enabled = enabled,
                    role = Role.Checkbox, onValueChange = { onToggle() })
                .semantics { contentDescription = label })
        }
    }
}
