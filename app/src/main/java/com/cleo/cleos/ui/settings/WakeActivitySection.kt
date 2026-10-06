package com.cleo.cleos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.LaterRules
import com.cleo.cleos.ai.WakeActivityText
import com.cleo.cleos.data.db.WakeActivityEntity as W
import com.cleo.cleos.glass.LocalGlassPalette
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
internal fun WakeActivitySection(vm: SettingsViewModel) {
    val records by vm.wakeActivity.collectAsStateWithLifecycle()
    var expanded by remember(vm.companionId) { mutableStateOf(false) }
    var filter by remember(vm.companionId) { mutableStateOf(0) }
    var page by remember(vm.companionId) { mutableStateOf(0) }
    val palette = LocalGlassPalette.current
    Section("主动消息记录") {
        if (records.isEmpty()) {
            Text("这里会记录之后的唤醒：发了消息、选择安静或被规则跳过，都能看到。", color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 19.sp)
        } else {
            val latest = records.first()
            Text("最近：${WakeActivityText.source(latest.source)} · ${WakeActivityText.status(latest)}", color = palette.content, fontSize = 14.sp)
            Chip(if (expanded) "收起记录" else "查看最近记录", selected = expanded) { expanded = !expanded }
            if (expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部", "发了消息", "其他状态").forEachIndexed { i, label ->
                        Chip(label, selected = filter == i) { filter = i; page = 0 }
                    }
                }
                val selected = records.filter { filter == 0 || if (filter == 1) it.status == W.SENT else it.status != W.SENT }
                if (selected.isEmpty()) Text("最近没有这类记录", color = palette.contentSecondary, fontSize = 13.sp)
                val pages = ((selected.size + 5) / 6).coerceAtLeast(1)
                val currentPage = page.coerceIn(0, pages - 1)
                selected.drop(currentPage * 6).take(6).forEach { w ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(w.startedAt), ZoneId.systemDefault())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("●", color = if (w.status == W.FAILED) palette.error else palette.contentSecondary, fontSize = 12.sp)
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
                                Text("${LaterRules.at(at, ZonedDateTime.now())} · ${WakeActivityText.source(w.source)}", color = palette.contentSecondary, fontSize = 12.sp)
                                Text(WakeActivityText.status(w), color = if (w.status == W.FAILED) palette.error else palette.content, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(WakeActivityText.requests(w), color = palette.contentSecondary, fontSize = 12.sp)
                                if (w.toolSummary.isNotBlank()) Text("尝试的工具：${w.toolSummary}", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                                if (w.detail.isNotBlank()) Text(w.detail, color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                            }
                        }
                    }
                }
                if (pages > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (currentPage > 0) Chip("更新的记录", selected = false) { page = currentPage - 1 }
                        if (currentPage + 1 < pages) Chip("更早的记录", selected = false) { page = currentPage + 1 }
                    }
                }
                Text("展示最近 30 条，本机保留最近 100 条。只记录执行状态和工具名称，不展示隐藏思考、工具参数或日记正文；不增加模型请求。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
