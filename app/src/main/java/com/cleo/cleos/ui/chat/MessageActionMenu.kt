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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.MessageReactions
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface

internal data class MessageMenuAction(val label: String, val run: () -> Unit)

@Composable
internal fun MessageActionMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MessageMenuAction>,
    reactions: Set<String>?, onReact: (String) -> Unit) {
    val palette = LocalGlassPalette.current
    var more by remember(expanded) { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.width(272.dp),
        containerColor = Color.Transparent, tonalElevation = 0.dp, shadowElevation = 0.dp,
        shape = RoundedCornerShape(20.dp)) {
        GlassSurface(Modifier.fillMaxWidth(), shape = GlassShape.Rounded(20.dp),
            contentPadding = PaddingValues(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (reactions != null) Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                    MessageReactions.OFFERED.take(6).forEach { emoji ->
                        Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (emoji in reactions) palette.accent.copy(alpha = .18f) else Color.Transparent)
                            .clickable { onDismiss(); onReact(emoji) }, contentAlignment = Alignment.Center) {
                            Text(emoji, fontSize = 21.sp)
                        }
                    }
                    Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(12.dp))
                        .clickable { more = !more }, contentAlignment = Alignment.Center) {
                        Icon(if (more) Icons.Rounded.ExpandLess else Icons.Rounded.Add,
                            if (more) "收起表情" else "更多表情", tint = palette.content, modifier = Modifier.size(22.dp))
                    }
                }
                if (more && reactions != null) {
                    Text("点选回应 · 再点取消", color = palette.content.copy(alpha = .65f), fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp))
                    Column(Modifier.fillMaxWidth().height(200.dp).verticalScroll(rememberScrollState())) {
                        MessageReactions.ALL.chunked(6).forEach { emojis -> Row {
                        emojis.forEach { emoji ->
                            Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (emoji in reactions) palette.accent.copy(alpha = .18f) else Color.Transparent)
                                .clickable { onDismiss(); onReact(emoji) }, contentAlignment = Alignment.Center) {
                                Text(emoji, fontSize = 24.sp)
                            }
                        }
                        repeat(6 - emojis.size) { Spacer(Modifier.weight(1f)) }
                        } }
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
