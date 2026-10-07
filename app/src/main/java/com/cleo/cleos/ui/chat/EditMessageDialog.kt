package com.cleo.cleos.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cleo.cleos.data.db.MessageEntity

@Composable
internal fun EditMessageDialog(message: MessageEntity, replying: Boolean, hasKey: Boolean,
    onDismiss: () -> Unit, onSave: (String, Boolean, (String?) -> Unit) -> Unit) {
    var text by rememberSaveable(message.id) { mutableStateOf(message.content) }
    var saving by remember(message.id) { mutableStateOf(false) }
    var error by remember(message.id) { mutableStateOf<String?>(null) }
    val dismiss by rememberUpdatedState(onDismiss)
    val changed = text.isNotBlank() && text.trim() != message.content
    fun save(resend: Boolean) {
        saving = true; error = null
        onSave(text, resend) { problem ->
            saving = false
            if (problem == null) dismiss() else error = problem
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("编辑消息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(text, { text = it; error = null }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp), minLines = 3,
                    label = { Text("消息内容") })
                Text("保存会修改原消息，保留后面的聊天。保存并另发会再发送一条修改后的消息。")
                if (message.images != null || message.quote != null) Text("原来的图片和引用会保留。")
                if (replying && !saving) Text("正在回复，稍后可保存。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = changed && !saving && !replying, onClick = { save(false) }) { Text(if (saving) "保存中…" else "保存") }
                TextButton(enabled = changed && !saving && !replying && hasKey, onClick = { save(true) }) { Text("保存并另发") }
            }
        }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}
