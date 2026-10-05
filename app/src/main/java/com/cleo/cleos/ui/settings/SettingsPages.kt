package com.cleo.cleos.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.CrashLog
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.ai.Voice
import com.cleo.cleos.data.ApiPresets
import com.cleo.cleos.data.GlassMode
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.avatarLetter

/**
 * The pages behind the settings' list of groups. Everything used to be on one page, ten
 * screens long; now the list fits about one screen and each group has a page of its own.
 * The first three belong to the TA being talked to, the next three are shared by every TA
 * (as the switches and the voice always were), the rest are the app's.
 */
enum class SettingsPage(val title: String) {
    Profile("资料与性格"),
    Model("模型"),
    Behavior("说话方式"),
    Abilities("能做的事"),
    Voice("TA 的声音"),
    Mcp("外部服务（MCP）"),
    Chat("聊天"),
    VoiceInput("发语音"),
    Look("外观"),
    Data("数据与备份"),
    About("关于"),
    ;

    /** Whose these are, under the page's title: the TA's own, or every TA's. */
    fun subtitle(taName: String): String? = when (this) {
        Profile, Model, Behavior -> taName
        Abilities, Voice, Mcp -> "所有 TA 共用"
        else -> null
    }

    companion object {
        fun of(name: String?): SettingsPage? = entries.firstOrNull { it.name == name }
    }
}

/** The switches on the abilities page, for the count on the list. */
internal val ABILITIES = listOf(
    ToolGroup.Messages, ToolGroup.Speak, ToolGroup.Stickers, ToolGroup.Pat,
    ToolGroup.Todos, ToolGroup.Memory, ToolGroup.Lore, ToolGroup.AiDiary, ToolGroup.Diary, ToolGroup.Secrets, ToolGroup.Letters,
    ToolGroup.Weather, ToolGroup.Location, ToolGroup.Alarm, ToolGroup.Calendar, ToolGroup.Music, ToolGroup.Avatar,
)

/** The list of groups, each line saying how things stand, so most of the time nothing needs opening. */
@Composable
internal fun SettingsDirectory(vm: SettingsViewModel, onOpenLore: (Long) -> Unit, onOpen: (SettingsPage) -> Unit) {
    val palette = LocalGlassPalette.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val servers by vm.mcpServers.collectAsStateWithLifecycle()
    val companions by vm.companions.collectAsStateWithLifecycle()
    val lore by vm.lore.collectAsStateWithLifecycle()
    val hasKey by vm.chat.hasKey.collectAsStateWithLifecycle()
    val ta = companions.firstOrNull { it.id == vm.companionId }
    val name = vm.aiName.trim().ifEmpty { "TA" }
    var switching by remember { mutableStateOf(false) }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    val crashed = remember { CrashLog.read(context) != null }

    ListCard {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(ta?.avatar, ta?.avatarEmoji ?: avatarLetter(vm.aiName, "TA"), 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("正在聊的 TA", color = palette.contentSecondary, fontSize = 12.sp)
            }
            if (companions.size > 1) {
                Spacer(Modifier.width(8.dp))
                Chip("换一个", selected = false) { switching = true }
            }
        }
        RowDivider(inset = 0.dp)
        Entry(Icons.Rounded.Person, "资料与性格", "名字、头像 · " + if (vm.persona.isBlank()) "还没写性格" else "性格 ${vm.persona.length} 字") {
            onOpen(SettingsPage.Profile)
        }
        RowDivider()
        val books = lore.map { it.book.trim() }.filter { it.isNotEmpty() }.distinct().size
        Entry(Icons.Rounded.AutoStories, "设定", when {
            lore.isEmpty() -> "还没有 · 世界观、人物、地点，TA 想用时自己查"
            books == 0 -> "${lore.size} 条 · TA 想用时自己查"
            else -> "$books 本书 ${lore.size} 条 · TA 想用时自己查"
        }) { onOpenLore(vm.companionId) }
        RowDivider()
        val service = ApiPresets.all.firstOrNull { it.baseUrl == vm.chat.baseUrl.trim().trimEnd('/') }?.name ?: host(vm.chat.baseUrl)
        val heard = vm.spoken.model.trim().takeIf { vm.spokenOn && vm.spoken.baseUrl.isNotBlank() }
        val model = listOfNotNull(service, vm.chat.model.trim(), heard?.let { "电话和语音用 $it" }).filter { it.isNotEmpty() }.joinToString(" · ")
        when {
            vm.chat.baseUrl.isBlank() -> Entry(Icons.Rounded.Memory, "模型", "还没接上模型", palette.error) { onOpen(SettingsPage.Model) }
            !hasKey -> Entry(Icons.Rounded.Memory, "模型", "$model · 还没填 Key", palette.error) { onOpen(SettingsPage.Model) }
            else -> Entry(Icons.Rounded.Memory, "模型", model) { onOpen(SettingsPage.Model) }
        }
        RowDivider()
        Entry(Icons.Rounded.Forum, "说话方式", "主动找你 ${onOff(vm.proactive)} · 深度思考 ${onOff(vm.deepThinking)}") {
            onOpen(SettingsPage.Behavior)
        }
    }

    ListCard("所有 TA 共用") {
        Entry(Icons.Rounded.Widgets, "能做的事", "开着 ${ABILITIES.count { it in settings.tools }} 项") { onOpen(SettingsPage.Abilities) }
        RowDivider()
        val voice = vm.voiceService
        when {
            voice == null -> Entry(Icons.Rounded.GraphicEq, "TA 的声音", "还没选", if (ToolGroup.Speak in settings.tools) palette.error else null) {
                onOpen(SettingsPage.Voice)
            }
            else -> Entry(Icons.Rounded.GraphicEq, "TA 的声音", voice.label + if (settings.earVoice) " · 戴耳机时在耳边说" else "") {
                onOpen(SettingsPage.Voice)
            }
        }
        RowDivider()
        Entry(
            Icons.Rounded.Extension,
            "外部服务（MCP）",
            if (servers.isEmpty()) "还没接" else "接了 ${servers.size} 个，开着 ${servers.count { it.enabled }} 个",
        ) { onOpen(SettingsPage.Mcp) }
    }

    ListCard("App") {
        val you = if (vm.userName.isBlank()) "还没填你的名字" else "叫你${vm.userName.trim()}"
        Entry(Icons.Rounded.ChatBubbleOutline, "聊天", "$you · 带上最近 ${vm.historySize} 条 · " + if (settings.chatAvatars) "显示头像" else "不显示头像") {
            onOpen(SettingsPage.Chat)
        }
        RowDivider()
        val transcriber = Voice.presets.firstOrNull { it.baseUrl == vm.voiceBaseUrl.trim().trimEnd('/') }?.name ?: host(vm.voiceBaseUrl)
        Entry(Icons.Rounded.Mic, "发语音", if (vm.voiceBaseUrl.isBlank()) "还没接转文字的服务" else "转文字：$transcriber") {
            onOpen(SettingsPage.VoiceInput)
        }
        RowDivider()
        val glass = when (settings.glassMode) {
            GlassMode.Auto -> "跟随壁纸"
            GlassMode.Light -> "浅色"
            GlassMode.Dark -> "深色"
        }
        Entry(Icons.Rounded.Palette, "外观", (if (settings.wallpaper == null) "默认壁纸" else "自己的壁纸") + " · 玻璃$glass") {
            onOpen(SettingsPage.Look)
        }
        RowDivider()
        Entry(Icons.Rounded.Inventory2, "数据与备份", "导出、恢复、导入记忆") { onOpen(SettingsPage.Data) }
        RowDivider()
        if (crashed) {
            Entry(Icons.Rounded.Info, "关于", "Cleos $version · 上次闪退了，记录在这里", palette.error) { onOpen(SettingsPage.About) }
        } else {
            Entry(Icons.Rounded.Info, "关于", "Cleos $version · 新版本、许可与出处") { onOpen(SettingsPage.About) }
        }
    }

    if (switching) {
        TaPicker(companions, vm.companionId, onPick = {
            switching = false
            vm.switchCompanion(it)
        }, onDismiss = { switching = false })
    }
}

/** Which TA's settings to show; picking one also makes them the one being talked to. */
@Composable
private fun TaPicker(tas: List<CompanionEntity>, current: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("换一个 TA") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(tas, key = { it.id }) { t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(t.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(t.avatar, t.avatarEmoji ?: avatarLetter(t.name, "TA"), 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            t.name.trim().ifEmpty { "TA" },
                            fontSize = 16.sp,
                            fontWeight = if (t.id == current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关上") } },
    )
}

private fun onOff(on: Boolean) = if (on) "开" else "关"

/** The address's host, for a service the presets don't name. */
private fun host(url: String): String = runCatching { java.net.URI(url.trim()).host }.getOrNull() ?: url.trim()

/** A card of rows that sit close, divided by hairlines: lists of switches and of pages to open. */
@Composable
internal fun ListCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalGlassPalette.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassShape.Rounded(24.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    ) {
        Column {
            if (title != null) {
                Text(title, color = palette.accentContent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
            }
            content()
        }
    }
}

/** A hairline between rows, starting where their words do (past the icon) unless [inset] says otherwise. */
@Composable
internal fun RowDivider(inset: androidx.compose.ui.unit.Dp = 46.dp) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(palette.content.copy(alpha = 0.07f)),
    )
}

/** One group to open: its icon, its name, and in one line how it stands ([summaryColor] when it needs seeing to). */
@Composable
private fun Entry(icon: ImageVector, title: String, summary: String, summaryColor: Color? = null, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(interactionSource = null, indication = null, onClickLabel = "打开", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(palette.accent.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.accentContent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(summary, color = summaryColor ?: palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.contentSecondary)
    }
}

/**
 * A switch with what it does in one line; tapping the words shows all of it, [full] (none when
 * the line says it all). The explanations are what made the old page ten screens long: most are
 * read once, and then only take room. The switch alone turns it on and off.
 */
@Composable
internal fun ExplainedSwitch(title: String, line: String, full: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalGlassPalette.current
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .animateContentSize()
                .then(
                    if (full == null) Modifier
                    else Modifier.clickable(interactionSource = null, indication = null, onClickLabel = if (open) "收起说明" else "展开说明") { open = !open },
                ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (open && full != null) {
                Text(full, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        line,
                        color = palette.contentSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (full != null) {
                        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = palette.contentSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = glassSwitchColors(),
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}
