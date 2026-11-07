package com.cleo.cleos.ui.focus

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.focus.FocusAlerts
import com.cleo.cleos.focus.FocusSession
import com.cleo.cleos.focus.FocusTimer
import com.cleo.cleos.focus.FocusCompanionRules
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun FocusScreen(taskId: Long?, onBack: () -> Unit) {
    val c = appContainer()
    // Recover an interrupted request even when its persisted state is still "running".
    val companion = remember(c) { c.focusCompanion }
    val context = LocalContext.current
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    val rows by remember { c.focus.recent }.collectAsStateWithLifecycle(emptyList())
    val todos by remember { c.db.todos().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val activeState by remember { c.focus.active }.collectAsStateWithLifecycle(null)
    val active = activeState
    val last = rows.firstOrNull()
    val pageScroll = rememberScrollState()
    LaunchedEffect(active?.id) { if (active != null) pageScroll.scrollTo(0) }
    val companions by remember { c.companions.all }.collectAsStateWithLifecycle(emptyList())
    var companionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var chooseCompanion by remember { mutableStateOf(false) }
    val accompanied = (active ?: last)?.takeIf { it.companionId != null && it.phase == "focus" }
    var focusInput by rememberSaveable { mutableStateOf(c.focus.focusMinutes.toString()) }
    var restInput by rememberSaveable { mutableStateOf(c.focus.restMinutes.toString()) }
    var title by rememberSaveable(taskId) { mutableStateOf("") }
    var filledTask by rememberSaveable(taskId) { mutableStateOf(false) }
    LaunchedEffect(todos, taskId) {
        if (!filledTask) todos.firstOrNull { it.id == taskId }?.let { title = it.title; filledTask = true }
    }
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var permissionVersion by remember { mutableIntStateOf(0) }
    var endConfirm by remember { mutableStateOf<FocusSession?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val alerts = remember { FocusAlerts(context) }
    val allowed = remember(permissionVersion) { alerts.notificationsAllowed() }
    val exact = remember(permissionVersion) { alerts.exactAllowed() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionVersion++ }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionVersion++
        scope.launch { c.focus.reconcile() }
    }
    // This refresh is UI-only. The persisted monotonic deadline and system alarm own the timer.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(accompanied?.id, accompanied?.status) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            accompanied?.let { s ->
                val end = !s.active
                if (FocusCompanionRules.canRequest(s, end, retry = false)) companion.request(s.id, end)
            }
            kotlinx.coroutines.awaitCancellation()
        }
    }
    LaunchedEffect(active?.id, active?.status, active?.segmentElapsed) {
        elapsed = SystemClock.elapsedRealtime()
        if (active?.status != "running") return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                elapsed = SystemClock.elapsedRealtime()
                if (active?.status == "running" && FocusTimer.remaining(active, elapsed) == 0L) c.focus.reconcile()
                delay(500)
            }
        }
    }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        c.appScope.launch {
            try { action() } catch (_: Exception) { error = "这次没有完成，请重试" }
            finally { busy = false }
        }
    }
    val companionCard: @Composable (FocusSession) -> Unit = { s ->
        FocusCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val ta = companions.firstOrNull { it.id == s.companionId }
                    Avatar(ta?.avatar, s.companionName.ifBlank { "TA" }.take(1), 32.dp)
                    Text("${s.companionName.ifBlank { "TA" }} · 陪你这一段", color = palette.content, fontSize = 15.sp)
                }
                val end = !s.active
                val state = FocusCompanionRules.state(s, end)
                val words = FocusCompanionRules.text(s, end)
                Text(when (state) {
                    "ready" -> words.orEmpty()
                    "running" -> "正在等 TA 回应，计时状态已保存。"
                    "pending" -> if (s.status == "interrupted") "这段已中断，没有再请求回应。" else "回应会留在这里，计时状态已保存。"
                    "silent" -> "TA 这次没有留话。"
                    "failed" -> "这次回应没有完成，不影响计时和记录。"
                    else -> "本轮陪伴已停止，已有的话仍保留在记录中。"
                }, color = if (words != null) palette.content else palette.contentSecondary, fontSize = 14.sp)
                if (state == "failed" && FocusCompanionRules.boundary(s, end)) TextButton(onClick = { companion.request(s.id, end, retry = true) }) { Text("重试回应") }
                if (state in setOf("pending", "running", "failed") || (s.active && s.endReplyStatus == "pending"))
                    TextButton(onClick = { run { companion.stop(s.id) } }) { Text("停止本轮陪伴") }
        }
    }
    GlassPage(overlay = { backdrop ->
        GlassTopBar("专注", backdrop, subtitle = "留一点时间给眼前的事", leading = {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, backdrop)
        })
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(pageScroll).statusBarsPadding()
            .navigationBarsPadding().imePadding().padding(top = 84.dp, start = 22.dp, end = 22.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (active == null) accompanied?.let { companionCard(it) }
            if (active != null) {
                val left = FocusTimer.remaining(active, elapsed)
                val accent = palette.accentContent
                val track = palette.content.copy(alpha = .1f)
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(active.title, color = palette.content, fontSize = 19.sp, textAlign = TextAlign.Center)
                    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                            drawCircle(track, style = Stroke(4.dp.toPx()))
                            drawArc(accent, -90f, 360f * left / active.plannedMs.coerceAtLeast(1), false,
                                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (active.phase == "rest") "休息" else "专注", color = palette.contentSecondary, fontSize = 14.sp)
                            Text(time(left), color = palette.content, fontSize = 44.sp, fontWeight = FontWeight.Light)
                            Text(if (active.status == "paused") "已暂停" else "慢慢来", color = palette.contentSecondary, fontSize = 13.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = !busy, onClick = { run { c.focus.action(active.id, if (active.status == "paused") "resume" else "pause") } }) {
                            Text(if (active.status == "paused") "继续" else "暂停")
                        }
                        TextButton(enabled = !busy, onClick = { endConfirm = active }) { Text("提前结束") }
                    }
                    Text(if (active.status == "paused") "暂停期间不计时，准备好后再继续。" else "可以离开这页、切换应用或锁屏，计时会继续。", color = palette.contentSecondary,
                        fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            } else {
                if (last?.status == "completed") FocusCard {
                    Text(if (last.phase == "focus") "这一段，做完了。" else "休息好了。", color = palette.content, fontSize = 19.sp)
                    Text(last.title, color = palette.contentSecondary, fontSize = 14.sp)
                    if (last.phase == "focus") {
                        Button(enabled = !busy, onClick = { run { c.focus.start(last.title, last.taskId, last.restMinutes, last.restMinutes, "rest") } }) {
                            Text("休息 ${last.restMinutes} 分钟")
                        }
                        val todo = todos.firstOrNull { it.id == last.taskId && !it.done }
                        if (todo != null) TextButton(onClick = { run { c.focus.completeTask(todo.id) } }, enabled = !busy) {
                            Text("把这件待办标为完成")
                        }
                    }
                }
                if (last?.status == "interrupted") Text("上次计时已中断，已保存的专注时间仍在记录里。", color = palette.contentSecondary, fontSize = 13.sp)
                FocusCard {
                    Text("开始一段专注", color = palette.content, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    OutlinedTextField(title, { title = it.take(160) }, label = { Text("这段时间想做什么（可选）") },
                        modifier = Modifier.fillMaxWidth(), maxLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(focusInput, { focusInput = it.filter(Char::isDigit).take(3) },
                            label = { Text("专注 / 分钟") }, supportingText = { Text("1–180") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(restInput, { restInput = it.filter(Char::isDigit).take(2) },
                            label = { Text("休息 / 分钟") }, supportingText = { Text("1–60") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                    }
                    val focus = focusInput.toIntOrNull()?.takeIf { it in 1..180 }
                    val rest = restInput.toIntOrNull()?.takeIf { it in 1..60 }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("TA 陪伴", color = palette.content, fontSize = 15.sp)
                            Text(companions.firstOrNull { it.id == companionId }?.name?.ifBlank { "TA" } ?: "这次独自专注",
                                color = palette.contentSecondary, fontSize = 13.sp)
                        }
                        Switch(checked = companionId != null, onCheckedChange = { if (it) chooseCompanion = true else companionId = null })
                    }
                    if (companionId != null) {
                        TextButton(onClick = { chooseCompanion = true }) { Text("换一位 TA") }
                        Text("开始和结束时各回应一次，中途不催促。会将这次的标题、时长和该 TA 的角色设定发给其模型；回应只留在专注页，离开后可回来查看。计时不等回复。",
                            color = palette.contentSecondary, fontSize = 12.sp)
                    }
                    Button(enabled = !busy && focus != null && rest != null, onClick = {
                        run {
                            c.focus.start(title, taskId?.takeIf { id -> todos.any { it.id == id } }, requireNotNull(focus), requireNotNull(rest), companionId = companionId)
                            companionId = null
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("开始专注") }
                    Text("自由专注 · 不限制使用其他应用。结束后可选择休息，待办由你确认完成。", color = palette.contentSecondary, fontSize = 13.sp)
                }
            }
            if (active != null) accompanied?.let { companionCard(it) }
            if (error != null) Text(requireNotNull(error), color = palette.error, fontSize = 13.sp)
            FocusReminderSettings()
            if (!allowed || !exact) FocusCard {
                Text("到时提醒", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (!allowed) {
                    Text("通知尚未开启或结束提醒被关闭，到时仍会保存记录。", color = palette.contentSecondary, fontSize = 13.sp)
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text("开启通知") }
                }
                if (!exact) {
                    Text("允许“闹钟和提醒”后，锁屏也能按时提醒；未允许时，系统可能延后通知，计时记录不受影响。", color = palette.contentSecondary, fontSize = 13.sp)
                    TextButton(onClick = { runCatching { context.startActivity(FocusAlerts.permissionIntent(context)) } }) { Text("允许准时提醒") }
                }
            }
            TextButton(onClick = { history = !history }) { Text(if (history) "收起专注记录" else "专注记录") }
            if (history) {
                val finished = rows.filter { !it.active && it.phase == "focus" }
                if (finished.isEmpty()) Text("还没有专注记录。从一小段时间开始就好。", color = palette.contentSecondary, fontSize = 14.sp)
                else {
                    Text("最近 100 段计时中的专注记录", color = palette.contentSecondary, fontSize = 12.sp)
                    finished.forEach { s ->
                        FocusCard {
                            Text(s.title, color = palette.content, fontSize = 15.sp)
                            val label = when (s.status) { "completed" -> "完成"; "interrupted" -> "中断"; else -> "提前结束" }
                            Text("${date(s.startedAt)} · $label · ${time(FocusTimer.spent(s))}", color = palette.contentSecondary, fontSize = 12.sp)
                            if (s.companionId != null) {
                                Text("${s.companionName.ifBlank { "TA" }} 的陪伴", color = palette.contentSecondary, fontSize = 12.sp)
                                s.startReply?.let { Text(it, color = palette.contentSecondary, fontSize = 13.sp) }
                                s.endReply?.let { Text(it, color = palette.content, fontSize = 13.sp) }
                            }
                        }
                    }
                    TextButton(onClick = { clearConfirm = true }) { Text("清空已结束的记录") }
                }
            }
        }
    }
    endConfirm?.let { s -> AlertDialog(onDismissRequest = { endConfirm = null }, title = { Text("结束这一段？") },
        text = { Text("已经专注的时间会保留，待办不会自动完成。") },
        confirmButton = { TextButton(onClick = { endConfirm = null; run { c.focus.action(s.id, "finish") } }) { Text("结束并保存") } },
        dismissButton = { TextButton(onClick = { endConfirm = null }) { Text("继续专注") } }) }
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("清空已结束的记录？") },
        text = { Text("进行中的计时和待办不受影响。") },
        confirmButton = { TextButton(onClick = { clearConfirm = false; run { c.focus.clearHistory() } }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } })
    if (chooseCompanion) AlertDialog(onDismissRequest = { chooseCompanion = false }, title = { Text("这一段，想和谁一起？") },
        text = { Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            companions.forEach { ta -> TextButton(onClick = { companionId = ta.id; chooseCompanion = false }, modifier = Modifier.fillMaxWidth()) {
                Avatar(ta.avatar, ta.name.ifBlank { "TA" }.take(1), 30.dp)
                Spacer(Modifier.width(12.dp)); Text(ta.name.ifBlank { "TA" })
            } }
            if (companions.isEmpty()) Text("还没有可以选择的 TA。可以先独自专注。")
        } }, confirmButton = { TextButton(onClick = { chooseCompanion = false }) { Text("取消") } })
}

@Composable
private fun FocusCard(content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), style = LocalGlassPalette.current.bar, shape = GlassShape.Rounded(24.dp),
        contentPadding = PaddingValues(20.dp)) { Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

private fun time(ms: Long): String {
    val seconds = (ms + 999) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
private fun date(wall: Long) = DateTimeFormatter.ofPattern("M月d日 HH:mm").format(Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault()))
