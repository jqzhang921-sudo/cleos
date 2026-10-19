package com.cleo.cleos.ui.settings

import android.app.TimePickerDialog
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.cleo.cleos.ai.IncomingCallRules
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.ai.FreeTopicRules
import com.cleo.cleos.glass.LocalGlassPalette

@Composable
internal fun FreeTopicSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    val c = appContainer()
    val settings by c.settings.settings.collectAsStateWithLifecycle(com.cleo.cleos.data.AppSettings())
    var fullScreenAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT<34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        fullScreenAllowed=Build.VERSION.SDK_INT<34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }
    val color = LocalGlassPalette.current.contentSecondary
    Section("想找你聊") {
        ExplainedSwitch("想找你聊", "不必先约好，TA 也可以自己想聊什么",
            "默认关闭。开启后，TA 会偶尔看看最近的聊天和记忆，自行决定聊什么，也可以保持安静。与原来的提醒和「还有句话」分别设置。", vm.freeTopicEnabled) { vm.setFreeTopic(it) }
        if (vm.freeTopicEnabled) {
            ExplainedSwitch("TA 主动来电", "TA 也可以选择打电话给你",
                "默认关闭。开启后，TA 可在想找你聊的机会里邀请语音通话，也能在单聊中应你的邀请打来；你接听后才开麦，无需重启 App。沿用下面的免打扰；每位 TA 可单独设置下方的来电间隔和次数，未接或拒绝也遵守间隔、计入次数，不会连续重拨。请先配好转写、通话模型和 TA 的声音。",vm.incomingCallsEnabled) { vm.setIncomingCalls(it) }
            if (vm.incomingCallsEnabled) {
                ExplainedSwitch("持续横幅与锁屏来电页", "来电提醒多停留一会儿（所有 TA 共用）", null, settings.persistentIncomingAlerts) { on ->
                    c.appScope.launch { c.settings.update { it.copy(persistentIncomingAlerts=on) } }
                }
                Text("开启后，使用手机时请求持续来电横幅，锁屏时可显示来电页；接听后才打开话筒。需要手机允许通知及横幅，实际停留由系统决定。来电最多等候 60 秒。",
                    color=color,fontSize=12.sp,lineHeight=18.sp)
                if(settings.persistentIncomingAlerts) {
                    TextButton(onClick={
                        runCatching { context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID,com.cleo.cleos.Notifier.CHANNEL_INCOMING)) }
                    }) { Text("来电通知设置") }
                }
                if(settings.persistentIncomingAlerts && Build.VERSION.SDK_INT>=34 && !fullScreenAllowed) {
                    TextButton(onClick={
                        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:${context.packageName}"))) }
                    }) { Text("允许锁屏来电页") }
                    Text("尚未允许锁屏全屏提醒，仍会尝试显示来电横幅；若停留仍太短，可在系统中允许。",color=color,fontSize=12.sp)
                }

                IncomingLimitChoice("两次来电至少间隔", IncomingCallRules.intervalText(vm.incomingCallIntervalMinutes),
                    IncomingCallRules.INTERVALS, IncomingCallRules::intervalText, vm::chooseIncomingCallInterval)
                IncomingLimitChoice("最近 24 小时最多来电", "${vm.incomingCallDailyMax} 次",
                    IncomingCallRules.DAILY_LIMITS, { "$it 次" }, vm::chooseIncomingCallDailyMax)
                Text("只影响这位 TA 的主动来电，不限制接通后的通话时长。最短间隔 30 分钟；未接或拒绝也计入次数，关闭再开启不会清除记录。TA 仍会自行决定是否来电。",
                    color = color, fontSize = 12.sp, lineHeight = 18.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeTopicRules.LEVELS.forEach { level ->
                    Chip(level.label, selected = vm.freeTopicLevel == level.id) { vm.chooseFreeTopicLevel(level.id) }
                }
            }
            val level = FreeTopicRules.level(vm.freeTopicLevel)
            Text("TA 可在 ${FreeTopicRules.intervalText(level)}范围内决定下次何时再看看；没有安排时按这个范围自动等待。每天最多 ${level.dailyMax} 次考虑机会，每次会请求模型，用工具时可能请求多轮；选择安静也会消耗 token。", color = color, fontSize = 12.sp, lineHeight = 18.sp)
            ExplainedSwitch("想找你聊免打扰", "这段时间不主动开启新话题",
                "只影响想找你聊，原来约好的提醒和「还有句话」仍按各自设置。起止时间相同表示全天免打扰。", vm.freeTopicQuietOn) { vm.setFreeTopicQuiet(it) }
            if (vm.freeTopicQuietOn) {
                Row {
                    listOf(true, false).forEach { start ->
                        val value = if (start) vm.freeTopicQuietStart else vm.freeTopicQuietEnd
                        TextButton(onClick = {
                            TimePickerDialog(context, { _, hour, minute -> vm.setFreeTopicTime(start, hour * 60 + minute) }, value / 60, value % 60, true).show()
                        }) { Text((if (start) "开始 " else "结束 ") + FreeTopicRules.time(value)) }
                    }
                }
            }
            Text("首次聊天后生效。开始输入会取消旧安排，聊完重新安排，至少留 15 分钟空闲；连续两轮主动消息没收到回复就先停下来。后台执行时间会受手机省电影响，开启和改档后会重新等待下一次机会。", color = color, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

/** Compact choices save immediately for this screen's TA. */
@Composable
private fun IncomingLimitChoice(title: String, value: String, options: List<Int>, label: (Int) -> String, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), color = LocalGlassPalette.current.content, fontSize = 14.sp)
        Box {
            TextButton(onClick = { open = true }) { Text("$value ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(label(option)) }, onClick = { open = false; onPick(option) })
                }
            }
        }
    }
}
