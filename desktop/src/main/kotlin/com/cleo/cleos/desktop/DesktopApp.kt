package com.cleo.cleos.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.nio.file.Path
import java.awt.datatransfer.Transferable
import androidx.compose.ui.layout.ContentScale

internal val LocalMediaRoot = staticCompositionLocalOf<Path> { Path.of(".") }

private val Ink = Color(0xff3d354b)
private val Muted = Color(0xff81788e)
private val Accent = Color(0xff77659c)
private val SoftLine = Color.White.copy(alpha = .72f)
private val Tones = listOf(Color(0xffd8cee9), Color(0xffd7e4e0), Color(0xfff1d9cb), Color(0xffd2dfed), Color(0xffead3de))
private val Shape = RoundedCornerShape(28.dp)

@Composable
fun DesktopApp(vm: DesktopController, libraryName: String = "我的空间", chooseLibrary: (() -> Unit)? = null, importBackup: (() -> Unit)? = null, receivePhone: (() -> Unit)? = null, desktopBackup: (() -> Unit)? = null,chooseImages:(suspend ()->List<Path>)?=null,clipboard:(()->Transferable?)?=null) {
    val scheme = lightColorScheme(primary = Accent, onPrimary = Color.White, background = Color(0xffeeeaf2), surface = Color(0xfffaf8fc), onSurface = Ink,
        onSurfaceVariant = Muted, outline = Color(0xffc9c0d5), error = Color(0xffa65361))
    CompositionLocalProvider(LocalMediaRoot provides vm.store.directory,LocalAudioPlayback provides vm.audio) {
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        var configure by remember { mutableStateOf<DesktopTa?>(null) }
        var newTa by remember { mutableStateOf(false) }
        var newGroup by remember { mutableStateOf(false) }
        var showTools by remember { mutableStateOf(false) }
        var toolSheet by remember { mutableStateOf(false) }
        var destination by remember { mutableStateOf(DesktopDestination.Chats) }
        val pageStates = rememberSaveableStateHolder()
        val notebookCache = remember(vm.store.directory) { PhoneNotebookCache() }
        val keepsCache = remember(vm.store.directory) { PhoneKeepsCache() }
        val chatScrolls = remember { mutableMapOf<String,LazyListState>() }
        val chat = vm.selected
        var detailSheet by remember(chat.id) { mutableStateOf(false) }
        var highlighted by remember(chat.id) { mutableStateOf<String?>(null) }
        val scroll = remember(chat.id) { chatScrolls.getOrPut(chat.id) { LazyListState(chat.messages.lastIndex.coerceAtLeast(0)) } }
        val nearEnd by remember(scroll) { derivedStateOf { !scroll.canScrollForward } }
        LaunchedEffect(chat.id,chat.messages.size,chat.messages.lastOrNull()?.text?.length,chat.messages.lastOrNull()?.reasoning?.length) {
            if(destination==DesktopDestination.Chats && nearEnd && chat.messages.isNotEmpty())scroll.scrollToItem(chat.messages.lastIndex)
        }
        val members = chat.memberIds.mapNotNull { id -> vm.data.companions.find { it.id == id } }
        val imageScope=rememberCoroutineScope()
        fun locate(messageId: String) {
            toolSheet = false
            imageScope.launch {
                if (vm.selected.id != chat.id) return@launch
                val index = vm.selected.messages.indexOfFirst { it.id == messageId }
                if (index >= 0) { scroll.scrollToItem(index); highlighted = messageId }
            }
        }
        LaunchedEffect(highlighted) { if (highlighted != null) { delay(2400); highlighted = null } }
        fun offerImage(offer:ImageOffer) {imageScope.launch {vm.attachOffer(offer,chat.id)}}
        fun pasteImage(showMissing:Boolean):Boolean {
            if(clipboard==null)return false
            return try {
                val offer=clipboard()?.let(::readImageOffer)
                if(offer==null) {if(showMissing)vm.imageInputProblem("剪贴板里没有图片，可以先复制截图或 PNG/JPEG 文件。");false}
                else {
                    if(vm.busyThread!=null || vm.mediaBusy || chat.readOnly)vm.imageInputProblem("请等当前操作完成后再粘贴图片。") else offerImage(offer)
                    true
                }
            } catch(e:Exception) {vm.imageInputProblem((e as? TextFailure)?.message?:"剪贴板暂时无法读取，请稍后再试。");true}
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Wallpaper()
            val wide = maxWidth >= 1240.dp
            val compact = maxWidth < 1040.dp
            fun navigate(target:DesktopDestination) {
                detailSheet=false;toolSheet=false
                destination=target
            }
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NavigationRail(vm,Modifier.width(if(compact)64.dp else 72.dp).fillMaxHeight(),libraryName,chooseLibrary,destination,::navigate)
                pageStates.SaveableStateProvider(destination.name) {
                if(destination==DesktopDestination.Chats) {
                Sidebar(vm, Modifier.width(if (compact) 188.dp else 230.dp).fillMaxHeight(), { newTa = true }, { newGroup = true }, libraryName)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ChatHeader(vm.data.title(chat), members, chat.group, wide && showTools, { detailSheet = true }, { if (wide) showTools = !showTools else toolSheet = true })
                    Spacer(Modifier.height(12.dp))
                    ImageDropArea(vm.busyThread==null && !vm.mediaBusy && !chat.readOnly,::offerImage,vm::imageInputProblem,Modifier.weight(1f).fillMaxWidth()) {
                    Glass(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            val scope = rememberCoroutineScope()
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                if (chat.messages.isEmpty()) EmptyChat(members.firstOrNull(), { configure = members.firstOrNull() })
                                else LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).testTag("聊天消息列表"), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                                    items(chat.messages, key = { it.id }) { message ->
                                        Box(Modifier.fillMaxWidth().testTag("消息-${message.id}").background(if (message.id == highlighted) Accent.copy(alpha = .14f) else Color.Transparent, RoundedCornerShape(20.dp)).padding(4.dp)) {
                                        MessageRow(message, vm.data, message.id == chat.messages.lastOrNull()?.id && vm.busyThread == null && !chat.readOnly, { vm.retry(message.id) }, if (!chat.group) members.firstOrNull()?.userAvatar ?: vm.data.userAvatar else vm.data.userAvatar)
                                        }
                                    }
                                }
                                if (chat.messages.isNotEmpty() && !nearEnd) FilledTonalButton(
                                    { scope.launch { scroll.animateScrollToItem(chat.messages.lastIndex) } },
                                    Modifier.align(Alignment.BottomCenter).padding(12.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = .94f))) { Text("↓  最新消息") }
                            }
                            if (vm.notice.isNotEmpty()) Row(Modifier.fillMaxWidth().background(Color(0xfffff0e8).copy(alpha = .8f)).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(vm.notice, Modifier.weight(1f), color = Color(0xff926c58), fontSize = 12.sp)
                                TextButton(vm::clearNotice) { Text("知道了") }
                            }
                            if (vm.busyThread != null) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Accent)
                                Spacer(Modifier.width(8.dp))
                                Text(if (vm.busyThread == chat.id) "${vm.activeTa ?: "TA"} 正在回复" else "另一段对话正在回复", color = Muted, fontSize = 12.sp)
                            }
                            if (chat.readOnly) Text("只读历史 · 已离开的成员不会重新加入", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(24.dp))
                            else key(chat.id) {Composer(vm,chat.draft, vm::draft, vm.busyThread != null, vm::send, vm::stop,chat.draftImages,vm.mediaBusy,
                                {if(chooseImages!=null)scope.launch {try {vm.attachImages(chooseImages())}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){vm.imagePickerFailed()}}},vm::removeDraftImage,
                                members.filter {it.endpoint.supportsImages}.joinToString("、") {it.name},{pasteImage(it)},clipboard!=null)}
                        }
                    }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 6.dp, end = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("留一点空间，慢慢聊。", color = Muted, fontSize = 11.sp)
                        Text("记录保存在这台电脑", color = Muted, fontSize = 11.sp)
                    }
                }
                if (wide && showTools) Glass(Modifier.width(280.dp).fillMaxHeight()) {
                    ConversationTools(vm.data, chat, Modifier.fillMaxSize(), ::locate)
                }
                } else if(destination==DesktopDestination.Settings) {
                    DesktopSettingsPage(vm,libraryName,receivePhone,importBackup,desktopBackup,Modifier.weight(1f).fillMaxHeight()){navigate(DesktopDestination.Chats)}
                } else if(destination==DesktopDestination.Letters || destination==DesktopDestination.Favorites) {
                    PhoneKeepsPage(vm.store.directory,vm.data,libraryName,destination==DesktopDestination.Favorites,keepsCache,Modifier.weight(1f).fillMaxHeight()){navigate(DesktopDestination.Chats)}
                } else {
                    PhoneNotebookPage(vm.store.directory,vm.data,libraryName,destination==DesktopDestination.Todos,notebookCache,Modifier.weight(1f).fillMaxHeight()){navigate(DesktopDestination.Chats)}
                }
                }
            }
            if (!wide && toolSheet) Dialog(onDismissRequest = { toolSheet = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.width((maxWidth-64.dp).coerceIn(320.dp,400.dp)).height((maxHeight-64.dp).coerceIn(360.dp,720.dp)),shape=Shape,color=MaterialTheme.colorScheme.surface.copy(alpha=.98f),border=BorderStroke(1.dp,SoftLine),shadowElevation=16.dp) {
                    Column { Row(Modifier.fillMaxWidth().padding(start=22.dp,end=10.dp),verticalAlignment=Alignment.CenterVertically) { Text("当前对话",Modifier.weight(1f),color=Muted,fontSize=12.sp);TextButton({toolSheet=false}){Text("关闭常用")} }
                        ConversationTools(vm.data,chat,Modifier.weight(1f).fillMaxWidth(),::locate) }
                }
            }
            if (detailSheet) Dialog(onDismissRequest = { detailSheet = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.width((maxWidth-64.dp).coerceIn(320.dp,560.dp)).height((maxHeight-64.dp).coerceIn(360.dp,720.dp)),shape=Shape,color=MaterialTheme.colorScheme.surface.copy(alpha=.98f),border=BorderStroke(1.dp,SoftLine),shadowElevation=16.dp) {
                    Column { Row(Modifier.fillMaxWidth().padding(start = 22.dp,end=10.dp,top=8.dp), verticalAlignment = Alignment.CenterVertically) { Text("对话资料", Modifier.weight(1f),color=Ink,fontWeight=FontWeight.SemiBold); TextButton({ detailSheet = false }) { Text("关闭资料") } }
                        Details(vm, members, Modifier.weight(1f).fillMaxWidth(), { configure = it }) }
                }
            }
        }
        if (newTa) NameDialog("认识一位 TA", "TA 的名字", { newTa = false }) { name -> configure = vm.addCompanion(name); newTa = false }
        if (newGroup) GroupDialog(vm.data.companions, { newGroup = false }) { name, ids -> if (vm.createGroup(name, ids)) newGroup = false }
        configure?.let { ta -> ModelDialog(ta, vm.hasKey(ta), { configure = null }) { updated, key, remove -> if (vm.configure(updated, key, remove)) configure = null } }
    }
    }
}

@Composable private fun Wallpaper() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(listOf(Color(0xffeee7ef), Color(0xffe5e9ee), Color(0xfff5ece3)), Offset.Zero, Offset(size.width, size.height)))
        drawCircle(Brush.radialGradient(listOf(Color(0xffd2c2ec).copy(alpha = .64f), Color.Transparent), Offset(size.width * .65f, size.height * .15f), size.width * .5f), size.width * .5f, Offset(size.width * .65f, size.height * .15f))
        drawCircle(Brush.radialGradient(listOf(Color(0xfff4cfbb).copy(alpha = .55f), Color.Transparent), Offset(size.width * .1f, size.height), size.width * .45f), size.width * .45f, Offset(size.width * .1f, size.height))
        drawCircle(Color.White.copy(alpha = .18f), size.height * .3f, Offset(size.width * .92f, size.height * .68f))
    }
}

@Composable private fun Glass(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.shadow(16.dp, Shape, spotColor = Color(0xff9c8bb3).copy(alpha = .12f), ambientColor = Color.Transparent)
        .clip(Shape).background(Brush.linearGradient(listOf(Color.White.copy(alpha = .64f), Color.White.copy(alpha = .32f))))
        .border(1.dp, SoftLine, Shape), content = content)
}

@Composable private fun Avatar(ta: DesktopTa?, size: Int = 42, group: Boolean = false, file: String? = ta?.avatar, fallback: String = "我") {
    val image = if (!group) rememberMedia(LocalMediaRoot.current, file) else null
    Box(Modifier.size(size.dp).clip(CircleShape).background(Tones[(ta?.tone ?: 0).mod(Tones.size)]).border(1.dp, Color.White.copy(alpha = .8f), CircleShape), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, "头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(if (group) "✦" else ta?.name?.take(1) ?: fallback.take(1), color = Accent, fontSize = (size * .36f).sp, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun NavigationRail(vm:DesktopController,modifier:Modifier,libraryName:String,chooseLibrary:(()->Unit)?,destination:DesktopDestination,navigate:(DesktopDestination)->Unit) {
    var personalMenu by remember {mutableStateOf(false)}
    val ready=vm.busyThread==null && !vm.mediaBusy
    Glass(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal=6.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.padding(bottom=12.dp).size(34.dp).clip(RoundedCornerShape(13.dp)).background(Accent),contentAlignment=Alignment.Center) {Text("C",color=Color.White,fontSize=22.sp,fontWeight=FontWeight.Bold)}
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(DesktopDestination.Chats,DesktopDestination.Diary,DesktopDestination.Todos,DesktopDestination.Letters,DesktopDestination.Favorites).forEach {target->NavigationButton(target,destination==target,ready || target==DesktopDestination.Chats){navigate(target)}}
            }
            NavigationButton(DesktopDestination.Settings,destination==DesktopDestination.Settings,ready){navigate(DesktopDestination.Settings)}
            Box {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled=ready,onClickLabel="打开个人菜单"){personalMenu=true}
                    .semantics(mergeDescendants=true){contentDescription="个人菜单"}.padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Avatar(null,28,file=vm.data.userAvatar,fallback=vm.data.userName)
                    Text("我",fontSize=10.sp,color=Muted)
                }
                DropdownMenu(personalMenu,{personalMenu=false},modifier=Modifier.width(238.dp),shape=RoundedCornerShape(20.dp),containerColor=Color(0xfffaf8fc)) {
                    Column(Modifier.padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(vm.data.userName.ifBlank {"我"},fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                        Text(libraryName,fontSize=11.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(color=SoftLine)
                    DropdownMenuItem(text={Text("设置")},onClick={personalMenu=false;navigate(DesktopDestination.Settings)},modifier=Modifier.semantics {contentDescription="个人菜单设置"})
                    if(chooseLibrary!=null)DropdownMenuItem(text={Text("切换资料库")},onClick={personalMenu=false;chooseLibrary()},modifier=Modifier.semantics {contentDescription="切换资料库"})
                }
            }
        }
    }
}

@Composable private fun NavigationButton(target:DesktopDestination,selected:Boolean,enabled:Boolean,click:()->Unit) {
    val color=if(!enabled)Muted.copy(alpha=.4f)else if(selected)Accent else Muted
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if(selected)Color.White.copy(alpha=.85f)else Color.Transparent)
        .selectable(selected,enabled=enabled,role=Role.Tab,onClick=click).semantics(mergeDescendants=true){contentDescription="${target.label}导航"}.padding(vertical=12.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        NavigationGlyph(target,color)
        Text(target.label,fontSize=10.sp,color=color,fontWeight=if(selected)FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable private fun Sidebar(vm: DesktopController, modifier: Modifier, add: () -> Unit, group: () -> Unit, libraryName: String) {
    var search by rememberSaveable { mutableStateOf("") }
    var onlyGroups by rememberSaveable { mutableStateOf(false) }
    Glass(modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.padding(6.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Cleos", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink); Text(libraryName, fontSize = 10.sp, color = Muted,maxLines=1,overflow=TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().semantics { contentDescription = "搜索对话" }, placeholder = { Text("搜索 TA 或群聊", fontSize = 12.sp) }, singleLine = true, shape = RoundedCornerShape(18.dp))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ onlyGroups = false }, contentPadding = PaddingValues(8.dp)) { Text("全部", color = if (!onlyGroups) Accent else Muted) }
                TextButton({ onlyGroups = true }, contentPadding = PaddingValues(8.dp)) { Text("群聊", color = if (onlyGroups) Accent else Muted) }
                Spacer(Modifier.weight(1f))
                TextButton(add, enabled = vm.busyThread == null && !vm.mediaBusy, contentPadding = PaddingValues(8.dp), modifier = Modifier.semantics { contentDescription = "添加 TA" }) { Text("＋", fontSize = 22.sp) }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.data.conversations.filter { (!onlyGroups || it.group) && vm.data.title(it).contains(search, ignoreCase = true) }, key = { it.id }) { c ->
                    val selected = c.id == vm.selected.id
                    val ta = vm.data.companions.find { it.id == c.memberIds.firstOrNull() }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (selected) Color.White.copy(alpha = .77f) else Color.Transparent)
                        .clickable { vm.select(c.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(ta, 38, c.group); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(vm.data.title(c), color = Ink, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(c.messages.lastOrNull()?.let { if (it.text.isBlank()) "回复未完成" else it.text.replace('\n', ' ') } ?: if (c.group) "${c.memberIds.size} 位 TA · 一起聊聊" else "从一句问候开始", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            OutlinedButton(group, Modifier.fillMaxWidth(), enabled = vm.data.companions.size >= 2 && vm.busyThread == null, border = BorderStroke(1.dp, SoftLine), shape = RoundedCornerShape(18.dp)) { Text("＋  发起群聊", fontSize = 12.sp) }
        }
    }
}

@Composable private fun ChatHeader(title: String, members: List<DesktopTa>, group: Boolean, tools: Boolean, details: () -> Unit, toggle: () -> Unit) {
    Glass(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable(onClickLabel="查看对话资料",onClick=details).semantics {contentDescription="查看对话资料"}.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Avatar(members.firstOrNull(), group = group); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (group) "${members.size} 位 TA · 轮流回复" else members.firstOrNull()?.endpoint?.model.orEmpty(), color = Muted, fontSize = 11.sp)
            }
            }
            TextButton(toggle,modifier=Modifier.semantics {contentDescription="常用功能"}) { Text(if (tools) "收起常用 ›" else "常用 ‹", fontSize = 12.sp) }
        }
    }
}

@Composable private fun EmptyChat(ta: DesktopTa?, configure: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val short=maxHeight<440.dp
    Column(Modifier.fillMaxSize().padding(horizontal=if(short)20.dp else 40.dp,vertical=if(short)16.dp else 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if(!short){Avatar(ta, 76); Spacer(Modifier.height(22.dp))}
        Text("在大一点的世界里，继续聊天。", color = Ink, fontSize = if(short)16.sp else 21.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(if(short)10.dp else 12.dp))
        Text("给 TA 配好模型，就能从这里开始。", color = Muted, fontSize = if(short)12.sp else 13.sp)
        Spacer(Modifier.height(if(short)14.dp else 24.dp))
        FilledTonalButton(configure, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = .8f), contentColor = Accent)) { Text("配置 TA 的模型") }
        Spacer(Modifier.height(12.dp)); Text("从个人菜单的设置里导入手机备份", color = Muted, fontSize = 11.sp)
    }
    }
}

@Composable private fun MessageRow(m: Message, data: DesktopData, retryable: Boolean, retry: () -> Unit, userAvatar: String?) {
    if (m.notice) { Text(m.text, color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(10.dp)); return }
    val user = m.speakerId == Message.USER
    val ta = data.companions.find { it.id == m.speakerId }
    val clipboard = LocalClipboardManager.current
    var thought by rememberSaveable(m.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!user) { Avatar(ta, 32, file = m.speakerAvatar ?: ta?.avatar, fallback = m.speakerName.ifBlank { "TA" }); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(if (user) .88f else .94f), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Text(if (user) data.userName else m.speakerName.ifBlank { ta?.name ?: "TA" }, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            ContextMenuArea(items = { listOf(ContextMenuItem("复制正文") { clipboard.setText(AnnotatedString(m.text)) }) }) {
                Column(Modifier.clip(RoundedCornerShape(22.dp)).background(if (user) Color(0xffe1d6ed).copy(alpha = .7f) else Color.White.copy(alpha = .65f)).border(1.dp, SoftLine, RoundedCornerShape(22.dp)).padding(18.dp, 14.dp)) {
                    if (m.reasoning.isNotEmpty()) {
                        TextButton({ thought = !thought }, contentPadding = PaddingValues(0.dp)) { Text(if (thought) "✦  收起思考  ⌃" else "✦  思考  ⌄", color = Muted, fontSize = 11.sp) }
                        if (thought) SelectionContainer { Text(m.reasoning, color = Muted, fontSize = 12.sp, lineHeight = 20.sp) }
                    }
                    if (m.text.isNotEmpty()) SelectionContainer { Text(m.text, color = Ink, fontSize = 14.sp, lineHeight = 24.sp) }
                    else if (m.status == "streaming") Text("···", color = Muted, fontSize = 20.sp)
                    m.attachments.forEach { attachment -> Spacer(Modifier.height(8.dp)); AttachmentView(LocalMediaRoot.current, attachment) }
                    if (m.error.isNotEmpty()) {
                        Text(m.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        if (retryable) TextButton(retry, contentPadding = PaddingValues(0.dp)) { Text("再试一次", fontSize = 12.sp) }
                    }
                }
            }
            Text(DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault())), color = Muted.copy(alpha = .7f), fontSize = 10.sp, modifier = Modifier.padding(5.dp))
        }
        if (user) { Spacer(Modifier.width(10.dp)); Avatar(null, 32, file = userAvatar, fallback = data.userName) }
    }
}

@Composable private fun Composer(vm:DesktopController,text: String, change: (String) -> Unit, busy: Boolean, send: () -> Unit, stop: () -> Unit,images:List<Attachment>,adding:Boolean,attach:()->Unit,remove:(String)->Unit,visionNames:String,paste:(Boolean)->Boolean,canPaste:Boolean) {
    var input by remember {mutableStateOf(TextFieldValue(text,TextRange(text.length)))}
    val enterGate=remember {ComposerEnterGate()}
    if(input.text!=text)input=TextFieldValue(text,TextRange(text.length))
    Column(Modifier.fillMaxWidth().heightIn(max=390.dp).padding(16.dp).clip(RoundedCornerShape(23.dp)).background(Color.White.copy(alpha = .72f)).border(1.dp, Color.White, RoundedCornerShape(23.dp)).padding(14.dp, 8.dp)) {
        Column(Modifier.weight(1f,fill=false).fillMaxWidth().verticalScroll(rememberScrollState())) {
        VoiceDraftView(vm)
        if(images.isNotEmpty()) {
            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {items(images,key={it.file}) {image ->
                Column(Modifier.width(100.dp)) {
                    val bitmap=rememberMedia(LocalMediaRoot.current,image.file)
                    if(bitmap!=null)Image(bitmap,"待发送图片",Modifier.size(80.dp).clip(RoundedCornerShape(10.dp)),contentScale=ContentScale.Crop)
                    TextButton({remove(image.file)},enabled=!busy && !adding) {Text("移除图片",fontSize=11.sp)}
                }
            }}
            Text(if(visionNames.isBlank()) "请先为支持看图的模型开启看图功能。" else "图片将发送给：$visionNames；其他成员只收到文字。",fontSize=10.sp,color=Muted)
        }
        }
        OutlinedTextField(input, {input=it;change(it.text)}, Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 156.dp).semantics { contentDescription = "输入消息" }.onPreviewKeyEvent {
            if(it.key==Key.V && it.isCtrlPressed && !it.isAltPressed && !it.isShiftPressed && it.type==KeyEventType.KeyDown) return@onPreviewKeyEvent paste(false)
            if(it.key!=Key.Enter && it.key!=Key.NumPadEnter)return@onPreviewKeyEvent false
            if(it.type==KeyEventType.KeyUp){enterGate.release();return@onPreviewKeyEvent false}
            if(it.type!=KeyEventType.KeyDown)return@onPreviewKeyEvent false
            when(enterGate.press(input.composition!=null,it.isShiftPressed,it.isAltPressed,it.isMetaPressed,busy || adding)) {
                EnterAction.Pass->false
                EnterAction.Consume->true
                EnterAction.Send->{send();true}
            }
        }, placeholder = { Text("说点什么…", color = Muted, fontSize = 14.sp) }, maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(attach,enabled=!busy && !adding && images.size<4) {Text(if(vm.addingImages) "添加中…" else "＋ 图片",fontSize=11.sp)}
            if(canPaste)TextButton({paste(true)},enabled=!busy && !adding && images.size<4) {Text("粘贴图片",fontSize=11.sp)}
            if(vm.selected.draftVoice==null)TextButton(vm::startRecording,enabled=!busy && !adding){Text("录音",fontSize=11.sp)}
            Spacer(Modifier.weight(1f))
            Button(if (busy) stop else send, enabled = busy || (!adding && (text.isNotBlank() || images.isNotEmpty() || vm.selected.draftVoice!=null)), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(18.dp, 8.dp)) { Text(if (busy) "■  停止" else "发送  ↑", fontSize = 12.sp) }
        }
        Text("Enter 发送  ·  Shift + Enter 换行", color = Muted, fontSize = 10.sp)
    }
}

@Composable private fun Details(vm: DesktopController, members: List<DesktopTa>, modifier: Modifier, configure: (DesktopTa) -> Unit) {
    var imported by remember { mutableStateOf<Pair<List<ImportedMemory>, List<ImportedLore>>?>(null) }
        Column(modifier.verticalScroll(rememberScrollState()).padding(22.dp)) {
            members.forEach { ta ->
                Row(verticalAlignment = Alignment.CenterVertically) { Avatar(ta, 42); Spacer(Modifier.width(12.dp)); Column { Text(ta.name, color = Ink, fontSize = 16.sp); Text(if (vm.hasKey(ta)) "模型已配置" else "等待配置模型", color = Muted, fontSize = 11.sp) } }
                Spacer(Modifier.height(15.dp))
                Text(ta.persona.ifBlank { "还没有写下 TA 的设定，可以在配置中添加。" }, color = Muted, fontSize = 12.sp, lineHeight = 21.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                TextButton({ configure(ta) }, enabled = vm.busyThread == null && !vm.mediaBusy, contentPadding = PaddingValues(0.dp)) { Text("角色与模型设置  ↗", fontSize = 12.sp) }
                if (!vm.selected.group && (ta.memories.isNotEmpty() || ta.lore.isNotEmpty())) TextButton({ imported = ta.memories to ta.lore }, contentPadding = PaddingValues(0.dp)) { Text("手机记忆 ${ta.memories.size} · 设定 ${ta.lore.size}", fontSize = 12.sp) }
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = SoftLine)
                Spacer(Modifier.height(20.dp))
            }
            if (vm.selected.group && vm.selected.sharedNotes.isNotEmpty()) TextButton({ imported = vm.selected.sharedNotes to emptyList() }) { Text("群内共同记录 ${vm.selected.sharedNotes.size}", fontSize = 12.sp) }
            val ta = members.firstOrNull()
            if (ta != null) {
                var notes by remember(ta.id, ta.notes) { mutableStateOf(ta.notes) }
                Text("留给 ${ta.name} 的笔记", color = Ink, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text("保存后会在你们的聊天中提供给 TA。", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 240.dp).semantics { contentDescription = "给 TA 的笔记" }, placeholder = { Text("写下想让 TA 记住的事…", fontSize = 12.sp) }, shape = RoundedCornerShape(18.dp), maxLines = 8, textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 20.sp))
                TextButton({ vm.notes(ta.id, notes) }, enabled = notes != ta.notes && vm.busyThread == null, modifier = Modifier.align(Alignment.End)) { Text("保存笔记", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(24.dp))
            vm.data.importOrigin?.let { origin ->
                Text("已从手机备份导入 · 原始备份完整保留", color = Muted, fontSize = 10.sp, lineHeight = 18.sp)
                if (origin.missingMedia.isNotEmpty()) Text("${origin.missingMedia.size} 个附件在原备份中缺失", color = Muted, fontSize = 10.sp)
            }
        }
    imported?.let { ImportedRecordsDialog("手机保留的记录", it.first, it.second) { imported = null } }
}

@Composable private fun ConversationTools(data: DesktopData, chat: Conversation, modifier: Modifier, locate: (String) -> Unit) {
    var query by rememberSaveable(chat.id) { mutableStateOf("") }
    var hits by remember(chat.id, query) { mutableStateOf(emptyList<ChatSearchHit>()) }
    var searching by remember(chat.id, query) { mutableStateOf(query.isNotBlank()) }
    val media = remember(chat.id, chat.messages) { recentConversationMedia(chat) }
    LaunchedEffect(chat.id, query, chat.messages) {
        if (query.isNotBlank()) {
            searching = true
            delay(150)
            val context = currentCoroutineContext()
            hits = withContext(Dispatchers.Default) { searchConversation(chat, query) { context.ensureActive() } }
        }
        searching = false
    }
    Column(modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("常用", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(query,{query=it.take(200)},Modifier.fillMaxWidth().semantics {contentDescription="搜索当前聊天记录"},placeholder={Text("查找聊天记录",fontSize=12.sp)},singleLine=true,shape=RoundedCornerShape(16.dp),
            textStyle=LocalTextStyle.current.copy(fontSize=13.sp),trailingIcon=if(query.isNotEmpty()){{TextButton({query=""},contentPadding=PaddingValues(4.dp)){Text("清除",fontSize=11.sp)}}}else null)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if (query.isNotBlank()) {
                item {Text(if(searching)"正在查找…" else "最近 ${hits.size} 条匹配 · 点击定位",fontSize=11.sp,color=Muted)}
                if (!searching && hits.isEmpty()) item {Text("这段对话里没有找到。",fontSize=12.sp,color=Muted)}
                items(hits,key={it.message.id}) {hit ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha=.6f)).clickable {locate(hit.message.id)}.testTag("搜索结果-${hit.message.id}").padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(messageMeta(data,hit.message),fontSize=10.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(hit.excerpt,fontSize=12.sp,lineHeight=20.sp,color=Ink,maxLines=4,overflow=TextOverflow.Ellipsis)
                    }
                }
            } else {
                item {Text("最近附件",fontSize=13.sp,color=Ink,fontWeight=FontWeight.Medium)}
                item {Text("点击回到原消息，查看图片或播放录音。",fontSize=11.sp,lineHeight=18.sp,color=Muted)}
                if(media.isEmpty())item {Text("这段对话还没有图片或录音。",fontSize=12.sp,color=Muted)}
                items(media) {entry ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha=.6f)).clickable {locate(entry.message.id)}.testTag("附件定位-${entry.message.id}-${entry.attachment.file}").padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        val bitmap=if(entry.attachment.kind=="image")rememberMedia(LocalMediaRoot.current,entry.attachment.file)else null
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Accent.copy(alpha=.08f)),contentAlignment=Alignment.Center) {
                            if(bitmap!=null)Image(bitmap,"附件缩略图",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                            else Text(if(entry.attachment.kind=="audio")"音" else "图",fontSize=15.sp,color=Accent)
                        }
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(if(entry.attachment.kind=="audio")"录音" else "图片",fontSize=12.sp,color=Ink)
                            Text(messageMeta(data,entry.message),fontSize=10.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                        Text("›",color=Muted)
                    }
                }
                if(media.size==24)item {Text("最近 24 项 · 更早附件可在聊天中查看",fontSize=10.sp,color=Muted)}
            }
        }
        TextButton({chat.messages.lastOrNull()?.let {locate(it.id)}},Modifier.fillMaxWidth(),enabled=chat.messages.isNotEmpty()) {Text("↓  回到最新消息",fontSize=12.sp)}
    }
}

private fun messageMeta(data: DesktopData, message: Message): String {
    val name=if(message.speakerId==Message.USER)data.userName else message.speakerName.ifBlank {data.companions.find {it.id==message.speakerId}?.name?:"TA"}
    val date=DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(message.at).atZone(ZoneId.systemDefault()))
    return "$name · $date"
}

@Composable private fun NameDialog(title: String, label: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(name, { name = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton({ save(name) }, enabled = name.isNotBlank()) { Text("继续") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}

@Composable private fun GroupDialog(tas: List<DesktopTa>, dismiss: () -> Unit, save: (String, List<String>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var ids by remember { mutableStateOf(emptyList<String>()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("一起聊聊") }, text = { Column(Modifier.width(360.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("群名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("选择 2–4 位 TA，各自使用自己的模型。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            tas.forEach { ta -> Row(Modifier.fillMaxWidth().clickable { ids = if (ta.id in ids) ids - ta.id else if (ids.size < 4) ids + ta.id else ids }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ta.id in ids, null); Avatar(ta, 30); Spacer(Modifier.width(12.dp)); Text(ta.name)
            } }
        }
    } }, confirmButton = { TextButton({ save(name, ids) }, enabled = ids.size in 2..4) { Text("创建群聊") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}

@Composable private fun ModelDialog(ta: DesktopTa, hasKey: Boolean, dismiss: () -> Unit, save: (DesktopTa, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf(ta.name) }; var persona by remember { mutableStateOf(ta.persona) }
    var url by remember { mutableStateOf(ta.endpoint.baseUrl) }; var model by remember { mutableStateOf(ta.endpoint.model) }
    var key by remember { mutableStateOf("") }; var remove by remember { mutableStateOf(false) }
    var http by remember { mutableStateOf(ta.endpoint.allowHttp) }; var confirmHttp by remember { mutableStateOf(false) }
    var vision by remember {mutableStateOf(ta.endpoint.supportsImages)}
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(560.dp).heightIn(max = 760.dp), shape = RoundedCornerShape(28.dp), color = Color(0xfff8f5fa)) {
            Column(Modifier.padding(26.dp)) {
                Text("角色与模型", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text("每位 TA 都有自己的设定和模型。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("TA 的名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(persona, { persona = it }, label = { Text("角色设定") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it;vision=false }, label = { Text("接口地址") }, placeholder = { Text("https://…/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it;vision=false }, label = { Text("模型名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it; remove = false }, label = { Text(if (hasKey) "API Key（留空保留已存 Key）" else "API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Text("支持兼容 Chat Completions 的接口。Key 由 Windows 加密保存，同一接口地址共用；这里不会回显已有 Key。", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
                    Row(verticalAlignment=Alignment.CenterVertically) {Switch(vision,{vision=it},modifier=Modifier.semantics {contentDescription="模型支持看图"});Spacer(Modifier.width(10.dp));Text("这个模型支持看图",fontSize=12.sp)}
                    Text("默认关闭；请确认接口和模型支持图片。开启后，最近聊天中的用户图片会随请求发送给此接口，最多 8 张。",fontSize=11.sp,color=Muted)
                    if (hasKey) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(remove, { remove = it }); Text("移除这个地址保存的 Key", fontSize = 12.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(http, { if (it) confirmHttp = true else http = false }); Spacer(Modifier.width(10.dp)); Text("允许这个接口使用 HTTP", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(dismiss) { Text("取消") }; Spacer(Modifier.width(10.dp))
                    Button({ save(ta.copy(name = name.trim(), persona = persona, endpoint = Endpoint(url.trim(), model.trim(), http,vision)), key, remove) }, enabled = name.isNotBlank() && url.isNotBlank() && model.isNotBlank()) { Text("保存配置") }
                }
            }
        }
    }
    if (confirmHttp) AlertDialog(onDismissRequest = { confirmHttp = false }, title = { Text("允许明文连接？") }, text = { Text("HTTP 不加密，网络中的其他人可能看到聊天和 API Key。仅在你信任的本地服务或网络环境使用。") }, confirmButton = { TextButton({ http = true; confirmHttp = false }) { Text("了解，允许") } }, dismissButton = { TextButton({ confirmHttp = false }) { Text("取消") } })
}
