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

/** Consent invalidation stays active while the settings themselves are folded. */
@Composable
internal fun WorldOutsidePictureMaintenance(ta: CompanionEntity?) {
    if (ta == null) return
    val c = appContainer()
    val shown = LocalPageShown.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(ta.id, ta.createdAt, ta.name, ta.persona, ta.apiBaseUrl, ta.apiModel, shown, lifecycle) {
        if (shown) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { c.worldPictureScheduler.reconcile(ta.id); delay(30_000) }
        }
    }
}

@Composable
internal fun WorldPictureConsent(ta: CompanionEntity?, articles: List<WorldArticleEntity>, state: WorldReadingStateEntity?) {
    if (ta == null) return

    val c = appContainer(); val p = LocalGlassPalette.current
    val pictureRevision = remember(ta.id, ta.createdAt) { c.worldPictures.revision() }
    val hasEntries = com.cleo.cleos.data.WorldPictureExplore.candidates(ta.id, articles, emptyList()).isNotEmpty()
    val schedule by remember(ta.id) { c.db.world().observePictureSchedule(ta.id) }.collectAsStateWithLifecycle(null)
    val busy by c.world.busy.collectAsStateWithLifecycle()
    var confirm by remember(ta.id, ta.createdAt) { mutableStateOf(false) }
    var changing by remember(ta.id, ta.createdAt) { mutableStateOf(false) }
    var error by remember(ta.id, ta.createdAt) { mutableStateOf("") }
    fun change(enabled: Boolean) {
        changing = true; error = ""
        c.appScope.launch {
            try {
                if (enabled) c.worldPictures.permit(ta.id, ta.createdAt, pictureRevision) { c.worldPictureScheduler.setEnabled(ta, true) }
                else c.worldPictureScheduler.setEnabled(ta, false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "设置没有完成。请先保存百科条目、配置看图模型和密钥，再重新打开窗外试试。" }
            finally { changing = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("让 TA 探索公开配图", color = p.content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if (schedule == null) "公开配图探索未开启" else "已开启，可以离开窗外", color = p.contentSecondary, fontSize = 13.sp)
            }
            Switch(checked = schedule != null, enabled = !changing && (hasEntries || schedule != null),
                modifier = Modifier.semantics { contentDescription = "公开配图探索" },
                onCheckedChange = { if (it) confirm = true else change(false) })
        }
        Text("开启后约 15 分钟给第一次机会，之后约每 4 小时一次。TA 从自己窗外的百科条目中挑一份，最多获取一张配图再决定是否看；与阅读共用每天 ${WorldReading.DAILY} 次机会，失败或停止退回。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        Text("候选标题和摘要发送到当前模型；对应条目与图片请求发送至维基百科 / Wikimedia，再将这一张图片及署名说明发送给看图模型。只保存公开图文见闻，不自动发消息或动态，不读取私人相册。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        schedule?.let { t ->
            val due = Instant.ofEpochMilli(t.nextAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
            Text("下次机会：$due 起，由系统安排，省电或网络可能让它晚一些。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        }
        Text("关闭会停止本次并取消后续机会；已取得的公开配图保留。角色、模型或密钥变化、恢复备份后，需要重新开启。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        if (ta.id in busy && c.world.browsingPictures(ta.id)) TextButton(onClick = { c.appScope.launch { c.world.stopPictureBackground(ta.id) } }) { Text("停止这次配图探索") }
        if (state?.space == "picture") state.status.takeIf(String::isNotBlank)?.let { Text(it, color = p.contentSecondary, fontSize = 13.sp, lineHeight = 21.sp) }
        if (error.isNotBlank()) Text(error, color = p.error, fontSize = 13.sp, lineHeight = 21.sp)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("允许${ta.name.ifBlank { "TA" }}探索公开配图？") },
        text = { Text("TA 可以从自己窗外已保存或以后新增的百科条目中挑一份。标题、摘要先发给当前模型，再从公开来源获取至多一张可确认署名与许可的配图，实际图片与说明也会发到当前模型，需要支持看图。未看过的不算看过，不读取私人相册、不自动分享。") },
        confirmButton = { TextButton(onClick = { confirm = false; change(true) }) { Text("允许探索") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("暂不开启") } })
}
