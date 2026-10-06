package com.cleo.cleos.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
    var paddingX by remember(settings.bubblePaddingX) { mutableFloatStateOf(settings.bubblePaddingX.toFloat()) }
    var paddingY by remember(settings.bubblePaddingY) { mutableFloatStateOf(settings.bubblePaddingY.toFloat()) }
    Section("气泡主题") {
        Text("气泡留白", color = palette.content, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("紧凑", 10, 6), Triple("标准", 12, 8), Triple("宽松", 16, 12)).forEach { (label, x, y) ->
                Chip(label, selected = paddingX.toInt() == x && paddingY.toInt() == y) { vm.setBubblePadding(x, y) }
            }
        }
        Text("左右留白：${paddingX.toInt()}", color = palette.contentSecondary, fontSize = 12.sp)
        Slider(value = paddingX, onValueChange = { paddingX = it }, valueRange = 6f..24f, steps = 17,
            onValueChangeFinished = { vm.setBubblePadding(paddingX.toInt(), paddingY.toInt()) })
        Text("上下留白：${paddingY.toInt()}", color = palette.contentSecondary, fontSize = 12.sp)
        Slider(value = paddingY, onValueChange = { paddingY = it }, valueRange = 4f..16f, steps = 11,
            onValueChangeFinished = { vm.setBubblePadding(paddingX.toInt(), paddingY.toInt()) })
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
                ChatBubbleSurface(Modifier.widthIn(max = 260.dp), mine, chosen, paddingX.toInt(), paddingY.toInt()) { ink ->
                    Text("你在呀。", color = ink, fontSize = 15.sp)
                }
                ChatBubbleSurface(Modifier.widthIn(max = 260.dp), mine, chosen, paddingX.toInt(), paddingY.toInt()) { ink ->
                    Text("刚刚想起一件小事，想慢慢讲给你听。你在的话，我会很开心。", color = ink, fontSize = 15.sp, lineHeight = 23.sp)
                }
                ChatBubbleSurface(Modifier.widthIn(max = 260.dp), mine, chosen, paddingX.toInt(), paddingY.toInt()) { ink ->
                    Text("▶  8 秒", color = ink, fontSize = 15.sp)
                }
            }
        }
        Text("默认玻璃保留原来的颜色与玻璃参数。装饰保持固定大小，随消息长度移动；图片和表情包仍独立展示。",
            color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
