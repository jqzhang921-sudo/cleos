package com.cleo.cleos.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.ai.Mcp
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.McpServer
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appViewModel
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlin.math.roundToInt

/**
 * The settings: a list of groups (SettingsDirectory), each opening a page of its own. [start]
 * opens straight on one page, for a way in that is about a single thing ("no model yet" goes to
 * the model). Back from a page comes to the list first.
 *
 * The pages are one screen with one view model, not screens of their own: what is typed on one
 * page is saved with the rest, a while after it stops changing (SettingsViewModel), and the
 * pickers and dialogs each page needs live with that page.
 */
@Composable
fun SettingsScreen(
    start: SettingsPage?,
    onBack: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenBubbleLab: () -> Unit,
    onOpenMcp: (String) -> Unit,
    onOpenPersona: (Long) -> Unit,
    onOpenLore: (Long) -> Unit,
) {
    val vm = appViewModel { SettingsViewModel(it) }
    var page by rememberSaveable { mutableStateOf(start) }
    BackHandler(enabled = page != null) { page = null }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The list keeps its place while a page is open.
    val listScroll = rememberScrollState()

    GlassPage(
        overlay = { backdrop ->
            GlassTopBar(
                title = page?.title ?: "设置",
                subtitle = page?.subtitle(vm.aiName.trim().ifEmpty { "TA" }),
                backdrop = backdrop,
                leading = {
                    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", { if (page != null) page = null else onBack() }, backdrop)
                },
            )
        },
    ) {
        if (!vm.loaded) return@GlassPage
        // Cross-fading like the app's screens (CleosNavHost): glass that slides would show the wallpaper from where it started.
        AnimatedContent(
            targetState = page,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
            label = "settings page",
        ) { shown ->
            val scroll = if (shown == null) listScroll else rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .fadeUnderTopBar(statusTop + TopBarHeight)
                    .imePadding()
                    .verticalScroll(scroll)
                    .padding(start = 14.dp, end = 14.dp, top = statusTop + TopBarHeight + 8.dp, bottom = navBottom + 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (shown) {
                    null -> SettingsDirectory(vm, onOpenLore) { page = it }
                    SettingsPage.Profile -> ProfilePage(vm, onOpenPersona = { onOpenPersona(vm.companionId) }, onLeave = onBack)
                    SettingsPage.Model -> ModelPage(vm)
                    SettingsPage.Behavior -> BehaviorPage(vm)
                    SettingsPage.Abilities -> AbilitiesPage(vm, onOpenVoice = { page = SettingsPage.Voice })
                    SettingsPage.Voice -> VoicePage(vm)
                    SettingsPage.Mcp -> McpPage(vm, onOpenMcp)
                    SettingsPage.Chat -> ChatPage(vm)
                    SettingsPage.VoiceInput -> VoiceInputPage(vm)
                    SettingsPage.Look -> LookPage(vm, onOpenLab, onOpenBubbleLab)
                    SettingsPage.Data -> DataPage(vm)
                    SettingsPage.About -> AboutPage()
                }
            }
        }
    }
}

/** A card of fields and words, [title] inside it: above it, it would be bare text on the wallpaper. */
@Composable
internal fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalGlassPalette.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassShape.Rounded(24.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, color = palette.accentContent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/**
 * How often a TA writes of their own accord, in a panel under the letters switch while it
 * is on. When a reply comes is picked on each letter as it is sent, not here. The slider is
 * saved when it is let go, not at every step of a drag.
 */
@Composable
internal fun LetterPace(settings: AppSettings, vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    var every by remember(settings.letterEveryDays) { mutableFloatStateOf(settings.letterEveryDays.toFloat()) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.content.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "TA 自己写信，两封至少隔 ${every.roundToInt()} 天",
            color = palette.content,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = every,
            onValueChange = { every = it },
            onValueChangeFinished = { vm.setLetterEveryDays(every.roundToInt()) },
            valueRange = 1f..14f,
            steps = 12,
        )
        Text(
            "隔够了也不一定写：这段时间聊过一阵，或者有新日记，TA 才会动笔。你寄的信，回信什么时候到在寄的时候选。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

/** One MCP service: tap it to edit, switch it on and off beside. */
@Composable
internal fun McpRow(server: McpServer, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val palette = LocalGlassPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .clickable(interactionSource = null, indication = null, onClick = onOpen),
        ) {
            Text(server.name.ifBlank { "没起名字" } + " ›", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            // Never the address as stored: a key in it would show.
            Text(Mcp.displayUrl(server.url), color = palette.contentSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = server.enabled, onCheckedChange = onToggle, colors = glassSwitchColors())
    }
}

@Composable
internal fun glassSwitchColors() = LocalGlassPalette.current.let { palette ->
    SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = palette.accent,
        checkedBorderColor = palette.accent,
        uncheckedThumbColor = palette.contentSecondary,
        uncheckedTrackColor = palette.content.copy(alpha = 0.07f),
        uncheckedBorderColor = palette.contentSecondary.copy(alpha = 0.6f),
    )
}

@Composable
internal fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A plain pill, not glass: these sit on a glass card, and glass inside the page can only
 * see the wallpaper, so a glass chip here would look like a hole cut through the card.
 */
@Composable
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .background(if (selected) palette.accent else palette.content.copy(alpha = 0.07f), CircleShape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) Color.White else palette.content, fontSize = 14.sp)
    }
}

/** What 「看许可全文」 shows: where the ear's data comes from, and the licence of the code it was ported from. */
internal val NOTICES = listOf("hrir/README.txt", "licenses/binaural-voice.txt")

/** How much of the persona the profile page shows: a few lines' worth, never all of it. */
internal const val PERSONA_PREVIEW = 200
