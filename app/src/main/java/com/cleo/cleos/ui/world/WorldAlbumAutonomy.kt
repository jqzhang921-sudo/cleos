package com.cleo.cleos.ui.world

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.LocalPageShown
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun WorldAlbumAutonomy(ta: CompanionEntity, albumRevision: Long, hasPhotos: Boolean, state: WorldReadingStateEntity?, expanded: Boolean, onExpanded: (Boolean) -> Unit) {
    val c = appContainer(); val p = LocalGlassPalette.current
    val schedule by remember(ta.id) { c.db.worldAlbum().observeSchedule(ta.id) }.collectAsStateWithLifecycle(null)
    val busy by c.world.busy.collectAsStateWithLifecycle()
    val shown = LocalPageShown.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var confirm by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(ta.id, ta.createdAt, ta.name, ta.persona, ta.apiBaseUrl, ta.apiModel, shown, lifecycle) {
        if (shown) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { c.worldAlbumScheduler.reconcile(ta.id); delay(30_000) }
        }
    }
    fun change(enabled: Boolean) {
        changing = true; error = ""
        c.appScope.launch {
            try {
                if (enabled) c.worldAlbum.permit(ta.id, ta.createdAt, albumRevision) { c.worldAlbumScheduler.setEnabled(ta, true) }
                else c.worldAlbumScheduler.setEnabled(ta, false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "设置没有完成。请确认相册已有照片，模型和密钥已配置，再重新打开相册试试。" }
            finally { changing = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onExpanded(!expanded) }) { Text(if (expanded) "收起翻看安排" else if (schedule == null) "翻看安排 · 仅手动" else "翻看安排 · 自主开启") }
        // Keep reconciliation mounted and the stop/error controls available even when details are collapsed.
        if (ta.id in busy && c.world.browsingAlbum(ta.id)) TextButton(onClick = { c.world.cancel(ta.id) }) { Text("停止这次翻看") }
        if (state?.space == "album") state.status.takeIf(String::isNotBlank)?.let { Text(it, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp) }
        if (error.isNotBlank()) Text(error, color = p.error, fontSize = 13.sp, lineHeight = 21.sp)
        if (expanded) AlbumCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("允许 TA 自己翻看", color = p.content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if (schedule == null) "仅手动看图" else "已开启，不需要停留在相册", color = p.contentSecondary, fontSize = 13.sp)
            }
            Switch(checked = schedule != null, enabled = !changing && (hasPhotos || schedule != null),
                modifier = Modifier.semantics { contentDescription = "相册自主翻看" },
                onCheckedChange = { if (it) confirm = true else change(false) })
        }
        Text("开启后约 15 分钟给第一次机会，之后约每 4 小时一次。TA 从这位 TA 的相册中挑一张，也可以不看；与阅读共用每天 ${WorldReading.DAILY} 次机会，失败或停止退回。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        Text("仅看你选入这位 TA 相册的照片，包括以后新添加的。照片、说明会发送到当前模型，感想只留在相册，不会自动发消息或动态。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        schedule?.let { t ->
            val due = Instant.ofEpochMilli(t.nextAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
            Text("下次机会：$due 起，由系统安排，省电或网络可能让它晚一些。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        }
        Text("关闭会停止本次并取消后续机会；角色或模型设置变化、恢复备份后，需要重新开启。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("允许${ta.name.ifBlank { "TA" }}自己翻看相册？") },
        text = { Text("TA 可以挑选你为它放进相册的照片，包括以后添加的照片。先发送候选照片的说明，由 TA 选一张，再把该照片发至当前配置的模型，需要支持看图。你可以随时关闭，感想只保存在这位 TA 的相册，不会自动分享。") },
        confirmButton = { TextButton(onClick = { confirm = false; change(true) }) { Text("允许翻看") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("暂不开启") } })
}
