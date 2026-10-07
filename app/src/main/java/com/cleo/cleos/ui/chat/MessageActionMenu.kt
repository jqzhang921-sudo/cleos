package com.cleo.cleos.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.MessageReactions
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette

internal data class MessageMenuAction(val label: String, val run: () -> Unit)

@Composable
internal fun MessageActionMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MessageMenuAction>,
    reactions: Set<String>?, onReact: (String) -> Unit) {
    val palette = LocalGlassPalette.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.width(272.dp),
        containerColor = Color.Transparent, tonalElevation = 0.dp, shadowElevation = 0.dp,
        shape = RoundedCornerShape(20.dp)) {
        GlassSurface(shape = GlassShape.Rounded(20.dp), contentPadding = PaddingValues(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (reactions != null) Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                    MessageReactions.OFFERED.forEach { emoji ->
                        Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (emoji in reactions) palette.accent.copy(alpha = .18f) else Color.Transparent)
                            .clickable { onDismiss(); onReact(emoji) }, contentAlignment = Alignment.Center) {
                            Text(emoji, fontSize = 21.sp)
                        }
                    }
                }
                actions.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        pair.forEach { action ->
                            val icon = when (action.label) {
                                "复制" -> Icons.Rounded.ContentCopy
                                "编辑" -> Icons.Rounded.Edit
                                "引用" -> Icons.AutoMirrored.Rounded.Reply
                                "朗读", "停止朗读" -> Icons.Rounded.VolumeUp
                                "收藏" -> Icons.Rounded.BookmarkBorder
                                "多选" -> Icons.Rounded.Checklist
                                "删除" -> Icons.Rounded.DeleteOutline
                                else -> Icons.Rounded.Refresh
                            }
                            val ink = if (action.label == "删除") Color(0xFFAD5656) else palette.content
                            Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                                .background(palette.content.copy(alpha = .055f))
                                .clickable { onDismiss(); action.run() }.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Icon(icon, null, tint = ink, modifier = Modifier.size(17.dp))
                                Text(action.label, color = ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
