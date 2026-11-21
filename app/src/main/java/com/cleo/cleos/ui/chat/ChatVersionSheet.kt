package com.cleo.cleos.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.db.ChatVersionEntity
import com.cleo.cleos.glass.LocalGlassPalette

@Composable
internal fun ChatVersionButton(modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    IconButton(onClick = onClick, modifier = modifier.size(36.dp)) {
        Icon(Icons.Rounded.Layers, contentDescription = "消息版本", tint = palette.contentSecondary,
            modifier = Modifier.size(15.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatVersionSheet(
    current: ChatVersionEntity,
    versions: List<ChatVersionEntity>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    val palette = LocalGlassPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("消息版本", Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
            color = palette.content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 390.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(versions, key = { _, item -> item.rootId }) { index, item ->
                val selected = item.rootId == current.rootId
                val label = when (item.kind) {
                    ChatVersionEntity.ORIGINAL -> "原版"
                    ChatVersionEntity.EDIT -> "编辑版"
                    else -> "重生成版"
                }
                val shape = RoundedCornerShape(16.dp)
                Row(Modifier.fillMaxWidth().clip(shape)
                    .background(if (selected) palette.accent.copy(alpha = .1f) else palette.content.copy(alpha = .04f))
                    .selectable(selected, enabled = !busy && !selected, role = Role.RadioButton,
                        onClick = { onSelect(item.rootId) })
                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (versions.count { it.kind == item.kind } > 1) "$label · ${index + 1}" else label,
                            color = palette.content, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(item.preview, color = palette.contentSecondary, fontSize = 13.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (selected) Icon(Icons.Rounded.Check, "当前版本", tint = palette.accentContent,
                        modifier = Modifier.padding(start = 8.dp).size(20.dp))
                }
            }
        }
    }
}
