package com.cleo.cleos.ui.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassMotion
import com.cleo.cleos.glass.GlassPalette
import com.cleo.cleos.glass.GlassPalettes
import com.cleo.cleos.glass.GlassPart
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassStyle
import com.cleo.cleos.glass.GlassTabBar
import com.cleo.cleos.glass.GlassTabBarLayoutHeight
import com.cleo.cleos.glass.GlassTuning
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.LocalWallpaperBackdrop
import com.cleo.cleos.glass.WallpaperOverscan
import com.cleo.cleos.glass.backdropSource
import com.cleo.cleos.glass.glassPress
import com.cleo.cleos.glass.liquidGlass
import com.cleo.cleos.glass.rememberBackdrop
import com.cleo.cleos.ui.MainTabBarSideInset
import com.cleo.cleos.ui.MainTabs
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.settings.ExplainedSwitch
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A playground for the glass, and where the app's glass gets tuned.
 *
 * "自由" is free play: one piece to drag over detailed content, a slider per shader knob.
 * Pick a part instead and the piece takes that part's shape and content, starts from what
 * the app uses now, and "用到 App 里" makes the app use it. The piece is drawn exactly as
 * the app will draw it, contrast floor included, so what is seen here is what is got.
 */
@Composable
fun GlassLabScreen(onBack: () -> Unit) {
    val palette = LocalGlassPalette.current
    val c = appContainer()
    val settings by remember { c.settings.settings }.collectAsStateWithLifecycle(initialValue = null)
    val density = LocalDensity.current
    val page = rememberBackdrop()

    var part by remember { mutableStateOf<GlassPart?>(null) }
    var editing by remember { mutableStateOf(palette.chrome) }
    var corner by remember { mutableFloatStateOf(100f) }
    var pieceOffset by remember { mutableStateOf(with(density) { Offset(24.dp.toPx(), 0f) }) }
    var panelOpen by remember { mutableStateOf(true) }
    var previewTab by remember { mutableStateOf(0) }
    val motion = remember { GlassMotion() }

    val saved = part?.let { settings?.glassTuning?.get(it) }
    fun defaultOf(p: GlassPart): GlassStyle = palette.partDefaults[p] ?: palette.surface
    fun select(p: GlassPart?) {
        part = p
        editing = if (p == null) {
            corner = 100f
            palette.chrome
        } else {
            val tuned = settings?.glassTuning?.get(p)
            tuned?.applyTo(defaultOf(p), palette.dark) ?: defaultOf(p)
        }
    }
    val current = part
    val tuning = GlassTuning.of(editing, palette.dark, keepShadow = saved?.shadow)
    val applied = tuning.sameAs(saved, palette.dark)
    val shown = if (current != null) palette.floored(current, editing) else editing

    val previewTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val previewHeight = when (current) {
            GlassPart.TabBar, GlassPart.Lens -> GlassTabBarLayoutHeight
            else -> pieceGeometry(current, corner).second
        }
        val panelMaxHeight = (maxHeight - previewTop - previewHeight).coerceAtLeast(0.dp)
        Box(
            Modifier
                .fillMaxSize()
                .backdropSource(page, behind = LocalWallpaperBackdrop.current, overscan = WallpaperOverscan),
        ) {
            // Only in free play. A part is previewed over what it will sit on in the app,
            // the wallpaper: a tab bar judged over giant black type looks unreadable, which
            // says nothing about how it will look.
            if (current == null) Specimen(Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp))
        }

        if (current == GlassPart.TabBar || current == GlassPart.Lens) {
            CompositionLocalProvider(LocalGlassPalette provides palette.previewTabGlass(current, shown)) {
                GlassTabBar(
                    tabs = MainTabs,
                    selectedIndex = previewTab,
                    onSelect = { previewTab = it },
                    backdrop = page,
                    modifier = Modifier
                        .padding(top = previewTop)
                        .padding(horizontal = MainTabBarSideInset)
                        .fillMaxWidth(),
                )
            }
        } else {
            val (w, h, shape) = pieceGeometry(current, corner)
            Box(
                Modifier
                    .offset { IntOffset(pieceOffset.x.roundToInt(), (previewTop.toPx() + pieceOffset.y).roundToInt()) }
                    .size(w, h)
                    .liquidGlass(page, shown, shape, motion)
                    .glassPress(motion, swell = 4.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            pieceOffset += drag
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                PieceContent(current, palette)
            }
        }

        Row(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page)
            Spacer(Modifier.width(12.dp))
            Text("玻璃实验室", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .heightIn(max = panelMaxHeight)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
                .liquidGlass(page, palette.surface, GlassShape.Rounded(28.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Wraps instead of scrolling: a part hidden off the edge is a part nobody finds.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("自由", selected = current == null) { select(null) }
                GlassPart.entries.forEach { p -> Pill(p.label, selected = current == p) { select(p) } }
            }
            ExplainedSwitch(
                title = "自动补着色",
                line = "按壁纸提高玻璃上文字的对比度",
                full = "此开关适用于全 App。关闭后，玻璃按默认或手动设置的着色显示，复杂壁纸上的文字可能变得难读。",
                checked = palette.autoTint,
            ) { enabled ->
                c.appScope.launch { c.settings.update { it.copy(glassAutoTint = enabled) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun preset(style: GlassStyle) {
                    editing = if (current == null) style else GlassTuning.of(style).applyTo(defaultOf(current), palette.dark)
                }
                Pill("清透") { preset(palette.chrome); if (current == null) corner = 100f }
                Pill("常规") { preset(palette.surface); if (current == null) corner = 24f }
                Pill("透镜") { preset(palette.lensHeld); if (current == null) corner = 100f }
                Spacer(Modifier.weight(1f))
                Pill(if (panelOpen) "收起" else "展开") { panelOpen = !panelOpen }
            }
            if (current != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (applied) "已用在 App 里" else "用到 App 里", selected = !applied) {
                        if (!applied) c.appScope.launch { c.settings.setGlassTuning(current, tuning) }
                    }
                    if (saved != null || editing != defaultOf(current)) {
                        Pill("恢复默认") {
                            editing = defaultOf(current)
                            c.appScope.launch { c.settings.setGlassTuning(current, null) }
                        }
                    }
                }
                val note = when {
                    shown.tint.alpha > editing.tint.alpha + 0.005f ->
                        "上面有字，在现在的壁纸上太透会看不清：着色会补到 %.2f。".format(shown.tint.alpha)
                    current == GlassPart.TopBar -> "调整顶栏按钮和页面里的玻璃操作胶囊。标题可在「标题胶囊」单独调整。"
                    current == GlassPart.Card -> "调整内容卡片和小提示胶囊。"
                    current == GlassPart.TopBarTitle -> "适用于「待办」「说话方式」「世界」「主页」等页面标题。"
                    current == GlassPart.ChatJump -> "只调整聊天里回到最新消息的圆按钮。"
                    current == GlassPart.Lens ->
                        "滑块调整静止效果。可按住或拖动上方底栏，预览透镜移动时的样子。"
                    current == GlassPart.Bubble && palette.autoTint ->
                        "你自己发的气泡也是这块玻璃，染强调色或你在设置里挑的颜色。着色同样会补到字看得清；挑了颜色的至少补到 %.2f，免得颜色透没了。"
                            .format(GlassPalettes.PICKED_TINT)
                    else -> null
                }
                if (note != null) Text(note, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            if (panelOpen) {
                Knob("模糊", editing.blur.value, 0f..30f) { editing = editing.copy(blur = it.dp) }
                Knob("折射", editing.refraction.value, -40f..40f) { editing = editing.copy(refraction = it.dp) }
                Knob("边缘宽度", editing.bevel.value, 0f..48f) { editing = editing.copy(bevel = it.dp) }
                Knob("色散", editing.dispersion, 0f..1f) { editing = editing.copy(dispersion = it) }
                Knob("放大", editing.zoom, 0.6f..1.6f) { editing = editing.copy(zoom = it) }
                Knob("着色", editing.tint.alpha, 0f..1f) { editing = editing.copy(tint = editing.tint.copy(alpha = it)) }
                Knob("饱和度", editing.saturation, 0f..2.5f) { editing = editing.copy(saturation = it) }
                Knob("高光", editing.highlight, 0f..1.5f) { editing = editing.copy(highlight = it) }
                if (!palette.dark) {
                    Knob("阴影", editing.shadowAlpha, 0f..0.5f) { editing = editing.copy(shadowAlpha = it) }
                }
                if (current == null) {
                    Knob("圆角", corner, 0f..100f, label = if (corner >= 100f) "胶囊" else "${corner.roundToInt()}") { corner = it }
                }
            }
        }
    }
}

/** The preview piece takes the size and outline of the part being tuned. */
private fun pieceGeometry(part: GlassPart?, corner: Float): Triple<Dp, Dp, GlassShape> = when (part) {
    GlassPart.Bubble -> Triple(250.dp, 92.dp, GlassShape.Rounded(20.dp))
    GlassPart.Card -> Triple(300.dp, 104.dp, GlassShape.Rounded(24.dp))
    GlassPart.TopBar -> Triple(180.dp, 56.dp, GlassShape.Capsule)
    GlassPart.TopBarTitle -> Triple(138.dp, 56.dp, GlassShape.Capsule)
    GlassPart.Input -> Triple(290.dp, 50.dp, GlassShape.Capsule)
    GlassPart.ChatJump -> Triple(44.dp, 44.dp, GlassShape.Capsule)
    GlassPart.TabBar, GlassPart.Lens -> error("Tab glass uses the full tab bar preview")
    null -> Triple(240.dp, 110.dp, if (corner >= 100f) GlassShape.Capsule else GlassShape.Rounded(corner.dp))
}

/** What sits on the part in the app, so its readability can be judged here. */
@Composable
private fun BoxScope.PieceContent(part: GlassPart?, palette: GlassPalette) {
    when (part) {
        GlassPart.Bubble -> Text(
            "今天过得怎么样？河边那家店开门了吗。",
            color = palette.content,
            fontSize = 16.sp,
            lineHeight = 23.sp,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
        )
        GlassPart.Card -> Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(42.dp)) {
                Text("23", color = palette.accentContent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("周三", color = palette.contentSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("安静的一天", color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("早上去了河边，光很软。", color = palette.content.copy(alpha = 0.78f), fontSize = 14.sp)
            }
        }
        GlassPart.TopBar -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Forum, contentDescription = null, tint = palette.content, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("聊天", color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        GlassPart.TopBarTitle -> Column {
            Text("待办", color = palette.content, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("还有 10 件", color = palette.contentSecondary, fontSize = 12.sp)
        }
        GlassPart.TabBar, GlassPart.Lens -> Unit
        GlassPart.ChatJump -> Icon(Icons.Rounded.ArrowDownward, "回到最新消息", tint = palette.content, modifier = Modifier.size(22.dp))
        GlassPart.Input -> Text(
            "说点什么…",
            color = palette.contentSecondary,
            fontSize = 16.sp,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp),
        )
        null -> Unit
    }
}

/**
 * Plain pills, not glass: they sit on the glass panel, and glass can only see the page
 * behind the panel, so glass buttons here would look like holes cut through it.
 */
@Composable
private fun Pill(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .background(if (selected) palette.accent else palette.content.copy(alpha = 0.07f), CircleShape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = 13.sp, color = if (selected) Color.White else palette.content)
    }
}

@Composable
private fun Knob(
    name: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String = if (range.endInclusive > 5f) "${value.roundToInt()}" else "%.2f".format(value),
    onChange: (Float) -> Unit,
) {
    val palette = LocalGlassPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
        Text(name, color = palette.content, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.content.copy(alpha = 0.15f),
            ),
        )
        Text(label, color = palette.contentSecondary, fontSize = 12.sp, modifier = Modifier.width(44.dp).padding(start = 8.dp))
    }
}

/** High-detail content for the glass to bend: big type, stripes, colour dots, a grid. */
@Composable
private fun Specimen(modifier: Modifier) {
    val palette = LocalGlassPalette.current
    Column(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("液态玻璃", color = palette.content, fontSize = 64.sp, fontWeight = FontWeight.Black)
        Text("Liquid Glass · 光在边缘弯折", color = palette.content, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val colors = listOf(Color(0xFFFF5A5F), Color(0xFFFFB400), Color(0xFF00A699), Color(0xFF007AFF), Color(0xFF8E44FF))
            val bar = size.width / 20f
            for (i in 0 until 20) {
                drawRect(colors[i % colors.size], topLeft = Offset(i * bar, 0f), size = Size(bar * 0.5f, size.height))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val step = 24.dp.toPx()
            var x = 0f
            while (x <= size.width) {
                drawLine(palette.content.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(palette.content.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                y += step
            }
            drawCircle(Color(0xFFFF2D55), 28.dp.toPx(), Offset(size.width * 0.2f, size.height * 0.5f))
            drawCircle(Color(0xFF34C759), 22.dp.toPx(), Offset(size.width * 0.5f, size.height * 0.35f))
            drawCircle(Color(0xFF5856D6), 34.dp.toPx(), Offset(size.width * 0.78f, size.height * 0.6f), style = Stroke(6.dp.toPx()))
        }
        Text(
            "把上面那块玻璃拖到文字和条纹上，看边缘怎样把后面的东西弯进来。按住它，它会亮起来、鼓起来。",
            color = palette.content,
            fontSize = 17.sp,
            lineHeight = 26.sp,
        )
    }
}
