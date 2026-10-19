package com.cleo.cleos.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Call
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.GroupChatRules
import com.cleo.cleos.ai.GroupMemoryRules
import com.cleo.cleos.ai.GroupToolRules
import com.cleo.cleos.ai.GroupUploadRules
import com.cleo.cleos.ai.GroupVoiceRules
import com.cleo.cleos.ai.GroupVoiceDraft
import com.cleo.cleos.ai.GroupSpeechRules
import com.cleo.cleos.ai.GroupMessageDeletion
import com.cleo.cleos.data.MessageAudios
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.BubbleSpacing
import com.cleo.cleos.data.StickerBook
import com.cleo.cleos.data.StickerText
import com.cleo.cleos.data.db.GroupMessageEntity
import com.cleo.cleos.data.db.MemoryEntity
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
    val turns by remember(groupId) { dao.observeTurns(groupId) }.collectAsStateWithLifecycle(emptyList())
    val chatItems = remember(rows, turns) { groupChatItems(rows, turns) }
    val notes by remember(groupId) { dao.observeMemories(groupId) }.collectAsStateWithLifecycle(emptyList())
    val tas by c.companions.all.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(com.cleo.cleos.data.AppSettings())
    val stickers by c.stickers.all.collectAsStateWithLifecycle(emptyList())
    val stickerBook = remember(stickers) { StickerBook(stickers) }
    val working by c.groupChat.working.collectAsStateWithLifecycle()
    val live by c.groupChat.streaming.collectAsStateWithLifecycle()
    val progress by c.groupChat.progress.collectAsStateWithLifecycle()
    val generation by c.groupChat.generation.collectAsStateWithLifecycle()
    val transcribingGroups by c.groupChat.transcribing.collectAsStateWithLifecycle()
    val transcribing = groupId in transcribingGroups
    // Members who have not written a word yet are one small strip, not one empty bubble each. A row with text, voice,
    // photos, tool records or an error stays a real row; only the ids that have started streaming matter here.
    val streamingIds = live.filter { it.value.isNotEmpty() }.keys.toSet()
    val entries = remember(chatItems, streamingIds) { groupListEntries(chatItems) { GroupWaiting.isWaiting(it, streamingIds) } }
    // Like private chat, index zero is the newest item. History can arrive after the first layout.
    val newestFirst = remember(entries) { entries.asReversed() }
    val busy = groupId in working
    val scope = rememberCoroutineScope()
    val list = key(groupId, generation) { rememberLazyListState() }
    LaunchedEffect(groupId, generation) { list.scrollToItem(0) }
    var draft by rememberSaveable(groupId) { mutableStateOf("") }
    var mentions by rememberSaveable(groupId) { mutableStateOf(listOf<Long>()) }
    var loaded by remember(groupId, generation) { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    // The small tray opened by + (点名, 图片). It floats over the list, so opening it never moves the conversation.
    var trayOpen by rememberSaveable(groupId, generation) { mutableStateOf(false) }
    var drawerOpen by rememberSaveable(groupId, generation) { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }
    val chatType = remember(settings.chatTextSize) { ChatType(settings.chatTextSize) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var details by remember { mutableStateOf(false) }
    var callPicker by remember(groupId, generation) { mutableStateOf(false) }
    var callIds by rememberSaveable(groupId) { mutableStateOf(listOf<Long>()) }
    var callCreatedAt by rememberSaveable(groupId) { mutableLongStateOf(0L) }
    val context = LocalContext.current
    var editingMembers by remember { mutableStateOf(false) }
    var savingMembers by remember { mutableStateOf(false) }
    var sharingMemberId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var savingSharing by remember(groupId, generation) { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var deletion by remember(groupId, generation) { mutableStateOf<GroupMessageDeletion?>(null) }
    var deletingMessage by remember(groupId, generation) { mutableStateOf(false) }
    var toolMessageId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var memoriesOpen by rememberSaveable(groupId, generation) { mutableStateOf(false) }
    var memorySearch by rememberSaveable(groupId, generation) { mutableStateOf("") }
    var memoryEditor by rememberSaveable(groupId, generation) { mutableStateOf(false) }
    var editingMemoryId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var memorySourceId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var deletingMemoryId by rememberSaveable(groupId, generation) { mutableStateOf<Long?>(null) }
    var savingMemory by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var attaching by remember { mutableStateOf(false) }
    var pickerGroup by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickerEpoch by rememberSaveable { mutableLongStateOf(0L) }
    val draftPhotos = remember(group?.draftImages) { MessageImages.decode(group?.draftImages) }
    val draftVoice = remember(group?.draftAudio, groupId) { GroupVoiceRules.draft(group?.draftAudio, groupId) }
    var voiceText by rememberSaveable(groupId, generation, draftVoice?.file) { mutableStateOf(draftVoice?.text.orEmpty()) }
    var voiceEdited by rememberSaveable(groupId, generation, draftVoice?.file) { mutableStateOf(false) }
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
    val playback = rememberGroupPlayback(groupId, generation) { problem = it }
    fun prepareDeletion(row: GroupMessageEntity, wholeRound: Boolean) {
        val createdAt = group?.createdAt ?: return
        act { deletion = c.groupChat.previewMessageDeletion(groupId, row.id, wholeRound, generation, createdAt) }
    }
    val latestReplyTurn = rows.mapNotNull { it.turnId }.maxOrNull()
    val latestUserId = rows.lastOrNull { it.role == "user" }?.id ?: Long.MAX_VALUE
    fun beginCall(ids: List<Long>, createdAt: Long) {
        playback.stop()
        if (!c.groupCalls.start(groupId, ids, createdAt)) problem = "现在有一通电话还没结束，请先挂断"
    }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && callIds.size == 2) beginCall(callIds, callCreatedAt)
        else problem = "需要话筒权限才能一起通话"
        callIds = emptyList()
    }
    val voiceEnabled = group != null && loaded && !posting && !attaching && draftVoice == null && draftPhotos.isEmpty() && draft.isBlank()
    val recording = rememberGroupRecording(groupId, generation, voiceEnabled,
        settings.voiceBaseUrl.isNotBlank() && settings.voiceModel.isNotBlank(), playback.stop,
        onClip = { clip -> c.appScope.launch {
            try { c.groupChat.stageVoice(groupId, clip, generation) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem = e.message ?: "这段语音没保存，请重新录制" }
        } }, onHint = { problem = it })
    BackHandler(enabled = trayOpen || drawerOpen) { if (drawerOpen) drawerOpen = false else trayOpen = false }
    LaunchedEffect(recording.recording) { if (recording.recording) { trayOpen = false; drawerOpen = false } }
    LaunchedEffect(draftVoice?.file, draftVoice?.text) { if (!voiceEdited) voiceText = draftVoice?.text.orEmpty() }
    LaunchedEffect(draftVoice?.file, voiceText, voiceEdited, transcribing, generation) {
        val file = draftVoice?.file
        if (file != null && voiceEdited && !transcribing) {
            delay(250)
            c.groupChat.editVoice(groupId, file, voiceText, generation)
        }
    }
    val latestVoiceEdit by rememberUpdatedState(Triple(draftVoice?.file, voiceText, voiceEdited && !transcribing))
    DisposableEffect(groupId, generation) {
        val epoch = generation
        onDispose {
            val edit = latestVoiceEdit
            if (edit.first != null && edit.third) c.appScope.launch { c.groupChat.editVoice(groupId, requireNotNull(edit.first), edit.second, epoch) }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(GroupUploadRules.MAX_IMAGES)) { uris ->
        val target = pickerGroup
        val epoch = pickerEpoch
        pickerGroup = null
        if (uris.isNotEmpty() && target != null) act {
            require(target == groupId) { "群聊已切换，请在当前群重新选择图片" }
            attaching = true
            try { c.groupChat.attachImages(target, uris, epoch) } finally { attaching = false }
        }
    }
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
    var newestBefore by remember(groupId, generation) { mutableStateOf<String?>(null) }
    LaunchedEffect(groupId, generation, entries.size, rows.size, rows.lastOrNull()?.id, rows.lastOrNull()?.images, rows.lastOrNull()?.audio,
        rows.lastOrNull()?.toolTrace, rows.lastOrNull()?.status, live.values.sumOf { it.length }) {
        // Several items can arrive together. Judge proximity by the previous newest item,
        // so a batch of replies does not turn a reader at the bottom into a reader of history.
        val previous = newestFirst.indexOfFirst { it.key == newestBefore }.coerceAtLeast(0)
        val follow = newestBefore == null || !list.isScrollInProgress && list.firstVisibleItemIndex <= previous + 2
        newestBefore = newestFirst.firstOrNull()?.key
        if (newestFirst.isNotEmpty() && follow) {
            withFrameNanos { } // Let the voice bubble and tool controls finish changing the row's height.
            list.scrollToItem(0)
        }
    }
    val messageMenu: @Composable (GroupMessageEntity, Boolean, () -> Unit) -> Unit = { row, expanded, dismiss ->
        GroupMessageActionsMenu(row.copy(content = live[row.id] ?: row.content), expanded, !busy && GroupMemoryRules.canRemember(row),
            !busy && !transcribing && !recording.recording && !posting && !attaching && !deletingMessage, dismiss,
            onSelect = { selectedText = it }, onRemember = {
                problem = null
                editingMemoryId = notes.firstOrNull { it.sourceMessageId == row.id }?.id
                memorySourceId = row.id; memoriesOpen = true; memoryEditor = true
            }, onDeleteSingle = { prepareDeletion(row, false) }, onDeleteRound = { prepareDeletion(row, true) })
    }
    GlassPage(overlay = { page ->
        GlassTopBar(group?.title ?: "群聊", page, subtitle = "${participating.size} 位 TA · 一起聊天",
            leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
            trailing = { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassIconButton(Icons.Rounded.Call, "群语音通话", { problem = null; callPicker = true }, page,
                    enabled = loaded && !busy && !recording.recording && !posting && !attaching && !transcribing && availableMembers.size >= 2)
                GlassIconButton(Icons.Rounded.Book, "群内小本本", { problem = null; memoriesOpen = true }, page)
                GlassIconButton(Icons.Rounded.Groups, "群聊成员", { details = true }, page)
            } },
            onTitleClick = { details = true })
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding()
            .padding(vertical = 8.dp).onSizeChanged { composerHeight = it.height },
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (problem != null || mentions.isNotEmpty() || draftPhotos.isNotEmpty() || recording.recording || draftVoice != null) {
                Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                    if (mentions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        mentions.forEach { id -> Chip("@${members.firstOrNull { it.companionId == id }?.name ?: "TA"} ×", false) { mentions = mentions - id } }
                    }
                    if (draftPhotos.isNotEmpty()) GroupDraftPhotos(draftPhotos, !attaching && !posting,
                        onRemove = { file -> act { c.groupChat.removeDraftImage(groupId, file, generation) } }, onOpen = onOpenImage)
                    if (recording.recording) GroupRecordingCard(recording)
                    draftVoice?.let { voice -> GroupVoiceDraftCard(voice, voiceText, transcribing, !posting, playback,
                        onText = { voiceText = it.take(GroupChatRules.MAX_TEXT); voiceEdited = true },
                        onRetry = { voiceEdited = false; act {
                            c.groupChat.editVoice(groupId, voice.file, voiceText, generation)
                            c.groupChat.retryVoice(groupId, voice.file, generation)
                        } },
                        onRemove = { playback.stop(); act { c.groupChat.removeVoice(groupId, voice.file, generation) } }) }
                }
            }
            val canSend = (draft.isNotBlank() || draftPhotos.isNotEmpty() || draftVoice != null && voiceText.isNotBlank()) &&
                !posting && !attaching && !transcribing && !recording.recording && loaded
            // Voice is only possible with nothing else drafted, so while there is something the button makes way for the text.
            val showMic = recording.recording || (draft.isBlank() && draftVoice == null && draftPhotos.isEmpty())
            val action = when {
                showMic && (!busy || recording.recording) -> ChatComposerAction.Mic
                busy -> ChatComposerAction.Stop
                else -> ChatComposerAction.Send
            }
            ChatComposer(page, chatType, draft, { draft = it.take(GroupChatRules.MAX_TEXT) }, inputFocus, action,
                onAction = {
                    if (busy) act { c.groupChat.stop(groupId) }
                    else if (canSend) act {
                        posting = true
                        try {
                            c.groupChat.send(groupId, draft, mentions, voiceText.takeIf { draftVoice != null }, draftVoice?.file)
                            draft = ""; mentions = emptyList(); trayOpen = false
                            list.scrollToItem(0)
                        } finally { posting = false }
                    }
                },
                leadingIcon = if (trayOpen) Icons.Rounded.Close else Icons.Rounded.Add,
                leadingLabel = if (trayOpen) "收起更多操作" else "更多操作：点名、发图片", leadingExpanded = trayOpen,
                onLeading = { trayOpen = !trayOpen; drawerOpen = false },
                fieldLabel = "群聊消息", fieldEnabled = !recording.recording && draftVoice == null,
                placeholder = if (draftVoice != null) "待发送…" else "说点什么…",
                onFieldFocus = { drawerOpen = false },
                drawerOpen = drawerOpen, drawerEnabled = loaded && !posting && !recording.recording && !attaching,
                drawerLabel = if (drawerOpen) "收起群聊表情包" else "群聊表情包",
                onDrawer = {
                    drawerOpen = !drawerOpen; trayOpen = false
                    if (drawerOpen) { keyboard?.hide(); focusManager.clearFocus() }
                },
                actionEnabled = when (action) {
                    ChatComposerAction.Mic -> voiceEnabled || recording.recording
                    ChatComposerAction.Stop -> true
                    ChatComposerAction.Send -> canSend
                },
                actionLabel = when (action) {
                    ChatComposerAction.Mic -> "按住发群语音"
                    ChatComposerAction.Stop -> "停止群聊回复"
                    ChatComposerAction.Send -> "发送群聊消息"
                },
                recording = recording.recording, onVoiceStart = recording.start, onVoiceMove = recording.move, onVoiceEnd = recording.end) {
                if (drawerOpen) key(groupId, generation) {
                    GroupStickerDrawer(stickers, loaded && !busy && !posting && !attaching && !transcribing && !recording.recording,
                        onSend = { sticker -> act {
                            posting = true
                            try { c.groupChat.sendSticker(groupId, sticker.id, mentions, generation) }
                            finally { posting = false }
                        } }, onProblem = { problem = it })
                }
            }
        }

        AnimatedVisibility(trayOpen, Modifier.align(Alignment.BottomStart).navigationBarsPadding().imePadding()
            .padding(start = 14.dp, bottom = 8.dp + bottom + 7.dp),
            enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = .82f, stiffness = Spring.StiffnessMediumLow),
                initialScale = .9f, transformOrigin = TransformOrigin(0f, 1f)),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = .94f, transformOrigin = TransformOrigin(0f, 1f))) {
            GroupComposerTray(page, mentions.size, draftPhotos.size, GroupUploadRules.MAX_IMAGES, photoEnabled = loaded && !posting &&
                !attaching && !recording.recording && draftVoice == null && draftPhotos.size < GroupUploadRules.MAX_IMAGES,
                onMention = { trayOpen = false; picking = true },
                onPhoto = {
                    trayOpen = false
                    pickerGroup = groupId; pickerEpoch = generation
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
        }
    }) {
        if (group == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("正在读取群聊，或这个群已被删除", color = palette.contentSecondary)
        } else CompositionLocalProvider(LocalBubblePadding provides (settings.bubblePaddingX to settings.bubblePaddingY),
            LocalBubbleDecoration provides settings.bubbleDecoration, LocalMessageSpacing provides settings.messageSpacing,
            LocalStickers provides stickerBook) {
            LazyColumn(Modifier.fillMaxSize().imePadding(), state = list, reverseLayout = true,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = top + 12.dp, bottom = bottom + 28.dp),
                verticalArrangement = Arrangement.spacedBy(BubbleSpacing.normalized(settings.messageSpacing).dp, Alignment.Bottom)) {
                if (rows.isEmpty()) item {
                    GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
                        Text("大家都在这里了。说句话，或点 + 选择要点名的伙伴。", color = palette.contentSecondary, fontSize = 14.sp)
                    }
                }
                itemsIndexed(newestFirst, key = { _, entry -> "$groupId:$generation:${entry.key}" }) { index, entry ->
                    if (entry is GroupListEntry.Waiting) {
                        Box(Modifier.fillMaxWidth()) { GroupWaitingStrip(entry.rows, progress) }
                        return@itemsIndexed
                    }
                    val item = (entry as GroupListEntry.Chat).item
                    if (item is GroupChatItem.CallRound) {
                        GroupCallCard(item, generation, settings.userAvatar,
                            onExpanded = { scope.launch { list.animateScrollToItem(index) } },
                            messageMenu = messageMenu)
                        return@itemsIndexed
                    }
                    val row = (item as GroupChatItem.Message).message
                    var menuOpen by rememberSaveable(row.id) { mutableStateOf(false) }
                    val mine = row.role == "user"
                    val text = live[row.id] ?: row.content
                    val audio = remember(row.audio, groupId, row.speakerId, row.role) { GroupVoiceRules.messageAudio(row) }
                    val speech = remember(row.audio, groupId, row.speakerId) { GroupSpeechRules.clip(row) }
                    val written = speech?.let { GroupSpeechRules.remaining(text, it.text) } ?: text
                    val waiting = GroupWaiting.stageText(progress[row.id]?.stage)
                    val ahead = rows.firstOrNull { it.status == "running" && it.turnId == row.turnId }?.speakerName
                    // The author is a header above the avatar/bubble row, not an extra line inside it.
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        Column(Modifier.padding(start = if (!mine) 42.dp else 0.dp, end = if (mine && settings.chatAvatars) 42.dp else 0.dp,
                            bottom = 4.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                            Text(row.speakerName.ifBlank { if (mine) "我" else "TA" }, color = palette.contentSecondary,
                                style = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)))
                            if (mine && GroupChatRules.ids(row.mentions).isNotEmpty()) Text(GroupChatRules.ids(row.mentions)
                                .joinToString(" ") { "@${members.firstOrNull { m -> m.companionId == it }?.name ?: "TA"}" }, color = palette.contentSecondary, fontSize = 11.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                            verticalAlignment = Alignment.Top) {
                            if (!mine) { Avatar(row.speakerAvatar, row.speakerEmoji ?: avatarLetter(row.speakerName, "TA"), 34.dp); Spacer(Modifier.width(8.dp)) }
                            Box(Modifier.widthIn(max = bubbleWidth)) {
                            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val photos = remember(row.images) { MessageImages.decode(row.images) }
                                if (audio != null) VoiceBubble(audio, mine, playback.file == audio.file, speech?.text ?: row.content, false, settings.expandVoiceText, row.id,
                                    onClick = { playback.play(audio.file) }, onLongClick = { menuOpen = true })
                                if ((audio == null || !mine) && (written.isNotBlank() || audio == null && (photos.isEmpty() || row.status in setOf("pending", "running")))) {
                                    val body = written.ifEmpty { when (row.status) {
                                        "pending" -> ahead?.let { "等${it}接话后轮到我…" } ?: "等会儿接话…"
                                        "running" -> waiting
                                        else -> "这次没有回复"
                                    } }
                                    val pieces = remember(body, stickerBook, row.status) { StickerText.split(
                                        (if (row.status == "running") StickerText.finishedPart(body) else body).let {
                                            if (mine) it else StickerText.normalizeModelTokens(it, stickerBook)
                                        }, stickerBook) }
                                    pieces.forEach { piece -> when (piece) {
                                        is StickerText.Piece.Sticker -> StickerView(piece.sticker) { menuOpen = true }
                                        is StickerText.Piece.Unavailable -> UnavailableSticker(piece.name) { menuOpen = true }
                                        is StickerText.Piece.Words -> ChatBubbleSurface(mine = mine,
                                            theme = if (mine) settings.myBubbleTheme else settings.taBubbleThemes[row.speakerId.toString()] ?: "glass",
                                            background = settings.bubbleBackgrounds[if (mine) settings.myBubbleTheme else settings.taBubbleThemes[row.speakerId.toString()] ?: "glass"],
                                            modifier = Modifier.widthIn(max = bubbleWidth).combinedClickable(onClick = {}, onLongClick = { menuOpen = true })) { ink ->
                                            MessageText(piece.text, ink, TextStyle(fontSize = 16.sp, lineHeight = 23.sp))
                                        }
                                    } }
                                }
                                photos.forEach { photo -> GroupPhotoCard(photo, bubbleWidth, onOpenImage) { menuOpen = true } }
                                if (row.status == "running" && text.isNotEmpty()) Text(waiting, color = palette.contentSecondary, fontSize = 11.sp)
                                val toolTrace = remember(row.toolTrace) { GroupToolRules.decode(row.toolTrace) }
                                if (toolTrace.records.isNotEmpty()) TextButton({ toolMessageId = row.id }) {
                                    Text("工具记录 · ${toolTrace.records.size}", color = palette.accentContent, fontSize = 12.sp)
                                }
                                if (toolTrace.notice.isNotEmpty()) Text(toolTrace.notice, color = palette.contentSecondary, fontSize = 12.sp)
                                row.error?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                                if (!busy && row.speakerId in availableMembers && GroupChatRules.retryable(row) && row.turnId == latestReplyTurn && row.id > latestUserId) {
                                    TextButton({ act { c.groupChat.retry(row.id) } }) { Text("请${row.speakerName}再试一次", color = palette.accentContent, fontSize = 12.sp) }
                                }
                            }
                            messageMenu(row, menuOpen) { menuOpen = false }
                            }
                            if (mine && settings.chatAvatars) { Spacer(Modifier.width(8.dp)); Avatar(settings.userAvatar, avatarLetter(settings.userName, "我"), 34.dp) }
                        }
                    }
                }
            }
        }
    }
    group?.let { saved ->
        if (picking || details) GroupMembersDialog(saved, members, active, if (picking) mentions else null,
            if (picking) { value -> mentions = value; picking = false } else null,
            { picking = false; details = false }, { name, allowed -> act { c.groupChat.saveOptions(groupId, name, allowed, generation); details = false } },
            canManage = !busy && members.isNotEmpty() && active.isNotEmpty(),
            onManage = { problem = null; details = false; editingMembers = true },
            onMemorySharing = { member -> problem = null; details = false; sharingMemberId = member.companionId })
        sharingMemberId?.let { target ->
            val member = members.firstOrNull { it.companionId == target && it.active && target in active }
            if (member == null) {
                LaunchedEffect(target) { sharingMemberId = null; problem = "这位 TA 已不在本群" }
            } else key(groupId, generation, target) {
                val privateNotes by produceState<List<MemoryEntity>?>(null, target) {
                    c.db.memories().observeFor(target).collect { value = it }
                }
                GroupMemorySharingDialog(saved, member, privateNotes, savingSharing || busy, problem,
                    memoryEnabled = com.cleo.cleos.ai.ToolGroup.Memory in settings.tools,
                    onDismiss = { if (!savingSharing) { sharingMemberId = null; problem = null; details = true } },
                    onSave = { enabled, grants -> act {
                        savingSharing = true
                        try {
                            c.groupChat.saveMemorySharing(groupId, target, enabled, grants, generation)
                            sharingMemberId = null; details = true
                        } finally { savingSharing = false }
                    } })
            }
        }
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
    deletion?.let { approved -> GroupMessageDeleteDialog(approved, deletingMessage, !busy && !transcribing,
        problem, onDismiss = { if (!deletingMessage) { deletion = null; problem = null } }, onDelete = { act {
            deletingMessage = true
            try {
                playback.stop()
                c.groupChat.deleteMessages(approved)
                deletion = null
                toolMessageId = null
                selectedText = null
            } finally { deletingMessage = false }
        } }) }
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
    if (callPicker) GroupCallDialog(participating.filter { it.companionId in availableMembers },
        onDismiss = { callPicker = false }, onStart = { ids ->
            callPicker = false
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                callIds = ids; callCreatedAt = group?.createdAt ?: 0L; callPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else beginCall(ids, group?.createdAt ?: 0L)
        })
}
