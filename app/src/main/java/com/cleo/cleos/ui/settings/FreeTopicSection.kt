package com.cleo.cleos.ui.settings

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
    val color = LocalGlassPalette.current.contentSecondary
    Section("自由找话题") {
        ExplainedSwitch("自由找话题", "不必先约好，TA 也可以自己想聊什么",
            "默认关闭。开启后，TA 会偶尔看看最近的聊天和记忆，自行决定聊什么，也可以保持安静。与原来的提醒和「聊完再说一点」分别设置。", vm.freeTopicEnabled) { vm.setFreeTopic(it) }
        if (vm.freeTopicEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FreeTopicRules.LEVELS.forEach { level ->
                    Chip(level.label, selected = vm.freeTopicLevel == level.id) { vm.chooseFreeTopicLevel(level.id) }
                }
            }
            val level = FreeTopicRules.level(vm.freeTopicLevel)
            Text("TA 可在 ${FreeTopicRules.intervalText(level)}范围内决定下次何时再看看；没有安排时按这个范围自动等待。每天最多 ${level.dailyMax} 次考虑机会，每次会请求模型，用工具时可能请求多轮；选择安静也会消耗 token。", color = color, fontSize = 12.sp, lineHeight = 18.sp)
            ExplainedSwitch("自由找话题免打扰", "这段时间不主动开启新话题",
                "只影响自由找话题，原来约好的提醒和聊完补充仍按各自设置。起止时间相同表示全天免打扰。", vm.freeTopicQuietOn) { vm.setFreeTopicQuiet(it) }
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
