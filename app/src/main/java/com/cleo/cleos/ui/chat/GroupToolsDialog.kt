package com.cleo.cleos.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cleo.cleos.ai.GroupToolRules
import com.cleo.cleos.data.db.GroupMessageEntity
import com.cleo.cleos.glass.*

@Composable
internal fun GroupToolsDialog(message: GroupMessageEntity, onDismiss: () -> Unit) {
    val palette = LocalGlassPalette.current
    val trace = GroupToolRules.decode(message.toolTrace)
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = 580.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${message.speakerName}的工具记录", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("本次回复的工具记录，结果在这个群共享。长按文字可以选择复制；返回结果也可能包含错误提示。",
                    color = palette.contentSecondary, fontSize = 12.sp)
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(trace.records, key = { it.id }) { record ->
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            val state = when (record.state) {
                                "running" -> if (message.status == "running") {
                                    when (record.name) {
                                        "send_voice" -> "正在合成语音"
                                        "invite_group_member" -> "正在记下接话邀请"
                                        else -> "正在查询"
                                    }
                                } else "执行中断，未保存结果"
                                "returned" -> record.note.ifEmpty { "已返回结果" }
                                "rejected" -> "未执行"
                                else -> "执行失败"
                            }
                            val name = if (record.name == "invite_group_member") "请伙伴接话" else record.name
                            Text("$name · $state", color = palette.content, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("参数", color = palette.contentSecondary, fontSize = 12.sp)
                            SelectionContainer { Text(record.arguments, color = palette.content, fontSize = 13.sp) }
                            if (record.result.isNotEmpty()) {
                                Text("结果", color = palette.contentSecondary, fontSize = 12.sp)
                                SelectionContainer { MessageText(record.result, palette.content, TextStyle(fontSize = 13.sp, lineHeight = 19.sp)) }
                            }
                            HorizontalDivider(color = palette.contentSecondary.copy(alpha = 0.15f))
                        }
                    }
                }
                TextButton(onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭", color = palette.content) }
            }
        }
    }
}
