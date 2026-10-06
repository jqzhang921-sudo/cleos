package com.cleo.cleos.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.CrashLog
import com.cleo.cleos.R
import com.cleo.cleos.Releases
import com.cleo.cleos.ai.Voice
import com.cleo.cleos.data.GlassMode
import com.cleo.cleos.data.PatRecord
import com.cleo.cleos.data.Pats
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.chat.ChatType
import com.cleo.cleos.ui.chat.PatDialog
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the person is called, how much of the conversation goes along, and the avatars beside messages. */
@Composable
internal fun ChatPage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    val settings by vm.settings.collectAsStateWithLifecycle()

    Section("你") {
        Field("你的名字", vm.userName, { vm.userName = it })
    }

    Section("TA 记得多少") {
        Text("每次原样带上最近 ${vm.historySize} 条消息", color = palette.content, fontSize = 14.sp)
        Slider(
            value = vm.historySize.toFloat(),
            onValueChange = { vm.historySize = (it / 10f).roundToInt() * 10 },
            valueRange = 10f..200f,
            steps = 18,
        )
        Text(
            "更早的，TA 会自己整理成前情提要带着，不会一下子忘掉；在聊天里往上翻，能看到分界的那一行，点开能看、能改。原样带得越多，细节记得越清楚，每次花的 token 也越多。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }

    Section("字号") {
        val type = ChatType(settings.chatTextSize)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatType.SIZES.forEach { size ->
                val label = when (size) {
                    14 -> "小"
                    15 -> "标准"
                    16 -> "大"
                    else -> "特大"
                }
                Chip("$label $size", selected = settings.chatTextSize == size) { vm.setChatTextSize(size) }
            }
        }
        // What a bubble looks like at that size.
        GlassSurface(
            style = palette.bubble,
            shape = GlassShape.Rounded(20.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("今天也辛苦啦。晚饭吃了吗？我刚才还在想，要不要提醒你早点睡。", color = palette.content, style = type.body)
        }
    }

    // Moved here from the home page, where they sat among the TA's things.
    ListCard("头像") {
        ExplainedSwitch("聊天里显示头像", "消息旁边放上各自的头像", null, settings.chatAvatars) { vm.setChatAvatars(it) }
        ExplainedSwitch("默认展开语音文字", "关闭时显示紧凑语音条，点“查看文字”展开；展开或收起都不影响播放", null, settings.expandVoiceText, vm::setExpandVoiceText)
        if (settings.chatAvatars) {
            RowDivider(inset = 0.dp)
            ExplainedSwitch(
                "每条都显示",
                "像微信那样每条消息都带头像",
                "像微信那样每条消息都带头像；关着时，连着的几条只在最后一条旁边放一个。",
                settings.avatarEachMessage,
            ) { vm.setAvatarEachMessage(it) }
        }
    }

    // 拍一拍: a double tap on an avatar says it, and the long press in the chat opens the very
    // same dialog — here as well, since a double tap is not a thing anyone is told to try.
    ListCard("拍一拍") {
        var editing by remember { mutableStateOf(false) }
        val said = Pats.line(PatRecord(Pats.AI, 1, settings.patVerb, settings.patSuffix), vm.aiName)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("双击头像拍一下", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "现在拍出来是「$said」。TA 不会为它单独回话，下次你说话时才知道。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Chip("改一改", selected = false) { editing = true }
        }
        RowDivider(inset = 0.dp)
        ExplainedSwitch("拍的时候震一下", "拍一下时手机跟着轻轻震一下", null, settings.patBuzz) { vm.setPatBuzz(it) }
        if (editing) {
            PatDialog(
                aiName = vm.aiName,
                verb = settings.patVerb,
                suffix = settings.patSuffix,
                buzz = settings.patBuzz,
                onSave = { verb, suffix, buzz ->
                    vm.setPat(verb, suffix, buzz)
                    editing = false
                },
                onDismiss = { editing = false },
            )
        }
    }
}

/** The service a voice message is turned into words by, before the TA reads it. */
@Composable
internal fun VoiceInputPage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    val hasVoiceKey by vm.hasVoiceKey.collectAsStateWithLifecycle()
    Section("转文字的服务") {
        Text(
            "在聊天里按住输入框右边的话筒说话，松开就发，往上滑再松开是取消。语音先转成文字再给 TA 看，" +
                "所以要接一个转文字的服务，和聊天的模型分开选。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Voice.presets.forEach { p ->
                Chip(p.name, selected = vm.voiceBaseUrl.trimEnd('/') == p.baseUrl) { vm.applyVoicePreset(p) }
            }
        }
        Field("接口地址", vm.voiceBaseUrl, { vm.voiceBaseUrl = it }, keyboardType = KeyboardType.Uri)
        Field("模型", vm.voiceModel, { vm.voiceModel = it })
        OutlinedTextField(
            value = vm.voiceKeyInput,
            onValueChange = { vm.voiceKeyInput = it },
            label = { Text("API Key") },
            placeholder = { if (hasVoiceKey) Text("已保存（不再显示）；要换就重新填") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.voiceKeyInput.isNotBlank()) Chip("保存 Key", selected = true) { vm.saveVoiceKey() }
            if (vm.voiceBaseUrl.isNotBlank()) Chip(if (vm.voiceTesting) "正在试…" else "试一下", selected = false) { vm.testVoice() }
        }
        Text(
            if (hasVoiceKey) "这个地址的 Key 已经有了。" else "Key 跟着地址存：和哪个 TA 聊天用的是同一个地址，就不用再填。模型名以服务商的说明为准。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        vm.voiceResult?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
    }
}

/** The wallpaper, the glass over it, and the colour of the person's own bubbles. */
@Composable
internal fun LookPage(vm: SettingsViewModel, onOpenLab: () -> Unit) {
    val palette = LocalGlassPalette.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setWallpaper(uri)
    }

    Section("壁纸") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(if (vm.wallpaperBusy) "正在换…" else "换壁纸", selected = false) {
                if (!vm.wallpaperBusy) {
                    wallpaperPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            if (settings.wallpaper != null) Chip("用回默认", selected = false) { vm.resetWallpaper() }
        }
        vm.wallpaperError?.let { Text(it, color = palette.error, fontSize = 13.sp) }
    }

    Section("玻璃") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("跟随壁纸", selected = settings.glassMode == GlassMode.Auto) { vm.setGlassMode(GlassMode.Auto) }
            Chip("浅色", selected = settings.glassMode == GlassMode.Light) { vm.setGlassMode(GlassMode.Light) }
            Chip("深色", selected = settings.glassMode == GlassMode.Dark) { vm.setGlassMode(GlassMode.Dark) }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(interactionSource = null, indication = null, onClickLabel = "打开", onClick = onOpenLab),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("玻璃实验室", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("拖一块玻璃，看每个参数在做什么", color = palette.contentSecondary, fontSize = 12.sp)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.contentSecondary)
        }
    }

    // It names itself ("我的气泡"), so its card has no title.
    Section(null) {
        MyBubbleColor(settings.myBubble, vm::setMyBubble)
    }
}

/** Backups out and back in, and memory in from anywhere else. */
@Composable
internal fun DataPage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var confirmUndo by remember { mutableStateOf(false) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingRestore = uri
    }
    val memoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMemoryFile(uri)
    }

    Section("备份") {
        Text(
            "把每个 TA、聊天、日记、信、记忆、收藏、待办、表情包、图片和语音打包成一个文件。换手机、重装之前先导出一份。API Key 不会导出。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (vm.backupBusy) "正在处理…" else "导出备份", selected = false) {
                if (!vm.backupBusy) exportPicker.launch("cleos-备份-${java.time.LocalDate.now()}.zip")
            }
            Chip("从备份恢复", selected = false) {
                if (!vm.backupBusy) restorePicker.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        }
        if (vm.canUndoRestore) {
            Chip("撤销上次恢复", selected = false) { confirmUndo = true }
        }
    }

    Section("从别的 app 搬过来") {
        Text(
            "选一个文件，四样都认：角色卡（PNG 图片卡也认；一张新开一个 TA，文件里几张就开几个，别的东西都归第一个）、" +
                "世界书（进「设定」，聊天时 TA 自己去查）、记忆库、历史聊天（接成一个对话）。也可以只搬记忆：" +
                "JSON、纯文本、Markdown 都行，一段或一行一件，「名字: 内容」拆成两半。" +
                "记忆同名的并进已有那件，缺的细节补上；一类最多记 10 件，多的放进「设定」；设定里已经有的条目不会重复进来。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        // Any type: a file passed along through a chat app often comes back without a JSON type.
        Chip(if (vm.memoryBusy) "正在导入…" else "选个文件", selected = false) {
            if (!vm.memoryBusy) memoryPicker.launch(arrayOf("*/*"))
        }
        vm.memoryMessage?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
    }

    vm.backupMessage?.let { message ->
        Section(null) { Text(message, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("用这份备份替换现在的内容？") },
            text = {
                Text("现在的 TA、聊天、日记、信、记忆、收藏和待办会被备份里的内容替换掉。恢复之前会自动把现在的留一份，恢复完可以撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    vm.restoreBackup(uri)
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } },
        )
    }

    if (confirmUndo) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("撤销上次恢复？") },
            text = { Text("回到恢复之前的 TA、聊天、日记和待办。恢复之后新写的会没有。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUndo = false
                    vm.undoRestore()
                }) { Text("撤销") }
            },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("取消") } },
        )
    }
}

/**
 * The WeChat code to pay the developer through. A phone can't scan its own screen, so the code
 * can be kept in the gallery, and picked from there in WeChat's scanner.
 */
@Composable
private fun TipDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("请开发者喝杯奶茶") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(
                    painterResource(R.drawable.tip_qr),
                    contentDescription = "微信收款码",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                )
                Text(
                    said ?: "在这台手机上付的话：先存到相册，再打开微信「扫一扫」，点右上角的相册选这张图。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = { scope.launch { said = saveTipCode(context) } }) { Text("存到相册") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** Copies the code into the gallery, under Pictures/Cleos; what to tell the person comes back. */
private suspend fun saveTipCode(context: Context): String = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Cleos-milk-tea.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Cleos")
        // Hidden from the gallery until it is all there.
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
        ?: return@withContext "相册没让存。截个图也一样能扫。"
    runCatching {
        resolver.openOutputStream(uri)!!.use { out -> context.resources.openRawResource(R.drawable.tip_qr).use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        "存好了，在相册的「Cleos」里。打开微信「扫一扫」，点右上角的相册选它就行。"
    }.getOrElse {
        runCatching { resolver.delete(uri, null, null) }
        "没存上（${it.message ?: it.javaClass.simpleName}）。截个图也一样能扫。"
    }
}

/** The version and where new ones are, the last crash when there was one, privacy, and credits. */
@Composable
internal fun AboutPage() {
    val palette = LocalGlassPalette.current
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
    var releasesHint by remember { mutableStateOf<String?>(null) }
    val updates = com.cleo.cleos.ui.common.appContainer().updates
    val checkingUpdate by updates.checking.collectAsStateWithLifecycle()
    val updateFeedback by updates.feedback.collectAsStateWithLifecycle()
    var showNotices by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Section("Cleos ${version.orEmpty()}") {
        Text(
            "新版本都放在蓝奏云上。更新时直接装新的 apk、覆盖安装，聊天记录都还在；别先卸载，卸载会把这台手机上的聊天、" +
                "日记一起清掉。真要重装，先在「数据与备份」里导出一份备份。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Chip(if (checkingUpdate) "正在检查更新…" else "检查更新", selected = false) {
            if (!checkingUpdate) scope.launch { updates.check(manual = true) }
        }
        updateFeedback?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
        Chip("去蓝奏云看新版", selected = false) {
            // The page asks for the code once; it is on the clipboard by then.
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("提取码", Releases.CODE))
            releasesHint = "正在找能打开的地址…"
            // On whichever of 蓝奏云's domains still exists: one of them going away stranded every copy of an
            // older version on a page that never loads.
            scope.launch {
                val url = Releases.reachableUrl()
                val opened = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                releasesHint = if (opened) {
                    "提取码 ${Releases.CODE} 已经复制好了，页面让输密码时粘贴就行。"
                } else {
                    "没找到能打开网页的浏览器。地址是 $url ，提取码 ${Releases.CODE}（已复制）。"
                }
            }
        }
        releasesHint?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
    }

    // For whoever wants to give something back. Only here: nothing in the app ever asks for it.
    var showTip by remember { mutableStateOf(false) }
    Section("请开发者喝杯奶茶") {
        Text(
            "Cleos 是一个人做的，一直免费。觉得好用、想请开发者喝杯奶茶的话，可以用微信扫一下。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Chip("看收款码", selected = false) { showTip = true }
    }
    if (showTip) TipDialog { showTip = false }

    // The last crash, to send to whoever makes the app (CrashLog): there only after one.
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    var crashHint by remember { mutableStateOf<String?>(null) }
    crash?.let { text ->
        Section("上次闪退") {
            Text(
                "Cleos 上次闪退了（${text.lineSequence().first().substringAfter("，").substringBefore(" 闪退")}）。" +
                    "复制下来发给做 App 的人，就能知道是哪里出的错；里面是出错的位置，不是聊天内容，发之前也可以先看一眼。",
                color = palette.content,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("复制闪退记录", selected = false) {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Cleos 闪退记录", text))
                    crashHint = "复制好了。"
                }
                Chip("清掉", selected = false) {
                    CrashLog.clear(context)
                    crash = null
                    crashHint = null
                }
            }
            crashHint?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
        }
    }

    // Something that failed out of sight and was let go of, with the app going on (CrashLog.note).
    var note by remember { mutableStateOf(CrashLog.readNote(context)) }
    var noteHint by remember { mutableStateOf<String?>(null) }
    note?.let { text ->
        Section("后台出错") {
            Text(
                "Cleos 后台有件事没做成（${text.lineSequence().first().substringAfter("，").substringBefore(" 后台出错")}）：" +
                    "App 没有退出，就是那件事被丢下了。复制下来发给做 App 的人，就能知道是哪里出的错；" +
                    "里面是出错的位置，不是聊天内容，发之前也可以先看一眼。",
                color = palette.content,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("复制出错记录", selected = false) {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Cleos 出错记录", text))
                    noteHint = "复制好了。"
                }
                Chip("清掉", selected = false) {
                    CrashLog.clearNote(context)
                    note = null
                    noteHint = null
                }
            }
            noteHint?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
        }
    }

    Section("隐私") {
        Text(
            "聊天、日记和待办都只存在这台手机上。API Key 用系统密钥库加密。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }

    Section("用到的别人的东西") {
        // Credit the data's licence asks for, where the people who get the app can see it.
        Text(
            "语音条「在耳边」用的是 J. M. Arend、A. Neidhardt、C. Pörschmann 发布的 Neumann KU100 近场 HRIR 数据" +
                "（Zenodo，doi:10.5281/zenodo.4297951，CC BY 4.0；Cleos 只留了三档距离、换了存法），" +
                "做法移植自 binaural-voice（github.com/Saekisui/binaural-voice，MIT）。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Chip("看许可全文", selected = false) { showNotices = true }
    }

    if (showNotices) {
        // Read from the files that ship with the app, so what is shown is what the licences say, word for word.
        val notices = remember {
            NOTICES.joinToString("\n\n————\n\n") { path ->
                runCatching { context.assets.open(path).use { it.readBytes().decodeToString() } }.getOrDefault("")
            }
        }
        AlertDialog(
            onDismissRequest = { showNotices = false },
            title = { Text("用到的别人的东西") },
            text = {
                Text(
                    notices,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showNotices = false }) { Text("好") } },
        )
    }
}
