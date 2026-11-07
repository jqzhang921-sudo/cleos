package com.cleo.cleos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.data.MessageStyle
import com.cleo.cleos.glass.LocalGlassPalette

@Composable
internal fun MessageStyleSection(vm: SettingsViewModel) {
    val p = LocalGlassPalette.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val style = MessageStyle.of(vm.messageStyle)
    Section("消息发送偏好") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MessageStyle.entries.forEach { option ->
                FilterChip(selected = style == option, enabled = !vm.messageStyleSaving,
                    onClick = { vm.chooseMessageStyle(option.id) }, label = { Text(option.title) })
            }
        }
        Text(style.description, color = p.content, fontSize = 14.sp, lineHeight = 21.sp)
        Text("仅对这位 TA 的私聊文字生效，选择后自动保存。你当轮明确说「完整说」「别分条」或「分开发」，TA 会优先按这次要求表达。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 19.sp)
        Text("这是表达偏好，实际发送仍取决于模型。连贯长文会尽量保持完整，App 不按字数或换行强拆，也不会因此多请求一次模型。语音回复、通话和群聊沿用原方式。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 19.sp)
        if (ToolGroup.Messages !in settings.tools) Text("分条发送能力目前关闭；可到「能做的事 → 分条消息与表情回应」开启。当前仍可正常发送完整文字。", color = p.contentSecondary, fontSize = 12.sp, lineHeight = 19.sp)
        if (vm.messageStyleSaving) Text("正在保存…", color = p.contentSecondary, fontSize = 12.sp)
        vm.messageStyleError?.let { Text(it, color = p.error, fontSize = 12.sp) }
    }
}
