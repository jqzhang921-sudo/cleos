package com.cleo.cleos.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cleo.cleos.ai.GroupMessageDeletion
import com.cleo.cleos.glass.*

@Composable
internal fun GroupMessageDeleteDialog(plan: GroupMessageDeletion, busy: Boolean, enabled: Boolean,
    problem: String?, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalGlassPalette.current
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(22.dp)) {
            Column(Modifier.heightIn(max = 570.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (plan.wholeRound) "删除这一轮？" else "删除这条消息？", color = palette.content,
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(if (plan.wholeRound) "共 ${plan.removed.size} 条：起始发言，以及这一轮所有 TA 的回复和未完成记录。"
                    else "只删除这条消息，其他发言保留。", color = palette.content, fontSize = 14.sp)
                Text(plan.selected.content.ifBlank { "图片、语音或未完成的回复" }, maxLines = 3,
                    color = palette.contentSecondary, fontSize = 13.sp)
                if (plan.files.isNotEmpty()) Text("包含 ${plan.files.size} 个图片 / 语音附件；仍被其他记录使用的附件会保留。",
                    color = palette.contentSecondary, fontSize = 12.sp)
                if (plan.notes.isNotEmpty()) Text("已整理的 ${plan.notes.size} 条小本本记录会保留，原消息链接会移除。",
                    color = palette.contentSecondary, fontSize = 12.sp)
                Text("对应的工具记录一并删除，已执行的操作不会撤销。删除后不能撤回。",
                    color = palette.contentSecondary, fontSize = 12.sp)
                problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onDismiss, enabled = !busy) { Text("取消", color = palette.content) }
                    TextButton(onDelete, enabled = !busy && enabled) {
                        Text(if (busy) "正在删除…" else "删除 ${plan.removed.size} 条", color = palette.error)
                    }
                }
            }
        }
    }
}
