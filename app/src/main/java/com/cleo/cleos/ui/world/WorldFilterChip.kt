package com.cleo.cleos.ui.world

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.LocalGlassPalette

/** World filters use the same capsule proportions as the record-page writer filters. */
@Composable
internal fun WorldFilterChip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalGlassPalette.current
    Box(
        Modifier.alpha(if (enabled) 1f else .38f).clip(CircleShape)
            .background(p.content.copy(alpha = if (selected) .14f else .06f))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) p.content else p.contentSecondary, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
