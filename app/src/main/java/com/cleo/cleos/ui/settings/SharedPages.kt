package com.cleo.cleos.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.PhoneCalendar
import com.cleo.cleos.ai.PhoneLocation
import com.cleo.cleos.ai.PhoneMusic
import com.cleo.cleos.ai.Speech
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.ai.VoiceOption
import com.cleo.cleos.ai.VoiceService
import com.cleo.cleos.glass.LocalGlassPalette

/**
 * What every TA can do when asked, in three groups: in the chat itself, keeping things, and
 * the phone's own. A switch that needs a permission asks for it as it is turned on, and says
 * so beneath itself while the permission is missing.
 */
@Composable
internal fun AbilitiesPage(vm: SettingsViewModel, onOpenVoice: () -> Unit) {
    val palette = LocalGlassPalette.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun on(group: ToolGroup) = group in settings.tools

    var locationHint by remember { mutableStateOf<String?>(null) }
    // The switch goes on once the person has let the app use the location, not before.
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            locationHint = null
            vm.setTool(ToolGroup.Location, true)
        } else {
            locationHint = "没给定位权限，TA 查不了位置。"
        }
    }
    var calendarHint by remember { mutableStateOf<String?>(null) }
    // Likewise the calendar, which needs both: reading what is on it, and adding to Cleos's own.
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) {
            calendarHint = null
            vm.setTool(ToolGroup.Calendar, true)
        } else {
            calendarHint = "没给日历权限，TA 看不了也加不了日程。"
        }
    }
    // Notification access for 一起听歌 is a switch in the system settings, not a dialog: whether it
    // got turned on is only known on coming back.
    var musicAllowed by remember { mutableStateOf(PhoneMusic.allowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { musicAllowed = PhoneMusic.allowed(context) }
    fun openMusicAccess() {
        // Some phones have no page for one app's switch; their list has it too.
        runCatching { context.startActivity(PhoneMusic.accessIntent(context)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
    }

    Section(null) {
        Text(
            "在聊天里说一声，TA 就能去做。模型要支持工具调用（function calling）；不支持的会自动不带工具，聊天照常。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }

    ListCard("聊天时") {
        ExplainedSwitch(
            "看朋友圈",
            "聊到时查看 App 内的帖子和评论",
            "你问起或让 TA 看时，TA 可以读取这个 App 内共享的朋友圈和资讯话题。需要模型支持工具调用。这个入口只读，不会点赞、评论或发帖；与每位 TA 的「主动逛朋友圈」开关独立。图片需转发到聊天才能交给支持图片的模型查看。",
            on(ToolGroup.Feed),
        ) { vm.setTool(ToolGroup.Feed, it) }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "发朋友圈与互动",
            "聊天中让 TA 发动态、点赞或留言",
            "TA 可以用自己的身份发表文字动态、点赞或取消自己的赞，以及评论或回复朋友。需要模型支持工具调用；点赞和留言前需开启「看朋友圈」确认对象。不会代替你或其他 TA 操作，也不会赞自己或回复自己的评论。与后台「主动逛朋友圈」独立，开启这里不会开启自动发帖。",
            on(ToolGroup.FeedActions),
        ) { vm.setTool(ToolGroup.FeedActions, it) }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "分条消息与表情回应",
            "分开发消息，也能给你的消息贴表情",
            "TA 可以分成几条消息说，也能偶尔给你的消息贴表情，或者只用一个表情回应。关掉后这两项能力都停用。",
            on(ToolGroup.Messages),
        ) { vm.setTool(ToolGroup.Messages, it) }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "发语音条",
            "道晚安、撒娇时发语音，字也看得到",
            "TA 想用声音说的时候（道晚安、撒娇），发一条语音条，你能听，也看得到字。用什么声音，在「TA 的声音」里选。",
            on(ToolGroup.Speak),
        ) { vm.setTool(ToolGroup.Speak, it) }
        if (on(ToolGroup.Speak) && !Speech.ready(settings)) {
            Under {
                Text("还没配好声音，TA 暂时发不了。", color = palette.error, fontSize = 12.sp)
                Chip("去选声音", selected = false, onClick = onOpenVoice)
            }
        }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "发表情包",
            "偶尔从你的表情包里挑一张发",
            "TA 会从你的表情包里挑着发，偶尔一张（表情包在聊天输入框的笑脸里加）。TA 看不到图，是按名字和说明认的，" +
                "所以名字起得像在说那张图最好。这个不需要模型支持工具。",
            on(ToolGroup.Stickers),
        ) { vm.setTool(ToolGroup.Stickers, it) }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "拍回来",
            "你拍 TA 之后，TA 有时会拍回来",
            "TA 回你话的时候，偶尔会拍你一下，聊天里多一行“TA 拍了拍我”，手机震一下。你连着拍了很多下，TA 也会回一两句。" +
                "拍回来要模型支持工具，连拍之后的那一句不用；关了这个开关，两样都停。",
            on(ToolGroup.Pat),
        ) { vm.setTool(ToolGroup.Pat, it) }
    }

    ListCard("记东西") {
        ExplainedSwitch("待办", "帮你记下、查看、改日期、打勾", null, on(ToolGroup.Todos)) { vm.setTool(ToolGroup.Todos, it) }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "记忆",
            "记下关于你和它自己的事，下次还知道",
            "TA 会自己记下关于你的事和它自己的事，下次还知道。在主页「记忆」里能看、能改、能钉住。",
            on(ToolGroup.Memory),
        ) { vm.setTool(ToolGroup.Memory, it) }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "查设定",
            "有世界观、人物这类设定时，TA 想用时自己去查",
            "你搬进来或自己写的设定（世界观、人物、地点这类），TA 不会一直背着，而是在聊到相关的话时自己去查那一条，" +
                "照原文说。在「资料与性格」下面的「设定」里能看、能改。",
            on(ToolGroup.Lore),
        ) { vm.setTool(ToolGroup.Lore, it) }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "写日记",
            "在同一个本子里写自己的日记",
            "TA 有自己的日记，写在同一个本子里，标着是 TA 写的；你能看，改不了。",
            on(ToolGroup.AiDiary),
        ) { vm.setTool(ToolGroup.AiDiary, it) }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "读你的日记",
            "你提到时去翻，小秘密除外",
            "你提到日记里写过的事时，TA 可以去翻（小秘密除外）。日记最私密，所以默认关着。",
            on(ToolGroup.Diary),
        ) { vm.setTool(ToolGroup.Diary, it) }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "小秘密",
            "知道你有，想看会先问你",
            "TA 知道你有小秘密，但看不到；想看会在聊天里问你，你点头才给看。",
            on(ToolGroup.Secrets),
        ) { vm.setTool(ToolGroup.Secrets, it) }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "写信",
            "有话可写时主动给你写信",
            "TA 隔几天、有话可写时会主动给你写信；聊天里提到信，TA 也接得上。关掉后只回你寄去的信。",
            on(ToolGroup.Letters),
        ) { vm.setTool(ToolGroup.Letters, it) }
        if (on(ToolGroup.Letters)) {
            Under { LetterPace(settings, vm) }
        }
    }

    ListCard("用手机") {
        ExplainedSwitch("查天气", "用 open-meteo 查，不需要 Key", null, on(ToolGroup.Weather)) { vm.setTool(ToolGroup.Weather, it) }
        if (on(ToolGroup.Weather)) {
            Under {
                OutlinedTextField(
                    value = vm.weatherCity,
                    onValueChange = { vm.weatherCity = it },
                    label = { Text("你在的城市") },
                    placeholder = { Text("比如 杭州；不填的话 TA 会问你") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "查位置",
            "问附近、问路时查一下你在哪",
            "TA 需要知道你在哪时（问附近、问路、问天气），用手机定位查一下，地名要联网查。只在聊天中查，不在后台跟踪；每查一次，聊天里都会写一行。",
            on(ToolGroup.Location),
        ) { turnOn ->
            when {
                !turnOn -> vm.setTool(ToolGroup.Location, false)
                PhoneLocation.allowed(context) -> vm.setTool(ToolGroup.Location, true)
                else -> askLocation.launch(PhoneLocation.PERMISSIONS)
            }
        }
        if (on(ToolGroup.Location) && !PhoneLocation.allowed(context)) {
            Under { Text("还没给定位权限，TA 查不了：把开关关掉再打开，会重新问你。", color = palette.error, fontSize = 12.sp, lineHeight = 17.sp) }
        }
        locationHint?.let { Under { Text(it, color = palette.contentSecondary, fontSize = 12.sp) } }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "定闹钟",
            "在手机自带的时钟里定闹钟、计时",
            "你让 TA 定闹钟、计时，它在手机自带的时钟里定，到点手机响，和你自己定的一样。只在 Cleos 开着的时候能定：手机不让 App 在后台打开时钟。",
            on(ToolGroup.Alarm),
        ) { vm.setTool(ToolGroup.Alarm, it) }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "日历",
            "看你的日程，记的加在「Cleos」日历里",
            "TA 能看你手机日历上的安排；你让它记的日程，加在一个叫「Cleos」的日历里，可以带提醒。它只能改、删自己加的，你的日程只能看。要日历权限，打开时会问。",
            on(ToolGroup.Calendar),
        ) { turnOn ->
            when {
                !turnOn -> vm.setTool(ToolGroup.Calendar, false)
                PhoneCalendar.allowed(context) -> vm.setTool(ToolGroup.Calendar, true)
                else -> askCalendar.launch(PhoneCalendar.PERMISSIONS)
            }
        }
        if (on(ToolGroup.Calendar) && !PhoneCalendar.allowed(context)) {
            Under { Text("还没给日历权限，TA 看不了：把开关关掉再打开，会重新问你。", color = palette.error, fontSize = 12.sp, lineHeight = 17.sp) }
        }
        calendarHint?.let { Under { Text(it, color = palette.contentSecondary, fontSize = 12.sp) } }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "一起听歌",
            "知道你在放哪首、唱到哪句",
            "你用手机上的音乐 App 放歌时（网易云、QQ 音乐、酷狗都行），TA 知道在放哪首、唱到哪句，像在旁边一起听；" +
                "聊天页顶上会有一条小播放条。你让 TA 停一下、换一首，它也能帮你切。" +
                "只认音乐 App，抖音、B 站这类放视频的不会读。要开一次「通知使用权」：只用来看在放什么歌，不读你的通知。",
            on(ToolGroup.Music),
        ) { turnOn ->
            vm.setTool(ToolGroup.Music, turnOn)
            if (turnOn && !PhoneMusic.allowed(context)) openMusicAccess()
        }
        if (on(ToolGroup.Music) && !musicAllowed) {
            Under {
                // Android 13 on greys the switch out for apps installed from a downloaded apk
                // (how most get Cleos) until "restricted settings" are allowed in the app's info.
                Text(
                    "还没开「通知使用权」，TA 听不到：点「去开」，把 Cleos 的开关打开。" +
                        "开关是灰的、点不动的话，先点「应用信息」，在右上角 ⋮ 里选「允许受限制的设置」，再回来开。",
                    color = palette.error,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("去开", selected = false) { openMusicAccess() }
                    Chip("应用信息", selected = false) {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        }
                    }
                }
            }
        }
        RowDivider(inset = 0.dp)
        ExplainedSwitch("换自己的头像", "用你发来的图或表情换头像", null, on(ToolGroup.Avatar)) { vm.setTool(ToolGroup.Avatar, it) }
    }
}

/** What belongs to the switch above: a field, a warning, a slider; kept off the line below it. */
@Composable
private fun Under(content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** The voice every TA's voice messages are in, and how it is heard on headphones. */
@Composable
internal fun VoicePage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hasSpeechKey by vm.hasSpeechKey.collectAsStateWithLifecycle()

    Section("用哪个平台") {
        Text(
            "TA 发语音条时用的声音。选一个平台，填上它的 Key，挑一个音色就能用。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceService.entries.forEach { v -> Chip(v.label, selected = vm.voiceService == v) { vm.pickService(v) } }
        }
        val service = vm.voiceService
        if (service == null) {
            Text("还没选：选好之后 TA 才会发语音条。", color = palette.content, fontSize = 13.sp)
        } else {
            VoicePanel(vm, service, hasSpeechKey)
            Chip(if (vm.speechBusy) "正在合成…" else "试听", selected = false) { vm.previewSpeech() }
            vm.speechResult?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
        }
    }

    ListCard {
        ExplainedSwitch(
            "戴耳机时在耳边说",
            "戴耳机听时，声音像贴在耳边",
            "戴着耳机听 TA 的语音条，声音像贴在耳边说话，还会一边说一边慢慢挪：从一只耳朵绕到脑后、到另一只耳朵，或者从面前靠过来。" +
                "用的是假人头在耳边 25 厘米实测的数据。外放时照原样放。一条语音第一次放要先算一下，会晚一点点。",
            settings.earVoice,
        ) { vm.setEarVoice(it) }
    }

    // Setting up a voice doesn't let the TA use it: that is the switch on 「能做的事」.
    if (ToolGroup.Speak !in settings.tools && Speech.ready(settings)) {
        Section(null) {
            Text(
                "声音配好了，不过「能做的事」里的「发语音条」还关着，TA 现在还不会发。",
                color = palette.content,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Chip("打开发语音条", selected = true) { vm.setTool(ToolGroup.Speak, true) }
        }
    }
}

/**
 * One voice service: where its key comes from, its voices to tap, and the key. Each service has
 * only what it needs, so there is nothing to work out: the general way through an MCP tool was
 * dropped because nobody could tell how to fill it in.
 */
@Composable
private fun VoicePanel(vm: SettingsViewModel, service: VoiceService, hasKey: Boolean) {
    val palette = LocalGlassPalette.current
    fun hint(text: String): @Composable () -> Unit = { Text(text, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp) }
    when (service) {
        VoiceService.SiliconFlow -> hint("Key 在硅基流动控制台的「API 密钥」里建。聊天或转文字用的也是硅基流动的话，Key 已经有了。")()
        VoiceService.MiniMax -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("国内版", selected = !vm.minimaxGlobal) { vm.setMinimaxSite(false) }
                Chip("国际版", selected = vm.minimaxGlobal) { vm.setMinimaxSite(true) }
            }
            hint("国内版和国际版是两个网站，Key 不通用。Key 在 MiniMax 开放平台的「接口密钥」里建，账号要先实名认证。")()
        }
        VoiceService.Mossland -> hint("Key 在 Moss 开放平台（platform.mosi.cn）的 API Keys 里建。")()
        VoiceService.ElevenLabs -> hint("Key 在 ElevenLabs 的 Profile → API Keys 里建。填好 Key 可以点「列出我的声音」挑，不用自己找 Voice ID。")()
        VoiceService.OpenAI -> hint("国内网络连不上 OpenAI，要能访问它才行。")()
        VoiceService.Other -> hint("别的平台只要兼容 OpenAI 的语音接口（/audio/speech）也能用，比如一些中转站：填地址、模型和声音。")()
    }

    // The voices: tap one; or, where the service can say, list all it has.
    val builtIn = Speech.builtIn(service)
    val current = vm.voiceOf(service)
    if (builtIn.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            builtIn.forEach { v -> Chip(v.label, selected = current == v.id) { vm.pickVoice(service, v.id) } }
        }
    }
    if (service == VoiceService.MiniMax || service == VoiceService.Mossland || service == VoiceService.ElevenLabs) {
        val what = if (service == VoiceService.ElevenLabs) "列出我的声音" else "列出全部音色"
        Chip(if (vm.listingVoices) "正在列…" else what, selected = false) { vm.listVoices() }
        vm.listProblem?.let { Text(it, color = palette.error, fontSize = 12.sp, lineHeight = 17.sp) }
    }
    when (service) {
        VoiceService.SiliconFlow, VoiceService.MiniMax, VoiceService.Mossland -> {
            Field("音色 ID（上面没有的、自己做的，粘到这里）", current, { vm.pickVoice(service, it.trim()) })
        }
        VoiceService.ElevenLabs -> {
            Field("Voice ID", vm.elevenVoice, { vm.elevenVoice = it })
            Field("模型（不填就是 ${Speech.ELEVENLABS_MODEL}）", vm.elevenModel, { vm.elevenModel = it })
        }
        VoiceService.Other -> {
            Field("接口地址", vm.speechBaseUrl, { vm.speechBaseUrl = it }, keyboardType = KeyboardType.Uri)
            Field("模型", vm.speechModel, { vm.speechModel = it })
            Field("声音", vm.speechVoice, { vm.speechVoice = it })
        }
        VoiceService.OpenAI -> Unit
    }

    OutlinedTextField(
        value = vm.speechKeyInput,
        onValueChange = { vm.speechKeyInput = it },
        // 「MiniMax 的 Key」, 「硅基流动的 Key」: a space only after a Latin name.
        label = { Text(service.label + (if (service.label.last().code < 128) " 的 Key" else "的 Key")) },
        placeholder = { if (hasKey) Text("已保存（不再显示）；要换就重新填") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    if (vm.speechKeyInput.isNotBlank()) Chip("保存 Key", selected = true) { vm.saveSpeechKey() }
    if (hasKey) Text("Key 已经有了。", color = palette.contentSecondary, fontSize = 12.sp)

    vm.listedVoices?.let { voices ->
        VoiceListDialog(voices, current, onPick = {
            vm.pickVoice(service, it.id)
            vm.closeVoiceList()
        }, onDismiss = vm::closeVoiceList)
    }
}

/** Every voice a service has, to search by name and tap. */
@Composable
private fun VoiceListDialog(voices: List<VoiceOption>, current: String, onPick: (VoiceOption) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(voices, query) {
        val q = query.trim()
        if (q.isEmpty()) voices else voices.filter { it.label.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选一个音色（${voices.size} 个）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜名字，比如 温柔、少女、男") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.id }) { v ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(v) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(v.label, fontSize = 15.sp, fontWeight = if (v.id == current) FontWeight.SemiBold else FontWeight.Normal)
                            if (v.label != v.id) Text(v.id, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关上") } },
    )
}

/** The MCP services every TA can use the tools of, each switched on or off here, edited on its own page. */
@Composable
internal fun McpPage(vm: SettingsViewModel, onOpenMcp: (String) -> Unit) {
    val palette = LocalGlassPalette.current
    val servers by vm.mcpServers.collectAsStateWithLifecycle()
    Section("接上的服务") {
        Text(
            "接上 MCP 服务，TA 就能用它们的工具，比如点咖啡、查路线。只支持 Streamable HTTP 的地址，电脑上 stdio 那种接不了。" +
                "地址和 Token 加密存在这台手机上，不进备份。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        for (server in servers) {
            McpRow(server, onOpen = { onOpenMcp(server.id) }) { vm.setMcpEnabled(server.id, it) }
        }
        Chip("添加一个服务", selected = false) { onOpenMcp("") }
    }
}
