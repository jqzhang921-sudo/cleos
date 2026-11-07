package com.cleo.cleos.ui.focus

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.cleo.cleos.focus.FocusAlerts
import com.cleo.cleos.glass.*

/** Reports what the system actually exposes. Never changes permissions or bypasses DND. */
@Composable
internal fun FocusReminderSettings() {
    val context = LocalContext.current
    val palette = LocalGlassPalette.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起提醒设置" else "提醒没有出现？") }
    if (!expanded) return
    val manager = context.getSystemService(NotificationManager::class.java)
    val power = context.getSystemService(PowerManager::class.java)
    val alerts = remember(revision) { FocusAlerts(context) }
    val allowed = remember(revision) { alerts.notificationsAllowed() }
    val exact = remember(revision) { alerts.exactAllowed() }
    val unrestricted = remember(revision) { power.isIgnoringBatteryOptimizations(context.packageName) }
    val restricted = remember(revision) { context.getSystemService(ActivityManager::class.java).isBackgroundRestricted }
    val powerSave = remember(revision) { power.isPowerSaveMode }
    val dnd = remember(revision) { when (manager.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "未开启"
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> "系统未提供状态"
        else -> "可能抑制提醒"
    } }
    val channel = remember(revision) { when (manager.getNotificationChannel(FocusAlerts.DONE)?.importance) {
        null -> "尚未建立"
        NotificationManager.IMPORTANCE_NONE -> "已关闭"
        NotificationManager.IMPORTANCE_MIN, NotificationManager.IMPORTANCE_LOW -> "静默"
        NotificationManager.IMPORTANCE_DEFAULT -> "普通"
        else -> "高优先级"
    } }
    fun open(intent: Intent) { problem = runCatching { context.startActivity(intent); true }.getOrDefault(false).not() }
    GlassSurface(Modifier.fillMaxWidth(), style = palette.bar, shape = GlassShape.Rounded(24.dp), contentPadding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("到时提醒检查", color = palette.content, fontSize = 15.sp)
            Text("结束通知：${if (allowed) "已允许" else "未允许"} · 准时提醒：${if (exact) "已允许" else "未允许"}", color = palette.contentSecondary, fontSize = 13.sp)
            Text("结束通知频道：$channel", color = palette.contentSecondary, fontSize = 13.sp)
            Text("后台受限：${if (restricted) "是" else "否"} · 电池优化：${if (unrestricted) "已豁免" else "未豁免"}", color = palette.contentSecondary, fontSize = 13.sp)
            Text("省电模式：${if (powerSave) "已开启" else "未开启"} · 勿扰：$dnd", color = palette.contentSecondary, fontSize = 13.sp)
            Text("计时通知平时保持安静；到时提醒由系统通知设置控制。允许通知不一定会弹横幅，还可以检查锁屏、横幅和电池管理。", color = palette.contentSecondary, fontSize = 12.sp)
            TextButton(onClick = { open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, FocusAlerts.DONE)) }) { Text("检查结束通知") }
            TextButton(onClick = { open(FocusAlerts.permissionIntent(context)) }) { Text("检查闹钟和提醒") }
            TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("检查应用电池与后台设置") }
            if (problem) Text("系统没有打开这个设置页，可以在手机设置里查找 Cleos。", color = palette.contentSecondary, fontSize = 12.sp)
        }
    }
}
