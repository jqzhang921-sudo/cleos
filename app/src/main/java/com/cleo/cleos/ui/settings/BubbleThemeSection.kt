package com.cleo.cleos.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.chat.BubbleThemes
import com.cleo.cleos.ui.chat.ChatBubbleSurface

@Composable
internal fun BubbleThemeSection(settings: AppSettings, vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    Section("气泡主题") {
        Text("分别选择你的气泡和当前 TA 的气泡。文字、语音和正在生成的回复使用同一套外观。",
            color = palette.contentSecondary, fontSize = 12.sp)
        for (mine in listOf(true, false)) {
            val chosen = BubbleThemes.valid(if (mine) settings.myBubbleTheme else settings.taBubbleThemes[vm.companionId.toString()] ?: "glass")
            Text(if (mine) "我的气泡" else "当前 TA 的气泡", color = palette.content, fontSize = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BubbleThemes.choices.forEach { (id, name) ->
                    Chip(name, selected = chosen == id) { vm.setBubbleTheme(mine, id) }
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChatBubbleSurface(Modifier.widthIn(max = 260.dp), mine, chosen) { ink ->
                    Text("你在呀。", color = ink, fontSize = 15.sp)
                }
                ChatBubbleSurface(Modifier.widthIn(max = 260.dp), mine, chosen) { ink ->
                    Text("刚刚想起一件小事，想慢慢讲给你听。你在的话，我会很开心。", color = ink, fontSize = 15.sp, lineHeight = 23.sp)
                }
                ChatBubbleSurface(Modifier.widthIn(max = 260.dp), mine, chosen) { ink ->
                    Text("▶  8 秒", color = ink, fontSize = 15.sp)
                }
            }
        }
        Text("默认玻璃保留原来的颜色与玻璃参数。装饰保持固定大小，随消息长度移动；图片和表情包仍独立展示。",
            color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
