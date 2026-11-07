package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun WorldTopicConsent(ta: CompanionEntity?) {
    val c = appContainer(); val p = LocalGlassPalette.current; val scope = rememberCoroutineScope()
    val ticket by remember(ta?.id) { ta?.let { c.db.world().observeTopicSchedule(it.id) } ?: flowOf(null) }.collectAsStateWithLifecycle(null)
    var changing by remember(ta?.id) { mutableStateOf(false) }
    var error by remember(ta?.id) { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("让 TA 沿着兴趣查阅", color = p.content, fontSize = 18.sp)
            Text(if (ticket == null) "自主续查未开启" else "已开启，可以离开这个页面", color = p.contentSecondary, fontSize = 13.sp)
        }
        Switch(checked = ticket != null, enabled = ta != null && !changing,
            modifier = Modifier.semantics { contentDescription = "自主续查" }, onCheckedChange = { enabled ->
                ta?.let { target ->
                    changing = true; error = null
                    scope.launch {
                        try { c.worldTopicScheduler.setEnabled(target.id, target.createdAt, enabled) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "自主续查设置未完成，请稍后重试" }
                        finally { changing = false }
                    }
                }
            })
    }
    Text("开启后，TA 可根据设定与近期两条公开笔记选择一个主题，检索词会发送到中文维基百科，最多取回 3 条导言摘要，再决定是否阅读。聊天和用户记忆不参与选题。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
    Text("约 15 分钟给首次机会，之后约每 4 小时一次；与书房共用每天 5 次机会。选题、选摘要、阅读通常最多 3 轮模型请求，每轮格式纠正最多一次。TA 选择不查也算一次；失败、无可读结果或停止会退回。相同检索词 24 小时内不重复搜索。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
    Text("模型会收到设定、少量公开笔记及所查摘要；只保存真实来源与可读笔记，不发送聊天或朋友圈。系统、省电与网络可能延后机会，不连续补跑。这个开关与订阅源的自主浏览分开，仅对本机这位 TA 生效，恢复备份后关闭。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
    ticket?.let {
        val next = Instant.ofEpochMilli(it.nextAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
        Text("下次机会：$next 起，由系统安排。关闭此处会停止本轮自主续查及后续机会，手动查阅保留。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        if (it.lastQuery.isNotEmpty()) Text("最近查阅：${it.lastQuery} · 获取摘要不等于已经读过", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
    }
    error?.let { Text(it, color = p.error, fontSize = 13.sp) }
}
