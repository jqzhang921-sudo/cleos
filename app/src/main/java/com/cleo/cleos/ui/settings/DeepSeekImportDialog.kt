package com.cleo.cleos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleo.cleos.data.DeepSeekPreview

@Composable
internal fun DeepSeekImportDialog(
    preview: DeepSeekPreview,
    onDismiss: () -> Unit,
    onImport: (Set<String>, Boolean) -> Unit,
) {
    var query by remember(preview) { mutableStateOf("") }
    var selected by remember(preview) { mutableStateOf(emptySet<String>()) }
    var includingImages by remember(preview) { mutableStateOf(false) }
    val shown = remember(preview, query) { preview.sessions.filter { it.title.contains(query.trim(), ignoreCase = true) } }
    val selectable = remember(shown, preview) {
        shown.filter { it.sourceKey !in preview.imported }.map { it.id }.toSet()
    }
    val allSelected = selectable.isNotEmpty() && selected.containsAll(selectable)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 DeepSeek 聊天") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("选中的会话将归到「${preview.companionName}」", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("搜索会话标题") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size} / ${preview.sessions.size}", Modifier.weight(1f))
                    TextButton(enabled = selectable.isNotEmpty(), onClick = {
                        selected = if (allSelected) selected - selectable else selected + selectable
                    }) { Text(if (allSelected) "取消全选" else "全选") }
                }
                if (shown.isEmpty()) Text("没有找到会话")
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 245.dp)) {
                    items(shown, key = { it.id }) { session ->
                        val imported = session.sourceKey in preview.imported
                        val checked = imported || session.id in selected
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(checked, enabled = !imported, role = Role.Checkbox, onValueChange = { value ->
                                selected = if (value) selected + session.id else selected - session.id
                            }), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Checkbox(checked, onCheckedChange = null, enabled = !imported)
                            Column(Modifier.weight(1f)) {
                                Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (imported) "已导入" else "原包 ${session.count} 条消息",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .toggleable(includingImages, role = Role.Checkbox, onValueChange = { includingImages = it }),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(includingImages, onCheckedChange = null)
                    Text("同时导入图片")
                }
                Text("导入 DeepSeek 当时显示的那一版对话。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { onImport(selected, includingImages) }) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
