package com.cleo.cleos.ui.settings

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.FeedVisitRules
import com.cleo.cleos.ai.FreeTopicRules
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.launch

@Composable
internal fun FeedVisitSection(companionId: Long) {
    val c = appContainer()
    val companions by c.companions.all.collectAsStateWithLifecycle(emptyList())
    companions.firstOrNull { it.id == companionId }?.let { ta ->
        Section("主动逛朋友圈") { FeedVisitControls(ta) }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FeedVisitSettingsDialog(currentId: Long?, onClose: () -> Unit) {
    val c = appContainer()
    val companions by c.companions.all.collectAsStateWithLifecycle(emptyList())
    var selected by rememberSaveable { mutableStateOf(currentId) }
    val ta = companions.firstOrNull { it.id == selected } ?: companions.firstOrNull()
    val palette = LocalGlassPalette.current
    Dialog(onDismissRequest = onClose) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = 580.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("主动逛朋友圈", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("每位 TA 单独设置，开关会直接保存。", color = palette.contentSecondary, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        companions.forEach { person -> Chip(person.name.ifBlank { "TA" }, person.id == ta?.id) { selected = person.id } }
                    }
                    if (ta == null) Text("先添加一位 TA", color = palette.contentSecondary)
                    else key(ta.id) { FeedVisitControls(ta) }
                }
                TextButton(onClose, Modifier.align(Alignment.End)) { Text("完成", color = palette.content) }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FeedVisitControls(ta: CompanionEntity) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val context = LocalContext.current
    var saving by remember(ta.id) { mutableStateOf(false) }
    var problem by remember(ta.id) { mutableStateOf<String?>(null) }
    var sources by rememberSaveable { mutableStateOf(false) }
    if (sources) com.cleo.cleos.ui.feed.FeedSourcesDialog { sources = false }
    fun change(transform: (CompanionEntity) -> CompanionEntity) {
        if (saving) return
        saving = true
        c.appScope.launch {
            try {
                c.companions.update(ta.id, transform)
                c.feedVisits.configure(ta.id)
                problem = null
            } catch (e: Exception) { problem = e.message ?: "设置没保存，请重试" }
            finally { saving = false }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("让 TA 自己逛逛", color = palette.content, fontWeight = FontWeight.Medium)
            Text("看看朋友的日常，自行决定点赞、回应或安静", color = palette.contentSecondary, fontSize = 12.sp)
        }
        Switch(ta.feedVisitEnabled, { value -> change { it.copy(feedVisitEnabled = value) } }, enabled = !saving)
    }
    if (ta.feedVisitEnabled) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeedVisitRules.LEVELS.forEach { level ->
                Chip(level.label, ta.feedVisitLevel == level.id) { change { it.copy(feedVisitLevel = level.id) } }
            }
        }
        val level = FeedVisitRules.level(ta.feedVisitLevel)
        Text("约每 ${level.minMinutes / 60}–${level.maxMinutes / 60} 小时一次，每天最多 ${level.dailyMax} 次考虑机会。有新内容时最多请求一次模型，选择安静也会消耗 token。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("也可以自己发动态", color = palette.content, modifier = Modifier.weight(1f))
            Switch(ta.feedVisitPosts, { value -> change { it.copy(feedVisitPosts = value) } }, enabled = !saving)
        }
        if (ta.feedVisitPosts) {
            Text("每位 TA 每天最多自动发 1 条；由 TA 决定，也可以不发。手动「让 TA 逛逛」另算。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("也看看资讯话题", color = palette.content, modifier = Modifier.weight(1f))
                Switch(ta.feedVisitNews, { value -> change { it.copy(feedVisitNews = value) } }, enabled = !saving)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("夜间免打扰", color = palette.content, modifier = Modifier.weight(1f))
            Switch(ta.feedVisitQuietOn, { value -> change { it.copy(feedVisitQuietOn = value) } }, enabled = !saving)
        }
        if (ta.feedVisitQuietOn) {
            Row {
                listOf(true, false).forEach { start ->
                    val value = FreeTopicRules.minute(if (start) ta.feedVisitQuietStart else ta.feedVisitQuietEnd, if (start) 1380 else 480)
                    TextButton(onClick = {
                        TimePickerDialog(context, { _, hour, minute -> change {
                            if (start) it.copy(feedVisitQuietStart = hour * 60 + minute) else it.copy(feedVisitQuietEnd = hour * 60 + minute)
                        } }, value / 60, value % 60, true).show()
                    }, enabled = !saving) { Text((if (start) "开始 " else "结束 ") + FreeTopicRules.time(value), color = palette.accentContent) }
                }
            }
            Text("起止相同表示全天免打扰。", color = palette.contentSecondary, fontSize = 12.sp)
        }
        Text("开启或调整后，从下一次等待开始；后台时间受手机省电影响。聊天时先不逛，同一段内容不重复回应，自己的帖子只回应朋友评论。图文阅读需要模型支持图片。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    }
    Text("默认关闭。只更新朋友圈，不发送聊天提醒。执行记录在「设置 → 说话方式 → 主动消息记录」里查看。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    TextButton({ sources = true }, enabled = !saving) { Text("设置资讯兴趣与来源", color = palette.accentContent) }
    problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
}
