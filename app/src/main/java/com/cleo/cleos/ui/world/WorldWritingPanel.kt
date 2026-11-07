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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.LocalPageShown
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun WorldWritingPanel(ta: CompanionEntity?, options: Boolean, onOptions: (Boolean) -> Unit) {
    if (ta == null) return
    val c = appContainer(); val p = LocalGlassPalette.current
    val state by remember(ta.id) { c.db.world().observeState(ta.id) }.collectAsStateWithLifecycle(null)
    val ticket by remember(ta.id) { c.db.world().observeWritingSchedule(ta.id) }.collectAsStateWithLifecycle(null)
    val busy by c.world.busy.collectAsStateWithLifecycle()
    val working = ta.id in busy; val writing = working && state?.space == "writing"
    val used = state?.takeIf { it.day == LocalDate.now().toEpochDay() }?.let { (it.attempts - if (it.token != null) 1 else 0).coerceAtLeast(0) } ?: 0
    var changing by remember(ta.id) { mutableStateOf(false) }
    var error by remember(ta.id) { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle; val shown = LocalPageShown.current
    LaunchedEffect(ta.id, ta.createdAt, ta.name, ta.persona, ta.apiBaseUrl, ta.apiModel, lifecycle, shown) {
        if (shown) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { c.worldWritingScheduler.reconcile(ta.id); delay(30_000) } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (writing) c.world.cancel(ta.id) else c.world.startWriting(ta) },
                enabled = writing || !working && used < WorldReading.DAILY) { Text(if (writing) "停止写作" else "让 TA 随手写写") }
            TextButton(onClick = { onOptions(!options) }) { Text(if (options) "收起写作安排" else "写作安排 · ${if (ticket == null) "自主关闭" else "自主开启"}") }
        }
        if (state?.space == "writing") Text(state!!.status, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp)
        if (working && !writing) Text("TA 正在另一个空间，结束后再写。", color = p.contentSecondary, fontSize = 12.sp)
        if (options) WorldPanel {
            Text("留一点自由写作的时间", color = p.content, fontSize = 17.sp)
            Text("TA 可以自己选题，写随想、草稿或虚构小故事，也可以不写。写作只发送角色设定和这位 TA 最近两篇随笔的少量文字；不读取私聊、用户记忆或私人相册，不发聊天和通知。开启「回想世界」后，这位 TA 可在私聊或电话中按需回看自己的作品片段；群聊不提供自由随笔。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("让 TA 自己写", color = p.content, modifier = Modifier.weight(1f))
                Switch(checked = ticket != null, enabled = !changing,
                    modifier = Modifier.semantics { contentDescription = "自主自由写作" }, onCheckedChange = { enabled ->
                        changing = true; error = null
                        c.appScope.launch {
                            try { c.worldWritingScheduler.setEnabled(ta, enabled) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { error = "写作安排没有保存，请检查模型配置后重试。" }
                            finally { changing = false }
                        }
                    })
            }
            Text("手动与自主写作、书房和窗外共用每日 ${WorldReading.DAILY} 次机会，今天已用 $used 次；失败或停止退回次数，TA 选择不写仍算一次机会。调用 TA 的现有模型，可能消耗接口额度。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
            Text("开启后约 15 分钟给第一次机会，之后每 4 小时左右一次；由系统安排，省电或网络可能让它晚一些。停止只结束本次，关闭开关才取消后续；恢复备份或角色 / 模型 / Key 变化后需重新开启。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
            ticket?.let { t -> Text("下次机会：" + Instant.ofEpochMilli(t.nextAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")) + " 起", color = p.contentSecondary, fontSize = 12.sp) }
        }
        Text("使用 TA 的模型写可回看的作品，不保存内部推理。共用今日 $used / ${WorldReading.DAILY} 次机会。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        error?.let { Text(it, color = p.error, fontSize = 13.sp) }
    }
}
