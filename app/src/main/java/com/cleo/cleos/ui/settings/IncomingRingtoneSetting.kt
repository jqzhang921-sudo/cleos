package com.cleo.cleos.ui.settings

import android.app.NotificationManager
import android.content.Intent
import android.media.RingtoneManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.cleo.cleos.Notifier
import com.cleo.cleos.glass.LocalGlassPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The notification channel owns ringtone, silence and vibration in foreground and background. */
@Composable
internal fun IncomingRingtoneSetting() {
    val context = LocalContext.current
    val palette = LocalGlassPalette.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("跟随系统来电通知") }
    var problem by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch {
            title = withContext(Dispatchers.IO) {
                runCatching {
                    val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(Notifier.CHANNEL_INCOMING)
                    when {
                        channel == null -> "跟随手机铃声"
                        channel.importance == NotificationManager.IMPORTANCE_NONE -> "来电通知已关闭"
                        channel.sound == null || channel.importance < NotificationManager.IMPORTANCE_DEFAULT -> "静音"
                        else -> RingtoneManager.getRingtone(context, channel.sound)?.getTitle(context) ?: "系统设置中的铃声"
                    }
                }.getOrDefault("系统设置中的铃声")
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("来电铃声", color = palette.content, fontSize = 14.sp)
                Text(title, color = palette.contentSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = {
                problem = null
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.CHANNEL_INCOMING))
                }.onFailure { problem = "请到手机设置 → 应用 → Cleos → 通知 → TA 主动来电中设置" }
            }) { Text("选择铃声") }
        }
        Text("在系统的来电通知设置中选择铃声、静音或振动，所有 TA 共用；前台和后台沿用同一设置，并受手机静音与勿扰设置控制。",
            color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        problem?.let { Text(it, color = palette.error, fontSize = 12.sp, lineHeight = 18.sp) }
    }
}
