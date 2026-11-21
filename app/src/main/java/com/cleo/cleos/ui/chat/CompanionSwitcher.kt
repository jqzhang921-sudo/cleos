package com.cleo.cleos.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.GlassFloatingPanel
import com.cleo.cleos.ui.common.avatarLetter

/** The menu stays in the chat window so its glass can sample the chat backdrop. */
@Composable
internal fun CompanionSwitcher(
    visible: Boolean,
    anchor: Rect,
    backdrop: Backdrop,
    companions: List<CompanionEntity>,
    currentId: Long,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onSwitch: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    GlassFloatingPanel(visible, backdrop, "人物菜单", onDismiss, modifier, anchor) {
        val rowShape = RoundedCornerShape(18.dp)
        Column {
            LazyColumn(Modifier.weight(1f, fill = false).selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(companions, key = { it.id }) { ta ->
                    val selected = ta.id == currentId
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(rowShape)
                            .then(if (selected) Modifier.background(palette.accentContent.copy(alpha = 0.13f)) else Modifier)
                            .selectable(selected = selected, enabled = visible, role = Role.RadioButton, onClick = { onSwitch(ta.id) })
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(ta.avatar, ta.avatarEmoji ?: avatarLetter(ta.name, "TA"), 30.dp)
                        Text(
                            ta.name.ifBlank { "TA" },
                            modifier = Modifier.weight(1f),
                            color = if (selected) palette.accentContent else palette.content,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = palette.accentContent)
                    }
                }
            }
            if (companions.isNotEmpty()) HorizontalDivider(
                Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                color = palette.content.copy(alpha = 0.12f),
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(rowShape)
                    .clickable(enabled = visible, role = Role.Button, onClick = onAdd)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PersonAdd, null, Modifier.size(20.dp), tint = palette.accentContent)
                }
                Text("添加一个 TA", color = palette.content, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
