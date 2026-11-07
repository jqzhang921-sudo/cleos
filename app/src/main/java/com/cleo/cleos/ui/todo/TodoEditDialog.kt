package com.cleo.cleos.ui.todo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cleo.cleos.data.db.TodoEntity
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Dates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoEditDialog(
    todo: TodoEntity,
    onDismiss: () -> Unit,
    onSave: (TodoEntity) -> Unit,
    onDelete: () -> Unit,
    onFocus: (TodoEntity) -> Unit,
) {
    var title by remember { mutableStateOf(todo.title) }
    var note by remember { mutableStateOf(todo.note) }
    var due by remember { mutableStateOf(todo.dueDay?.let { LocalDate.ofEpochDay(it) }) }
    var picking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("要做的事") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(enabled = title.isNotBlank(), onClick = { onFocus(todo.copy(title = title.trim(), note = note.trim(), dueDay = due?.toEpochDay())) }) { Text("为这件事专注") }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { picking = true }) {
                        Text(due?.let { Dates.due(it) + " · " + Dates.monthDay(it) } ?: "加个日期")
                    }
                    if (due != null) {
                        TextButton(onClick = { due = null }) { Text("去掉日期") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onSave(todo.copy(title = title.trim(), note = note.trim(), dueDay = due?.toEpochDay())) },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("删除", color = LocalGlassPalette.current.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (due ?: Dates.today()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { due = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("取消") } },
        ) { DatePicker(state) }
    }
}
