package com.cleo.cleos.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.GroupChatRules
import com.cleo.cleos.ai.GroupMemoryRules
import com.cleo.cleos.ai.GroupToolRules
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.BubbleSpacing
import com.cleo.cleos.data.db.GroupMessageEntity
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.*
import com.cleo.cleos.ui.settings.Chip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun GroupChatScreen(groupId: Long, onBack: () -> Unit, onOpenImage: (String) -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val dao = c.db.groups()
    val group by remember(groupId) { dao.observeGroup(groupId) }.collectAsStateWithLifecycle(null)
    val members by remember(groupId) { dao.observeMembers(groupId) }.collectAsStateWithLifecycle(emptyList())
    val rows by remember(groupId) { dao.observeMessages(groupId) }.collectAsStateWithLifecycle(emptyList())
    val notes by remember(groupId) { dao.observeMemories(groupId) }.collectAsStateWithLifecycle(emptyList())
    val tas by c.companions.all.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(com.cleo.cleos.data.AppSettings())
    val working by c.groupChat.working.collectAsStateWithLifecycle()
    val live by c.groupChat.streaming.collectAsStateWithLifecycle()
    val progress by c.groupChat.progress.collectAsStateWithLifecycle()
    val generation by c.groupChat.generation.collectAsStateWithLifecycle()
    val busy = groupId in working
    var tickNanos by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(busy) {
        while (busy) { tickNanos = System.nanoTime(); delay(1000) }
    }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var draft by rememberSaveable(groupId) { mutableStateOf("") }
    var mentions by rememberSaveable(groupId) { mutableStateOf(listOf<Long>()) }
    var loaded by remember(groupId, generation) { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var editingMembers by remember { mutableStateOf(false) }
    var savingMembers by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var actionMessageId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var toolMessageId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var memoriesOpen by rememberSaveable(groupId, generation) { mutableStateOf(false) }
    var memorySearch by rememberSaveable(groupId, generation) { mutableStateOf("") }
    var memoryEditor by rememberSaveable(groupId, generation) { mutableStateOf(false) }
    var editingMemoryId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var memorySourceId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var deletingMemoryId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var savingMemory by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var composerHeight by remember { mutableIntStateOf(120) }
    val density = LocalDensity.current
    val bubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.7f).dp
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight
    val bottom = with(density) { composerHeight.toDp() }
    val active = tas.map { it.id }.toSet()
    val participating = members.filter { it.active }
    val availableMembers = participating.filter { it.companionId in active }.map { it.companionId }.toSet()
    LaunchedEffect(groupId) { c.settings.setCurrentGroup(groupId) }
    fun act(block: suspend () -> Unit) { scope.launch {
        problem = null
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { problem = e.message ?: "没有完成，请再试一次" }
    } }
    LaunchedEffect(group?.id, generation) {
        if (group != null) {
            val saved = dao.group(groupId)
            draft = saved?.draft.orEmpty(); mentions = GroupChatRules.ids(saved?.mentions ?: "[]"); loaded = true
        }
    }
    LaunchedEffect(draft, mentions, loaded, generation) {
        if (loaded) { delay(250); c.groupChat.saveDraft(groupId, draft, mentions, generation) }
    }
    LaunchedEffect(members, active, loaded) {
        if (loaded && members.isNotEmpty() && active.isNotEmpty()) mentions = mentions.filter { it in availableMembers }
    }
    val latestDraft by rememberUpdatedState(Triple(draft, mentions, loaded))
    DisposableEffect(groupId, generation) {
        val epoch = generation
        onDispose {
            val snapshot = latestDraft
            if (snapshot.third) c.appScope.launch { c.groupChat.saveDraft(groupId, snapshot.first, snapshot.second, epoch) }
        }
    }
    LaunchedEffect(rows.size, rows.lastOrNull()?.images, live.values.sumOf { it.length }) {
        val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (rows.isNotEmpty() && last >= (list.layoutInfo.totalItemsCount - 3).coerceAtLeast(0)) list.scrollToItem(rows.lastIndex)
    }
    GlassPage(overlay = { page ->
        GlassTopBar(group?.title ?: "群聊", page, subtitle = "${participating.size} 位 TA · 一起聊天",
            leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
            trailing = { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassIconButton(Icons.Rounded.Book, "群内小本本", { problem = null; memoriesOpen = true }, page)
                GlassIconButton(Icons.Rounded.Groups, "群聊成员", { details = true }, page)
            } },
            onTitleClick = { details = true })
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding()
            .padding(horizontal = 14.dp, vertical = 8.dp).onSizeChanged { composerHeight = it.height },
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
            if (mentions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                mentions.forEach { id -> Chip("@${members.firstOrNull { it.companionId == id }?.name ?: "TA"} ×", false) { mentions = mentions - id } }
            }
            GlassSurface(Modifier.fillMaxWidth(), backdrop = page, style = palette.card, shape = GlassShape.Rounded(26.dp),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(Icons.Rounded.AlternateEmail, "点名成员", { picking = true }, page)
                    BasicTextField(draft, { draft = it.take(GroupChatRules.MAX_TEXT) }, modifier = Modifier.weight(1f)
                        .heightIn(min = 42.dp, max = 130.dp).semantics { contentDescription = "群聊消息" },
                        textStyle = TextStyle(color = palette.content, fontSize = 16.sp), cursorBrush = SolidColor(palette.content),
                        decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
                            if (draft.isEmpty()) Text("说点什么…", color = palette.contentSecondary, fontSize = 16.sp)
                            inner()
                        } })
                    if (busy) GlassIconButton(Icons.Rounded.Stop, "停止群聊回复", { act { c.groupChat.stop(groupId) } }, page)
                    else GlassIconButton(Icons.AutoMirrored.Rounded.Send, "发送群聊消息", {
                        if (draft.isNotBlank() && !posting && loaded) act {
                            posting = true
                            try {
                                c.groupChat.send(groupId, draft, mentions)
                                draft = ""; mentions = emptyList()
                                if (rows.isNotEmpty()) list.scrollToItem(rows.lastIndex)
                            } finally { posting = false }
                        }
                    }, page)
                }
            }
        }
    }) {
        if (group == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在读取群聊，或这个群已被删除", color = palette.contentSecondary)
        } else CompositionLocalProvider(LocalBubblePadding provides (settings.bubblePaddingX to settings.bubblePaddingY),
            LocalBubbleDecoration provides settings.bubbleDecoration, LocalMessageSpacing provides settings.messageSpacing) {
            LazyColumn(Modifier.fillMaxSize().imePadding(), state = list,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = top + 12.dp, bottom = bottom + 28.dp),
                verticalArrangement = Arrangement.spacedBy(BubbleSpacing.normalized(settings.messageSpacing).dp)) {
                if (rows.isEmpty()) item {
                    GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
                        Text("大家都在这里了。说句话，或者点 @ 请一位伙伴接话。", color = palette.contentSecondary, fontSize = 14.sp)
                    }
                }
                items(rows, key = { it.id }) { row ->
                    val mine = row.role == "user"
                    val text = live[row.id] ?: row.content
                    val waiting = progress[row.id]?.label(tickNanos) ?: "正在连接…"
                    val ahead = rows.firstOrNull { it.status == "running" && it.turnId == row.turnId }?.speakerName
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Top) {
                        if (!mine) { Avatar(row.speakerAvatar, row.speakerEmoji ?: avatarLetter(row.speakerName, "TA"), 34.dp); Spacer(Modifier.width(8.dp)) }
                        Column(Modifier.widthIn(max = bubbleWidth), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                            Text(row.speakerName.ifBlank { if (mine) "我" else "TA" }, color = palette.contentSecondary, fontSize = 11.sp)
                            if (mine && GroupChatRules.ids(row.mentions).isNotEmpty()) Text(GroupChatRules.ids(row.mentions)
                                .joinToString(" ") { "@${members.firstOrNull { m -> m.companionId == it }?.name ?: "TA"}" }, color = palette.contentSecondary, fontSize = 11.sp)
                            val photos = remember(row.images) { MessageImages.decode(row.images) }
                            if (text.isNotBlank() || photos.isEmpty() || row.status in setOf("pending", "running")) ChatBubbleSurface(mine = mine, theme = if (mine) settings.myBubbleTheme else settings.taBubbleThemes[row.speakerId.toString()] ?: "glass",
                                background = settings.bubbleBackgrounds[if (mine) settings.myBubbleTheme else settings.taBubbleThemes[row.speakerId.toString()] ?: "glass"],
                                modifier = Modifier.widthIn(max = bubbleWidth).combinedClickable(onClick = {}, onLongClick = {
                                    if (text.isNotBlank()) {
                                        if (GroupMemoryRules.canRemember(row)) actionMessageId = row.id else selectedText = text
                                    }
                                })) { ink ->
                                MessageText(text.ifEmpty { when (row.status) {
                                    "pending" -> ahead?.let { "等${it}接话后轮到我…" } ?: "等会儿接话…"
                                    "running" -> waiting
                                    else -> "这次没有回复"
                                } }, ink,
                                    TextStyle(fontSize = 16.sp, lineHeight = 23.sp))
                            }
                            photos.forEach { photo -> GroupPhotoCard(photo, bubbleWidth, onOpenImage) }
                            if (row.status == "running" && text.isNotEmpty()) Text(waiting, color = palette.contentSecondary, fontSize = 11.sp)
                            val toolTrace = remember(row.toolTrace) { GroupToolRules.decode(row.toolTrace) }
                            if (toolTrace.records.isNotEmpty()) TextButton({ toolMessageId = row.id }) {
                                Text("工具记录 · ${toolTrace.records.size}", color = palette.accentContent, fontSize = 12.sp)
                            }
                            if (toolTrace.notice.isNotEmpty()) Text(toolTrace.notice, color = palette.contentSecondary, fontSize = 12.sp)
                            row.error?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                            if (!busy && row.speakerId in availableMembers && GroupChatRules.retryable(row) && row.turnId == rows.mapNotNull { it.turnId }.maxOrNull()) {
                                TextButton({ act { c.groupChat.retry(row.id) } }) { Text("请${row.speakerName}再试一次", color = palette.accentContent, fontSize = 12.sp) }
                            }
                        }
                        if (mine && settings.chatAvatars) { Spacer(Modifier.width(8.dp)); Avatar(settings.userAvatar, avatarLetter(settings.userName, "我"), 34.dp) }
                    }
                }
            }
        }
    }
    group?.let { saved ->
        if (picking || details) GroupMembersDialog(saved, members, active, if (picking) mentions else null,
            if (picking) { value -> mentions = value; picking = false } else null,
            { picking = false; details = false }, { name -> act { c.groupChat.rename(groupId, name); details = false } },
            canManage = !busy && members.isNotEmpty() && active.isNotEmpty(),
            onManage = { problem = null; details = false; editingMembers = true })
        if (editingMembers) EditGroupMembersDialog(saved, members, tas, savingMembers || busy, problem,
            onDismiss = { if (!savingMembers) { editingMembers = false; problem = null } },
            onSave = { value -> act {
                savingMembers = true
                try {
                    c.groupChat.updateMembers(groupId, value)
                    mentions = mentions.filter { it in value }
                    editingMembers = false
                } finally { savingMembers = false }
            } })
    }
    actionMessageId?.let { id -> rows.firstOrNull { it.id == id }?.let { row ->
        GroupMessageActionsDialog(row, !busy, { actionMessageId = null },
            onSelect = { actionMessageId = null; selectedText = row.content },
            onRemember = {
                actionMessageId = null; problem = null
                editingMemoryId = notes.firstOrNull { it.sourceMessageId == row.id }?.id
                memorySourceId = row.id; memoriesOpen = true; memoryEditor = true
            })
    } }
    toolMessageId?.let { id -> rows.firstOrNull { it.id == id }?.let { row ->
        GroupToolsDialog(row, { toolMessageId = null })
    } }
    if (memoriesOpen && !memoryEditor && deletingMemoryId == null) GroupMemoriesDialog(notes, rows.associateBy { it.id }, busy,
        problem, memorySearch, { memorySearch = it }, { memoriesOpen = false; problem = null },
        onNew = { problem = null; editingMemoryId = null; memorySourceId = null; memoryEditor = true },
        onEdit = { note -> problem = null; editingMemoryId = note.id; memorySourceId = note.sourceMessageId; memoryEditor = true },
        onDelete = { note -> problem = null; deletingMemoryId = note.id },
        onSource = { row -> selectedText = "${row.speakerName.ifBlank { if (row.role == "user") "我" else "TA" }}的原消息\n\n${row.content}" })
    if (memoryEditor) EditGroupMemoryDialog(notes.firstOrNull { it.id == editingMemoryId }, rows.firstOrNull { it.id == memorySourceId },
        savingMemory || busy, problem,
        onDismiss = { if (!savingMemory) { memoryEditor = false; problem = null } },
        onSave = { title, content -> act {
            savingMemory = true
            try {
                c.groupChat.saveMemory(groupId, editingMemoryId, title, content, memorySourceId, generation)
                memoryEditor = false
            } finally { savingMemory = false }
        } })
    deletingMemoryId?.let { id -> notes.firstOrNull { it.id == id }?.let { note ->
        DeleteGroupMemoryDialog(note, savingMemory || busy, problem,
            onDismiss = { if (!savingMemory) { deletingMemoryId = null; problem = null } },
            onDelete = { act {
                savingMemory = true
                try { c.groupChat.deleteMemory(groupId, id, generation); deletingMemoryId = null }
                finally { savingMemory = false }
            } })
    } }
    selectedText?.let { SelectMessageTextDialog(it, TextStyle(fontSize = 16.sp, lineHeight = 23.sp)) { selectedText = null } }
}
