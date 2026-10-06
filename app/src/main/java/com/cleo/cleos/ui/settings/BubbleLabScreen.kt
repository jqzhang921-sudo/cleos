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
import com.cleo.cleos.data.BubbleDecoration
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
import com.cleo.cleos.data.BubbleBackground

/** Fixed specimen, independently scrolling controls: every drag remains visible. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BubbleLabScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = appViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val palette = LocalGlassPalette.current
    val type = remember(settings.chatTextSize) { ChatType(settings.chatTextSize) }
    var mine by rememberSaveable { mutableStateOf(true) }
    var paddingDetails by rememberSaveable { mutableStateOf(false) }
    var backgroundDetails by rememberSaveable { mutableStateOf(false) }
    var decorationDetails by rememberSaveable { mutableStateOf(false) }
    var selectedComponent by rememberSaveable { mutableStateOf<String?>(null) }
    var sample by rememberSaveable { mutableStateOf("short") }
    var x by remember(settings.bubblePaddingX) { mutableFloatStateOf(settings.bubblePaddingX.toFloat()) }
    var y by remember(settings.bubblePaddingY) { mutableFloatStateOf(settings.bubblePaddingY.toFloat()) }
    var decor by remember(settings.bubbleDecoration) { mutableStateOf(settings.bubbleDecoration) }
    val chosen = BubbleThemes.valid(if (mine) settings.myBubbleTheme else settings.taBubbleThemes[vm.companionId.toString()] ?: "glass")
    val backgroundKey = BubbleBackground.key(mine, vm.companionId, chosen)
    val savedBackground = settings.bubbleBackgrounds[backgroundKey]
    var background by remember(backgroundKey, savedBackground) { mutableStateOf(savedBackground) }
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
                Text("点选装饰后单独编辑，也可拖动微调，松手保存。", color = palette.contentSecondary, fontSize = 12.sp)
                // Size is bounded on small/landscape screens; only the specimen scrolls if needed.
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 170.dp).verticalScroll(rememberScrollState()),
                    contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    ChatBubbleSurface(Modifier.widthIn(max = 280.dp), mine, chosen, x.roundToInt(), y.roundToInt(), decor, background,
                        selectedComponent = selectedComponent,
                        onComponentSelect = { selectedComponent = it; decorationDetails = true },
                        onComponentDrag = { id, dx, dy ->
                            selectedComponent = id
                            decor = decor.copy(components = decor.components.map { item ->
                                if (item.id != id) item else item.copy(offsetX = item.offsetX + dx.roundToInt(),
                                    distance = item.distance + (if (item.corner.startsWith("b")) dy else -dy).roundToInt()).normalized()
                            })
                        },
                        onDecorationDrag = { face, dx, dy ->
                            val corner = if (face) decor.faceCorner else decor.starCorner
                            val actual = if (corner == "auto") (if (chosen == "blue") (if (face) "tr" else "bl") else (if (face) "tl" else "br")) else corner
                            val outward = if (actual.startsWith("t")) -dy else dy
                            decor = (if (face) decor.copy(faceOffsetX = decor.faceOffsetX + dx.roundToInt(), faceDistance = decor.faceDistance + outward.roundToInt())
                                else decor.copy(starOffsetX = decor.starOffsetX + dx.roundToInt(), starDistance = decor.starDistance + outward.roundToInt())).normalized()
                        }, onDecorationDragEnd = { vm.setBubbleDecoration(decor) }) { ink ->
                        if (sample == "voice") Text("▶  8 秒", color = ink, fontSize = 15.sp)
                        else Text(if (sample == "long") "刚刚想起一件小事，想慢慢讲给你听。你在的话，我会很开心。" else "你在呀。",
                            color = ink, style = type.body)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("选主题") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BubbleThemes.choices.forEach { (id, name) -> Chip(name, chosen == id) { vm.setBubbleTheme(mine, id) } }
                    }
                    Text("主题分别保存；留白和装饰调整同时用于两边。", color = palette.contentSecondary, fontSize = 12.sp)
                }
                Section("调松紧") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Triple("紧凑", 10, 6), Triple("标准", 12, 8), Triple("宽松", 16, 12)).forEach { (label, px, py) ->
                            Chip(label, x.roundToInt() == px && y.roundToInt() == py) { vm.setBubblePadding(px, py) }
                        }
                    }
                    Chip(if (paddingDetails) "收起精细调整" else "精细调整", paddingDetails) { paddingDetails = !paddingDetails }
                    if (paddingDetails) {
                        Text("左右留白：${x.roundToInt()}", color = palette.contentSecondary, fontSize = 12.sp)
                        Slider(x, { x = it }, valueRange = 6f..24f, steps = 17, onValueChangeFinished = { vm.setBubblePadding(x.roundToInt(), y.roundToInt()) })
                        Text("上下留白：${y.roundToInt()}", color = palette.contentSecondary, fontSize = 12.sp)
                        Slider(y, { y = it }, valueRange = 4f..16f, steps = 11, onValueChangeFinished = { vm.setBubblePadding(x.roundToInt(), y.roundToInt()) })
                    }
                }
                Section("换装饰") {
                    Text("选择内置装饰，或展开后使用 Emoji、自选图片。", color = palette.contentSecondary, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("跟随主题", decor.faceEnabled && decor.starsEnabled && decor.faceImage == null && decor.starImage == null && decor.faceEmoji.isEmpty() && decor.starEmoji.isEmpty() && decor.components.isEmpty()) { vm.setBubbleDecoration(com.cleo.cleos.data.BubbleDecoration()) }
                        Chip("只留小脸", decor.faceEnabled && !decor.starsEnabled) { vm.setBubbleDecoration(decor.copy(faceEnabled = true, starsEnabled = false, components = decor.components.map { it.copy(enabled = false) })) }
                        Chip("干净气泡", !decor.faceEnabled && !decor.starsEnabled && decor.components.none { it.enabled }) { vm.setBubbleDecoration(decor.copy(faceEnabled = false, starsEnabled = false, components = decor.components.map { it.copy(enabled = false) })) }
                    }
                    Chip(if (decorationDetails) "收起装饰编辑" else "添加装饰 / 编辑", decorationDetails) { decorationDetails = !decorationDetails }
                    if (decorationDetails) BubbleComponentsEditor(decor, selectedComponent, { selectedComponent = it }, { decor = it }, vm::setBubbleDecoration, vm::importBubbleComponent, vm.bubbleImageBusy, vm.bubbleImageError)
                }
                Section("更多设置") {
                    Chip(if (backgroundDetails) "收起材质与配色" else "材质与配色", backgroundDetails) { backgroundDetails = !backgroundDetails }
                    if (backgroundDetails) {
                        BubbleBackgroundEditor(background ?: BubbleBackground.defaults(chosen), { background = it },
                            { vm.setBubbleBackground(mine, chosen, it) }, { background = null; vm.setBubbleBackground(mine, chosen, null) })
                        Text("按当前对象和主题分别保存，不会改动另一边的配色。", color = palette.contentSecondary, fontSize = 12.sp)
                        if (mine && chosen == "glass") MyBubbleColor(settings.myBubble, vm::setMyBubble)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
