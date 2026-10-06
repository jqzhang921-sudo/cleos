package com.cleo.cleos.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.chat.BubbleThemes
import com.cleo.cleos.ui.chat.ChatBubbleSurface
import com.cleo.cleos.ui.chat.ChatType
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appViewModel
import kotlin.math.roundToInt

/** Fixed specimen, independently scrolling controls: every drag remains visible. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BubbleLabScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val palette = LocalGlassPalette.current
    val type = remember(settings.chatTextSize) { ChatType(settings.chatTextSize) }
    var mine by rememberSaveable { mutableStateOf(true) }
    var sample by rememberSaveable { mutableStateOf("short") }
    var x by remember(settings.bubblePaddingX) { mutableFloatStateOf(settings.bubblePaddingX.toFloat()) }
    var y by remember(settings.bubblePaddingY) { mutableFloatStateOf(settings.bubblePaddingY.toFloat()) }
    var decor by remember(settings.bubbleDecoration) { mutableStateOf(settings.bubbleDecoration) }
    val chosen = BubbleThemes.valid(if (mine) settings.myBubbleTheme else settings.taBubbleThemes[vm.companionId.toString()] ?: "glass")
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    GlassPage(overlay = { backdrop ->
        GlassTopBar(title = "气泡实验室", subtitle = "调整时看着效果 · 松手保存", backdrop = backdrop,
            leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, backdrop) })
    }) {
        if (!vm.loaded) return@GlassPage
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = top + TopBarHeight + 8.dp, bottom = bottom + 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("实时预览") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("我的气泡", mine) { mine = true }
                    Chip("${vm.aiName.ifBlank { "TA" }}的气泡", !mine) { mine = false }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("短句", sample == "short") { sample = "short" }
                    Chip("长消息", sample == "long") { sample = "long" }
                    Chip("语音", sample == "voice") { sample = "voice" }
                }
                // Size is bounded on small/landscape screens; only the specimen scrolls if needed.
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 170.dp).verticalScroll(rememberScrollState()),
                    contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    ChatBubbleSurface(Modifier.widthIn(max = 280.dp), mine, chosen, x.roundToInt(), y.roundToInt(), decor) { ink ->
                        if (sample == "voice") Text("▶  8 秒", color = ink, fontSize = 15.sp)
                        else Text(if (sample == "long") "刚刚想起一件小事，想慢慢讲给你听。你在的话，我会很开心。" else "你在呀。",
                            color = ink, style = type.body)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("主题") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BubbleThemes.choices.forEach { (id, name) -> Chip(name, chosen == id) { vm.setBubbleTheme(mine, id) } }
                    }
                    Text("主题分别保存；留白和装饰调整同时用于两边。", color = palette.contentSecondary, fontSize = 12.sp)
                }
                Section("气泡留白") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Triple("紧凑", 10, 6), Triple("标准", 12, 8), Triple("宽松", 16, 12)).forEach { (label, px, py) ->
                            Chip(label, x.roundToInt() == px && y.roundToInt() == py) { vm.setBubblePadding(px, py) }
                        }
                    }
                    Text("左右留白：${x.roundToInt()}", color = palette.contentSecondary, fontSize = 12.sp)
                    Slider(x, { x = it }, valueRange = 6f..24f, steps = 17, onValueChangeFinished = { vm.setBubblePadding(x.roundToInt(), y.roundToInt()) })
                    Text("上下留白：${y.roundToInt()}", color = palette.contentSecondary, fontSize = 12.sp)
                    Slider(y, { y = it }, valueRange = 4f..16f, steps = 11, onValueChangeFinished = { vm.setBubblePadding(x.roundToInt(), y.roundToInt()) })
                }
                Section("装饰组件") { BubbleDecorationEditor(decor, { decor = it }, vm::setBubbleDecoration) }
                if (mine && chosen == "glass") Section("玻璃底色") { MyBubbleColor(settings.myBubble, vm::setMyBubble) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
