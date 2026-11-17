package com.cleo.cleos.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.ai.MemoryHistory
import com.cleo.cleos.data.MemoryContext
import com.cleo.cleos.data.MemoryDetails
import com.cleo.cleos.data.db.MemoryEntity
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun MemorySources(memory: MemoryEntity, onFound: (MessageEntity) -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val traces = MemoryContext.traces(memory)
    var opened by remember(memory.id) { mutableStateOf(false) }
    var originals by remember(memory.id) { mutableStateOf<Map<Long, MessageEntity?>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val history = remember { MemoryHistory(c.db.messages(), c.db.conversations()) }
    if (traces.isEmpty()) {
        Text("未关联原消息。旧记忆、导入和手动填写的内容不会自动补猜出处。", color = palette.contentSecondary, fontSize = 12.sp)
    } else TextButton(onClick = {
        opened = true; loading = true; problem = null
        scope.launch {
            try { originals = traces.associate { it.messageId to history.source(memory.companionId, it) } }
            finally { loading = false }
        }
    }) { Text("查看关联聊天（${traces.map { it.messageId }.distinct().size} 条）") }
    if (opened) AlertDialog(
        onDismissRequest = { opened = false }, title = { Text("记忆来源") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("关联聊天用于核对；记录时的背景不代表整条记忆逐字出自该消息。修改过的记忆内容会解除原关联。", fontSize = 12.sp)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                traces.groupBy { it.messageId }.forEach { (id, group) ->
                    val trace = group.first()
                    val m = originals[id]
                    val matches = m != null && MemoryContext.matches(trace, m)
                    val whenSaid = Instant.ofEpochMilli(trace.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    val targets = group.map { t -> if (t.target.startsWith("summary:")) "一句话摘要" else
                        "细节 ${(MemoryDetails.decode(memory.details).indexOfFirst { "detail:" + MemoryContext.hash(it) == t.target } + 1)}" }
                    Text(targets.joinToString("、"), fontSize = 13.sp)
                    Text("$whenSaid · ${if (trace.role == "user") "你说的" else "TA 说的"} · ${if (trace.quoted) "记录时核对过摘录" else "记录时的最近用户消息"}", fontSize = 12.sp)
                    if (!loading) {
                        Text(when {
                            m == null -> "原消息已移除或无法访问。"
                            !matches -> "原消息已变化，需要重新核对。"
                            trace.quoted -> m.content.substring(trace.quoteStart, trace.quoteEnd).take(900)
                            else -> m.content.take(900)
                        }, fontSize = 14.sp)
                        if (m != null) TextButton(onClick = { scope.launch {
                            val live = history.source(memory.companionId, trace)
                            if (live == null) problem = "原消息已移除。" else {
                                opened = false; onFound(live)
                            }
                        } }) { Text("回到这条聊天") }
                    }
                    HorizontalDivider()
                }
                problem?.let { Text(it, color = palette.error) }
            }
        }, confirmButton = { TextButton(onClick = { opened = false }) { Text("关闭") } },
    )
}
