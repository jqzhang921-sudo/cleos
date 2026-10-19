package com.cleo.cleos.ui.chat

import com.cleo.cleos.data.FavoriteContent
import androidx.compose.material3.Checkbox
import android.Manifest
import android.os.SystemClock
import android.content.ClipData
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.DynamicFeed
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import com.cleo.cleos.data.MessageEdits
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleo.cleos.ai.ChatRepository
import com.cleo.cleos.ai.LyricLine
import com.cleo.cleos.ai.MusicAction
import com.cleo.cleos.ai.NowPlaying
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.ui.LocalPageShown
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.CallRecords
import com.cleo.cleos.ai.McpAsk
import com.cleo.cleos.ai.Prompt
import com.cleo.cleos.ai.Recap
import com.cleo.cleos.ai.SecretRequest
import com.cleo.cleos.ai.SecretRequests
import com.cleo.cleos.ai.Speech
import com.cleo.cleos.ai.SpeechException
import com.cleo.cleos.ai.StreamingReply
import com.cleo.cleos.ai.Voice
import com.cleo.cleos.data.MessageAudio
import com.cleo.cleos.data.MessageAudios
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.MessageQuote
import com.cleo.cleos.data.MessageQuotes
import com.cleo.cleos.data.FeedShare
import com.cleo.cleos.data.FeedShares
import com.cleo.cleos.data.MessageReactions
import com.cleo.cleos.data.Pats
import com.cleo.cleos.data.StickerBook
import com.cleo.cleos.data.StickerText
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.db.StickerEntity
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassButton
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.liquidGlass
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.appViewModel
import com.cleo.cleos.ui.common.avatarLetter
import com.cleo.cleos.ui.common.fadeUnderTopBar
import com.cleo.cleos.ui.settings.SettingsPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

private data class Face(val file: String?, val letter: String)

/** Whose picture goes beside which bubbles; null when the chat shows no avatars. */
private data class Faces(val me: Face, val ai: Face)

private val LocalFaces = compositionLocalOf<Faces?> { null }

/** The chat's text: its size from settings, and the line height that goes with it. */
private val LocalChatType = compositionLocalOf { ChatType(ChatType.DEFAULT) }
private val AvatarSize = 34.dp
private val AvatarGap = 8.dp

/** What a row gives up on the avatar's side, so lines without one still line up. */
private val AvatarSlot = AvatarSize + AvatarGap

private val MaxPictureHeight = 260.dp

/** The input bar's height on one line; its round ends have half this as radius. */

/** Pictures in one message: as many as are sent along with a request. */
private const val MAX_ATTACHMENTS = Prompt.MAX_IMAGES

@Composable
fun ChatTab(
    bottomInset: Dp,
    onOpenSettings: () -> Unit,
    onOpenSettingsPage: (SettingsPage) -> Unit,
    onOpenConversations: () -> Unit,
    onOpenFeed: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    val vm = appViewModel { ChatViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val c = appContainer()
    // Whether this is the tab on screen: the page stays composed while another tab shows (MainScreen).
    val pageShown = LocalPageShown.current
    // This chat on screen: what a TA says on its own here needs no notification (see Later). Off
    // again when the app goes to the back, another tab or screen comes up, or another conversation
    // is opened. A notification already up stays: it goes when the person swipes it away or taps it,
    // not because the chat was opened (they asked to keep them).
    LifecycleResumeEffect(state.conversationId, pageShown) {
        // Null until the conversation has loaded.
        val shown = state.conversationId?.takeIf { pageShown }
        if (shown != null) c.chatOnScreen = shown
        onPauseOrDispose { if (shown != null && c.chatOnScreen == shown) c.chatOnScreen = null }
    }
    val companions by remember { c.companions.all }.collectAsStateWithLifecycle(emptyList())
    // 一起听歌: what the phone plays, followed only while this screen is up and the switch is on.
    val appSettings by remember { c.settings.settings }.collectAsStateWithLifecycle(AppSettings())
    val listeningOn = ToolGroup.Music in appSettings.tools
    val nowPlaying by remember(listeningOn, pageShown) {
        if (listeningOn && pageShown) c.music.watch() else flowOf(null)
    }.collectAsStateWithLifecycle(null)
    // Up while a song plays, and a while after it is paused; the last song stays drawn as the bar goes.
    var barShown by remember { mutableStateOf(false) }
    var barSong by remember { mutableStateOf<NowPlaying?>(null) }
    LaunchedEffect(nowPlaying) {
        val np = nowPlaying
        if (np != null) barSong = np
        val left = when {
            np == null -> 0L
            np.playing -> Long.MAX_VALUE
            else -> PAUSED_SHOWN_MS - (SystemClock.elapsedRealtime() - np.at)
        }
        barShown = left > 0
        if (left in 1 until Long.MAX_VALUE) {
            delay(left)
            barShown = false
        }
    }
    // The player's own lyrics when it gives them (they can come a moment after the song does); LRCLIB's otherwise.
    val fetched by produceState<List<LyricLine>?>(null, barSong?.song, barShown) {
        value = null
        val np = barSong
        if (np != null && barShown && np.words == null) value = c.lyrics.of(np.title, np.artist, np.durationMs)
    }
    val words = barSong?.words ?: fetched
    var switching by remember { mutableStateOf(false) }
    var readingRecap by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Voice messages: the recording under way, what the indicator shows, and the one playing.
    val recorder = remember { VoiceRecorder(c.images.dir) }
    var recording by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var recordedMs by remember { mutableLongStateOf(0L) }
    var loudness by remember { mutableFloatStateOf(0f) }
    var voiceHint by remember { mutableStateOf<String?>(null) }
    var selecting by remember(state.conversationId) { mutableStateOf(false) }
    var selected by remember(state.conversationId) { mutableStateOf(setOf<Long>()) }
    var savingFavorite by remember { mutableStateOf(false) }
    var deletingSelected by remember { mutableStateOf<Set<Long>?>(null) }
    var deletingMessages by remember { mutableStateOf(false) }
    BackHandler(enabled = selecting && pageShown) { selecting = false; selected = emptySet() }
    fun toggleFavorite(id: Long) { selected = if (id in selected) selected - id else selected + id }
    fun saveFavorite(ids: Set<Long>) {
        val conversation = state.conversationId ?: return
        if (savingFavorite || ids.isEmpty()) return
        savingFavorite = true
        c.appScope.launch {
            try {
                c.favorites.save(conversation, ids)
                voiceHint = "已收藏"
                selecting = false
                selected = emptySet()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { voiceHint = e.message ?: "没能收藏，请再试一次"
            } finally { savingFavorite = false }
        }
    }
    var askVoiceSetup by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<String?>(null) }
    // Counts what the person sends: sending takes the chat down to the newest, wherever it was.
    var sentCount by remember { mutableIntStateOf(0) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voiceHint = if (granted) "可以了，再按住说话" else "要给 Cleos 用话筒的权限，才能发语音"
    }
    // Calls: the settings page that has what is missing before one can begin, and the call whose words are open.
    var askCallSetup by remember { mutableStateOf<SettingsPage?>(null) }
    var readingCall by remember { mutableStateOf<Long?>(null) }
    // The "tool" row whose call is open: what was asked of the tool, and what it answered.
    var readingTool by remember { mutableStateOf<Long?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editSaving by remember { mutableStateOf(false) }
    var editProblem by remember { mutableStateOf<String?>(null) }
    val askMicForCall = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.call() else voiceHint = "要给 Cleos 用话筒的权限，才能打电话"
    }

    // A TA's voice message being made ready for the ear (EarVoice) before it plays.
    val preparing = remember { arrayOfNulls<Job>(1) }
    val playScope = rememberCoroutineScope()

    fun stopPlaying() {
        preparing[0]?.cancel()
        preparing[0] = null
        player[0]?.let {
            runCatching { it.stop() }
            it.release()
        }
        player[0] = null
        playing = null
    }

    /**
     * Plays [file]. One of the TA's ([theirs]) plays beside the ear when headphones are on and that
     * is switched on: made the first time it is played, which takes a moment; it shows as playing
     * meanwhile, and a tap then stops it as usual.
     */
    fun play(file: String, theirs: Boolean) {
        val again = playing == file
        stopPlaying()
        if (again) return
        playing = file
        preparing[0] = playScope.launch {
            val ear = if (theirs && appSettings.earVoice && c.ear.headphones()) c.ear.prepared(file) else null
            preparing[0] = null
            val p = MediaPlayer()
            runCatching {
                p.setDataSource((ear ?: c.images.file(file)).path)
                p.setOnCompletionListener { stopPlaying() }
                p.prepare()
                p.start()
            }.onSuccess {
                player[0] = p
            }.onFailure {
                p.release()
                playing = null
                voiceHint = "这段语音放不了"
            }
        }
    }

    // 朗读: a message read aloud in the TA's voice. Asked for a voice first when there is none.
    var askRead by remember { mutableStateOf(false) }

    /**
     * Reads [text] aloud, a piece at a time: the next is being made while one plays. Made once and
     * kept (Speaker.reading), so a second time is instant. While it goes, [playing] is the
     * message's mark, so the menu says 停止朗读 and another tap stops it.
     */
    fun readAloud(messageId: Long, text: String) {
        val mark = readingMark(messageId)
        val again = playing == mark
        stopPlaying()
        if (again) return
        if (!state.speechReady) {
            askRead = true
            return
        }
        val parts = Speech.readParts(text)
        if (parts.pieces.isEmpty()) return
        val dir = File(context.cacheDir, "read")
        val readTa = companions.firstOrNull { it.id == state.companionId } ?: return
        val settingsNow = com.cleo.cleos.ai.TaVoices.effective(appSettings, readTa)
        playing = mark
        preparing[0] = playScope.launch {
            try {
                if (parts.cut) voiceHint = "太长了，只读前面一部分"
                coroutineScope {
                    var next = async { c.speaker.reading(settingsNow, parts.pieces[0], dir) }
                    for (i in parts.pieces.indices) {
                        val file = next.await()
                        if (i + 1 < parts.pieces.size) next = async { c.speaker.reading(settingsNow, parts.pieces[i + 1], dir) }
                        playToEnd(file) { player[0] = it }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechException) {
                voiceHint = e.message ?: "没读出来"
            } catch (e: Exception) {
                voiceHint = "这段读不出来"
            } finally {
                if (playing == mark) {
                    playing = null
                    preparing[0] = null
                }
            }
        }
    }

    /** A call needs the model, ears (voice to text) and a voice (TA 的声音); what is missing is asked for first. */
    fun startCall() {
        when {
            !state.hasApiKey -> onOpenSettingsPage(SettingsPage.Model)
            !state.voiceReady -> askCallSetup = SettingsPage.VoiceInput
            !state.speechReady -> askCallSetup = SettingsPage.Voice
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                askMicForCall.launch(Manifest.permission.RECORD_AUDIO)
            else -> {
                stopPlaying()
                vm.call()
            }
        }
    }

    fun startVoice(): Boolean {
        when {
            !state.voiceReady -> askVoiceSetup = true
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                askMic.launch(Manifest.permission.RECORD_AUDIO)
            !recorder.start() -> voiceHint = "话筒打不开，可能正被别的应用用着"
            else -> {
                stopPlaying()
                recordedMs = 0
                cancelling = false
                recording = true
                return true
            }
        }
        return false
    }

    fun endVoice(cancel: Boolean) {
        if (!recording) return
        recording = false
        cancelling = false
        if (cancel) {
            recorder.cancel()
            return
        }
        val clip = recorder.stop()
        when {
            clip == null -> voiceHint = "说话时间太短了"
            !vm.sendVoice(clip) -> voiceHint = "没发出去，再试一次"
            else -> sentCount++
        }
    }

    LaunchedEffect(recording) {
        val t0 = System.currentTimeMillis()
        while (recording) {
            recordedMs = System.currentTimeMillis() - t0
            loudness = recorder.level
            // A minute is as long as one goes; it is sent as it is.
            if (recordedMs >= Voice.MAX_MS) endVoice(cancel = false)
            delay(100)
        }
    }
    LaunchedEffect(voiceHint) {
        if (voiceHint != null) {
            delay(2200)
            voiceHint = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            stopPlaying()
            recorder.cancel()
            // Off to another screen: nothing is being typed here any more.
            vm.typing(false)
        }
    }
    // Off to another tab, the page staying as it is: the same, but for what is in the box, which waits here.
    LaunchedEffect(pageShown) {
        if (!pageShown) {
            stopPlaying()
            endVoice(cancel = true)
        }
    }
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val inputState = rememberConversationDraft(state.conversationId)
    var input by inputState
    var shareDraft by rememberConversationDraft(state.conversationId)
    val pendingShare = remember(shareDraft) { FeedShares.decode(shareDraft) }
    var beforeEditInput by rememberSaveable { mutableStateOf("") }
    fun cancelEditing() {
        editingMessage?.conversationId?.let { inputState.restore(it, beforeEditInput) }
        editingMessage = null
        editProblem = null
    }
    LaunchedEffect(state.conversationId) {
        if (!editSaving && editingMessage?.conversationId?.let { it != state.conversationId } == true) cancelEditing()
    }
    var diaryRequestId by rememberSaveable(state.conversationId) { mutableStateOf<Long?>(null) }
    val secretDraft by c.chat.secretDraft.collectAsStateWithLifecycle()
    val feedDraft by c.chat.feedDraft.collectAsStateWithLifecycle()
    var inputHeight by remember { mutableIntStateOf(0) }
    var pickingAttachment by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) {
        pickingAttachment = false
        vm.attach(it)
    }
    // Stickers: the collection, the drawer inside the input's glass, and a picture picked to add.
    val stickers by remember { c.stickers.all }.collectAsStateWithLifecycle(emptyList())
    val stickerBook = remember(stickers) { StickerBook(stickers) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var deletingSticker by remember { mutableStateOf<StickerEntity?>(null) }
    val stickerPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pickingAttachment = false
        if (uri != null) vm.pickSticker(uri) { voiceHint = it }
    }
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = drawerOpen && pageShown) { drawerOpen = false }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val inputBottom = if (imeBottom > bottomInset) imeBottom + 8.dp else bottomInset
    val storedRows = remember(state.messages, state.recapUntil, state.avatarEachMessage, appSettings.showThought) {
        buildRows(state.messages, state.recapUntil, eachFace = state.avatarEachMessage, showThought = appSettings.showThought)
    }
    val rows = remember(storedRows, state.streaming, appSettings.showThought) {
        withLiveRows(storedRows, state.messages, state.streaming, appSettings.showThought)
    }
    // The calls something was said in: only those have words to open.
    val callsSaid = remember(state.messages) { state.messages.mapNotNullTo(HashSet()) { it.call } }
    // The chat's text, for the bubbles and for what is being typed alike.
    val chatType = remember(state.chatTextSize) { ChatType(state.chatTextSize) }
    // 拍一拍 (Pats): a double tap on an avatar; the phone buzzes once unless that is switched off.
    val haptics = LocalHapticFeedback.current
    var editingPat by remember { mutableStateOf(false) }
    val buzz = state.patBuzz
    // Baseline each conversation first: opening history must never buzz for old reactions.
    var seenReactions by remember(state.conversationId) { mutableStateOf<Set<Pair<Long, String>>?>(null) }
    LaunchedEffect(state.conversationId, state.messages, buzz, pageShown) {
        val reactions = state.messages.filter { it.role == "user" }.flatMap { message ->
            MessageReactions.decode(message.reactions).map { reaction ->
                Triple(message.id, reaction.emoji, reaction.at)
            }
        }
        val previous = seenReactions
        if (previous != null && buzz && pageShown && reactions.any { (id, emoji, at) ->
            (id to emoji) !in previous && System.currentTimeMillis() - at in 0L..5_000L
        }) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        seenReactions = reactions.map { it.first to it.second }.toSet()
    }
    // The TA patting back (pat_user): the phone buzzes once when its line has just come in. One from before
    // (the chat opened later) doesn't.
    val lastPat = state.messages.lastOrNull { it.role == "pat" }
    var buzzedPat by remember { mutableStateOf(-1L) }
    LaunchedEffect(lastPat?.id) {
        val p = lastPat ?: return@LaunchedEffect
        val fresh = System.currentTimeMillis() - p.createdAt < 5_000
        if (buzz && fresh && p.id != buzzedPat && Pats.decode(p.content)?.who == Pats.FROM_AI) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            buzzedPat = p.id
        }
    }
    val patActions = remember(buzz) {
        PatActions(
            pat = { ai ->
                if (buzz) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.pat(ai)
            },
            edit = { editingPat = true },
        )
    }
    val faces = if (!state.chatAvatars) {
        null
    } else {
        Faces(
            me = Face(state.userAvatar, avatarLetter(state.userName, "我")),
            ai = Face(state.aiAvatar, state.aiAvatarEmoji ?: avatarLetter(state.aiName, "TA")),
        )
    }

    // Follow new messages, unless the user has scrolled up to read something older. Judged by
    // where the newest message from before this update is now, not by the bottom row's index:
    // several rows can arrive at once (what a TA says on its own comes with a line and a time
    // above it, often while the app was in the background), and the list keeps what was in view
    // where it was, so the index alone would read "scrolled up".
    var newestBefore by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.messages.lastOrNull()?.id, state.streaming?.text?.isEmpty(), state.streaming?.activity) {
        val was = rows.indexOfFirst { (it is ChatRow.Message && it.message.id == newestBefore) ||
            (it is ChatRow.Activity && newestBefore in it.ids) }.coerceAtLeast(0)
        newestBefore = state.messages.lastOrNull()?.id
        // Not while a searched-for message is being brought into view.
        if (c.chat.focus.value == null && listState.firstVisibleItemIndex <= was + 2) listState.animateScrollToItem(0)
    }
    // Their own message is always followed down to, like in any chat.
    LaunchedEffect(sentCount) {
        if (sentCount > 0) listState.animateScrollToItem(0)
    }
    // Something in the box: the TA waits for it before answering.
    val composing = input.isNotBlank() || pendingShare != null || recording || pickingAttachment || vm.attaching || vm.attachments.isNotEmpty() || drawerOpen
    val processingMedia = recording || pickingAttachment || vm.attaching
    LaunchedEffect(composing, processingMedia, pageShown, state.conversationId) {
        vm.typing(pageShown && composing, processingMedia = processingMedia)
    }

    val scope = rememberCoroutineScope()
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editingMessage?.id) {
        if (editingMessage != null) { withFrameNanos { }; inputFocus.requestFocus(); keyboard?.show() }
    }
    BackHandler(enabled = editingMessage != null && !editSaving && pageShown) { cancelEditing() }
    LaunchedEffect(secretDraft, state.conversationId, pageShown, editingMessage?.id) {
        val draft = secretDraft ?: return@LaunchedEffect
        if (!pageShown || editingMessage != null || state.conversationId != draft.conversationId) return@LaunchedEffect
        input = if (input.isBlank()) draft.text else input.trimEnd() + "\n" + draft.text
        diaryRequestId = draft.diaryId
        drawerOpen = false
        vm.unquote()
        c.chat.secretDraft.compareAndSet(draft, null)
        inputFocus.requestFocus()
    }
    // A feed share is editable input, not a sent message or a request to reveal a diary.
    LaunchedEffect(feedDraft, state.conversationId, pageShown, editSaving) {
        val draft = feedDraft ?: return@LaunchedEffect
        if (!pageShown || editSaving || state.conversationId != draft.conversationId) return@LaunchedEffect
        if (!c.chat.feedDraft.compareAndSet(draft, null)) return@LaunchedEffect
        if (editingMessage != null) cancelEditing()
        // Convert an untouched text draft from the preceding version into the card.
        if (input.trim() == FeedShares.text(draft.share)) input = ""
        val oldFiles = pendingShare?.images.orEmpty().map { it.file }
        c.appScope.launch { c.images.delete(oldFiles) }
        shareDraft = FeedShares.encode(draft.share)
        drawerOpen = false
        inputFocus.requestFocus()
        scope.launch { listState.animateScrollToItem(0) }
    }

    // The message a quote was tapped to find, lit up for a moment.
    var flashed by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(flashed) {
        if (flashed != null) {
            delay(1400)
            flashed = null
        }
    }

    fun quoteLabel(q: MessageQuote): String {
        val who = if (q.role == "user") state.userName.ifBlank { "我" } else state.aiName.ifBlank { "TA" }
        return "$who：${q.text}"
    }

    /** Where message [id] is drawn: its own row, or the card of the call it was said in. */
    fun rowOf(id: Long): Int {
        val own = rows.indexOfFirst { it is ChatRow.Message && it.message.id == id }
        if (own >= 0) return own
        val activity = rows.indexOfFirst { it is ChatRow.Activity && id in it.ids }
        if (activity >= 0) return activity
        val call = state.messages.firstOrNull { it.id == id }?.call ?: return -1
        return rows.indexOfFirst { it is ChatRow.Message && it.message.id == call }
    }

    /** Scrolls to the message a quote is of; one no longer in the chat is said to be gone. */
    fun jumpTo(id: Long) {
        val index = rowOf(id)
        if (index < 0) {
            voiceHint = "原消息已经不在了"
            return
        }
        scope.launch { listState.animateScrollToItem(index) }
        flashed = id
    }

    // A message found by a search: once its conversation is the one on screen, straight to it,
    // lit up. One that is gone by then is said to be.
    val focus by remember { c.chat.focus }.collectAsStateWithLifecycle()
    LaunchedEffect(focus, rows, state.conversationId) {
        val f = focus ?: return@LaunchedEffect
        if (state.conversationId != f.conversationId) return@LaunchedEffect
        val index = rowOf(f.messageId)
        if (index < 0) {
            voiceHint = "那条消息已经不在了"
        } else {
            listState.scrollToItem(index)
            flashed = f.messageId
            // Said on the phone: what was said in that call, opened.
            state.messages.firstOrNull { it.id == f.messageId }?.call?.let { readingCall = it }
        }
        c.chat.shown()
    }

    GlassPage(
        overlay = { page ->
            // The title is the TA; tapping it switches to another one or adds one.
            GlassTopBar(
                title = if (selecting) "已选 ${selected.size} 条" else state.aiName.ifBlank { "聊天" },
                subtitle = state.model.takeIf { !selecting }?.takeIf { it.isNotBlank() }?.let { "$it ▾" },
                backdrop = page,
                leading = {
                    if (selecting) TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("取消") }
                    else GlassIconButton(Icons.Rounded.Forum, "对话记录", onOpenConversations, page)
                },
                trailing = {
                    if (!selecting) GlassIconButton(Icons.Rounded.Call, "打电话", { startCall() }, page)
                    if (!selecting) GlassIconButton(Icons.Rounded.DynamicFeed, "动态", onOpenFeed, page)
                },
                onTitleClick = { if (!selecting) switching = true },
                titleMenu = {
                    DropdownMenu(expanded = switching, onDismissRequest = { switching = false }) {
                        companions.forEach { ta ->
                            val here = ta.id == state.companionId
                            DropdownMenuItem(
                                text = { Text(ta.name.ifBlank { "TA" }, fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal) },
                                leadingIcon = { Avatar(ta.avatar, ta.avatarEmoji ?: avatarLetter(ta.name, "TA"), 28.dp) },
                                trailingIcon = if (here) ({ Icon(Icons.Rounded.Check, contentDescription = "正在聊") }) else null,
                                onClick = {
                                    switching = false
                                    if (!here) vm.switchTo(ta.id)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("添加一个 TA") },
                            leadingIcon = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
                            onClick = {
                                switching = false
                                vm.addCompanion(onOpenSettings)
                            },
                        )
                    }
                },
            )
            AnimatedVisibility(
                visible = barShown,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusTop + TopBarHeight + 2.dp, start = 16.dp, end = 16.dp),
            ) {
                barSong?.let { np ->
                    ListeningBar(
                        backdrop = page,
                        np = np,
                        words = words,
                        who = state.aiName.ifBlank { "TA" },
                        onToggle = { runCatching { c.music.control(if (np.playing) MusicAction.Pause else MusicAction.Play) } },
                        onNext = { runCatching { c.music.control(MusicAction.Next) } },
                        onOpen = { if (!c.music.open()) voiceHint = "打不开这个播放器" },
                    )
                }
            }
            if (selecting) {
                GlassSurface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = inputBottom)
                        .fillMaxWidth().onSizeChanged { inputHeight = it.height },
                    shape = GlassShape.Rounded(24.dp), contentPadding = PaddingValues(8.dp),
                ) {
                    Row {
                    TextButton(onClick = { saveFavorite(selected) }, enabled = selected.isNotEmpty() && !savingFavorite && !deletingMessages,
                        modifier = Modifier.weight(1f)) {
                        Text(if (savingFavorite) "正在保存…" else "收藏这 ${selected.size} 条")
                    }
                    TextButton(onClick = { deletingSelected = selected.toSet() }, enabled = selected.isNotEmpty() && !savingFavorite && !deletingMessages,
                        modifier = Modifier.weight(1f)) { Text("删除这 ${selected.size} 条", color = LocalGlassPalette.current.error) }
                    }
                }
            } else ChatInputBar(
                backdrop = page,
                type = chatType,
                text = input,
                onTextChange = { input = it; editProblem = null; if (it.isBlank() && editingMessage == null) diaryRequestId = null },
                attachments = editingMessage?.let { m ->
                    val cardFiles = FeedShares.decode(m.feedShare)?.images.orEmpty().map { it.file }.toSet()
                    MessageImages.decode(m.images).filterNot { it.file in cardFiles }
                } ?: vm.attachments,
                attaching = editingMessage == null && vm.attaching,
                onPick = { pickingAttachment = true; vm.typing(true, processingMedia = true); picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemove = vm::detach,
                quote = if (editingMessage != null) MessageQuotes.decode(editingMessage?.quote)?.let { quoteLabel(it) }
                    else if (diaryRequestId != null) "询问 TA 的这篇小秘密" else vm.quoting?.let { quoteLabel(it) },
                onDropQuote = { diaryRequestId = null; vm.unquote() },
                share = if (editingMessage != null) FeedShares.decode(editingMessage?.feedShare) else pendingShare,
                onRemoveShare = if (editingMessage == null) ({
                    val files = pendingShare?.images.orEmpty().map { it.file }
                    shareDraft = ""
                    c.appScope.launch { c.images.delete(files) }
                }) else null,
                onOpenShareImage = onOpenImage,
                focus = inputFocus,
                busy = state.replying || editSaving,
                editing = editingMessage != null,
                editHint = editProblem ?: if (editSaving) "正在修改…" else "正在编辑 · 发送后从这里重新回答",
                onCancelEdit = { if (!editSaving) cancelEditing() },
                sendEnabled = !editSaving && (editingMessage?.let {
                    val shared = FeedShares.decode(it.feedShare)
                    (input.isNotBlank() || shared != null) && input.trim() != (shared?.caption ?: it.content) && !state.replying && state.hasApiKey
                } ?: true),
                onSend = {
                    val original = editingMessage
                    if (original != null) {
                        editSaving = true
                        vm.editMessage(original, input) { problem ->
                            editSaving = false
                            if (problem == null) { cancelEditing(); sentCount++ } else editProblem = problem
                        }
                    } else if (vm.send(input, diaryRequestId, pendingShare)) {
                        shareDraft = ""
                        diaryRequestId = null
                        input = ""
                        sentCount++
                    }
                },
                onStop = vm::stop,
                recording = recording,
                onVoiceStart = { startVoice() },
                onVoiceMove = { cancelling = it },
                onVoiceEnd = { endVoice(it) },
                drawerOpen = drawerOpen,
                onDrawer = {
                    drawerOpen = !drawerOpen
                    // The drawer takes the keyboard's place; typing again closes it (onFieldFocus).
                    if (drawerOpen) {
                        keyboard?.hide()
                        focusManager.clearFocus()
                    }
                },
                onFieldFocus = { drawerOpen = false },
                drawer = {
                    StickerDrawer(
                        stickers = stickers,
                        onSend = { if (vm.sendSticker(it)) sentCount++ },
                        onAdd = { pickingAttachment = true; vm.typing(true, processingMedia = true); stickerPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRename = vm::renameSticker,
                        onDelete = { deletingSticker = it },
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = inputBottom)
                    .onSizeChanged { inputHeight = it.height },
            )
            if (!selecting && !recording && !drawerOpen && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 80)) {
                GlassIconButton(Icons.Rounded.ArrowDownward, "回到最新消息", {
                    scope.launch { listState.scrollToItem(0) }
                }, page, modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = inputBottom + with(density) { inputHeight.toDp() } + 12.dp))
            }
            if (recording || voiceHint != null) {
                RecordingPill(
                    backdrop = page,
                    recording = recording,
                    ms = recordedMs,
                    level = loudness,
                    cancelling = cancelling,
                    hint = voiceHint,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = inputBottom + with(density) { inputHeight.toDp() } + 10.dp),
                )
            }
        },
    ) {
        val inputTop = inputBottom + with(density) { inputHeight.toDp() }
        CompositionLocalProvider(LocalFaces provides faces, LocalStickers provides stickerBook, LocalChatType provides chatType, LocalPat provides patActions,
            LocalBubbleThemes provides (appSettings.myBubbleTheme to (appSettings.taBubbleThemes[state.companionId.toString()] ?: "glass")),
            LocalBubblePadding provides (appSettings.bubblePaddingX to appSettings.bubblePaddingY),
            LocalMessageSpacing provides appSettings.messageSpacing,
            LocalBubbleDecoration provides appSettings.bubbleDecoration,
            LocalBubbleBackgrounds provides (appSettings.bubbleBackgrounds[com.cleo.cleos.data.BubbleBackground.key(true, state.companionId, appSettings.myBubbleTheme)] to
                appSettings.bubbleBackgrounds[com.cleo.cleos.data.BubbleBackground.key(false, state.companionId, appSettings.taBubbleThemes[state.companionId.toString()] ?: "glass")])) {
            if (editingPat) {
                PatDialog(
                    aiName = state.aiName,
                    verb = state.patVerb,
                    suffix = state.patSuffix,
                    buzz = state.patBuzz,
                    onSave = { v, s, b ->
                        vm.savePat(v, s, b)
                        editingPat = false
                    },
                    onDismiss = { editingPat = false },
                )
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .fadeUnderTopBar(statusTop + TopBarHeight, bottom = inputTop),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = statusTop + TopBarHeight + 8.dp + (if (barShown) ListeningBarSpace else 0.dp),
                    bottom = inputTop + 12.dp,
                ),
                // Bottom: a short conversation should sit just above the input, where the
                // newest message is, not float up under the title. spacedBy without an
                // alignment would put it at the top even in a reversed list.
                verticalArrangement = Arrangement.spacedBy(com.cleo.cleos.data.BubbleSpacing.normalized(appSettings.messageSpacing).dp, Alignment.Bottom),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ChatRow.Stamp -> TimeStamp(row.at)
                        ChatRow.RecapMark -> RecapMark(state.aiName) { readingRecap = true }
                        is ChatRow.Woke -> ToolNote("${state.aiName.ifBlank { "TA" }}自己想起来的", Icons.Rounded.Lightbulb)
                        is ChatRow.Live -> LiveBubble(row.reply, state.aiName, vm::answerAsk)
                        is ChatRow.Activity -> Box(Modifier.fillMaxWidth().padding(start = if (LocalFaces.current != null) AvatarSlot else 0.dp)) {
                            ActivityCard(
                                events = row.events, live = row.live, identity = row.key, maxWidth = bubbleMaxWidth(),
                                forceOpen = flashed in row.ids && rows.none { it is ChatRow.Message && it.message.id == flashed },
                                onOpenCall = { readingTool = it },
                                onLongClick = { if (row.ids.isNotEmpty()) { selected = row.ids; selecting = true } },
                            )
                            if (selecting && row.ids.isNotEmpty()) {
                                Box(Modifier.matchParentSize().clickable {
                                    selected = if (selected.containsAll(row.ids)) selected - row.ids else selected + row.ids
                                })
                                Checkbox(row.ids.all { it in selected }, null, modifier = Modifier.align(Alignment.CenterStart))
                            }
                        }
                        is ChatRow.Message -> {
                            val m = row.message
                            val note = m.note
                            when {
                                m.role == "call" -> CallNote(m, said = m.id in callsSaid) { readingCall = m.id }
                                m.role == "note" && m.content.startsWith("secret-excerpt:") -> {
                                    val share = remember(m.content) { com.cleo.cleos.ai.SecretShares.decode(m.content.removePrefix("secret-excerpt:")) }
                                    if (share != null) GlassSurface(modifier = Modifier.fillMaxWidth(), shape = GlassShape.Rounded(22.dp), contentPadding = PaddingValues(16.dp)) {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text("${state.aiName}愿意告诉你一点", color = LocalGlassPalette.current.accentContent, fontSize = 14.sp)
                                            Text(share.excerpt, color = LocalGlassPalette.current.content, fontSize = 16.sp, lineHeight = 24.sp)
                                            Text("看看这篇日记  ›", color = LocalGlassPalette.current.accentContent, fontSize = 14.sp,
                                                modifier = Modifier.clickable { c.opening.value = com.cleo.cleos.Opening.Diary(share.diaryId) })
                                        }
                                    }
                                }
                                m.role == "note" && m.content.startsWith("shared-diary:") -> {
                                    val diaryId = m.content.removePrefix("shared-diary:").toLongOrNull()
                                    GlassSurface(modifier = Modifier.fillMaxWidth().clickable {
                                        diaryId?.let { c.opening.value = com.cleo.cleos.Opening.Diary(it) }
                                    }, shape = GlassShape.Rounded(22.dp), contentPadding = PaddingValues(16.dp)) {
                                        Text("查看这篇小秘密  ›", color = LocalGlassPalette.current.accentContent, fontSize = 16.sp)
                                    }
                                }
                                m.role == "tool" || m.role == "note" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (!row.activityFolded) Box(Modifier.fillMaxWidth().combinedClickable(
                                    onClick = { if (selecting) toggleFavorite(m.id) else if (m.role == "tool") readingTool = m.id },
                                    onLongClick = { selected = setOf(m.id); selecting = true })) {
                                    ToolNote(
                                        note.orEmpty(),
                                        if (m.role == "note") Icons.Rounded.Info else (row.tool ?: ToolKind.Other).icon(),
                                        failed = m.role == "tool" && ToolRuns.failed(note.orEmpty()),
                                        onClick = null,
                                    )
                                    if (selecting) Checkbox(m.id in selected, null, modifier = Modifier.align(Alignment.CenterStart))
                                    }
                                    val pictures = remember(m.images) { MessageImages.decode(m.images) }
                                    if (pictures.isNotEmpty()) Box {
                                        ToolPictureCard(pictures, onOpenImage) {
                                            selected = setOf(m.id); selecting = true
                                        }
                                        if (selecting) Box(Modifier.matchParentSize().clickable { toggleFavorite(m.id) })
                                    }
                                }
                                m.role == "request" -> RequestCard(m, state.aiName, enabled = !state.replying) { grant ->
                                    vm.answerSecret(m.id, grant)
                                }
                                // The person's answer to a request: their turn, drawn as a line on their side.
                                m.role == "user" && note != null -> ToolNote(note, Icons.Rounded.Key, mine = true)
                                m.role == "pat" -> PatLine(Pats.decode(m.content), state.aiName)
                                else -> {
                                    val quote = remember(m.quote) { MessageQuotes.decode(m.quote) }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        if (selecting) Checkbox(checked = m.id in selected, onCheckedChange = { toggleFavorite(m.id) }, enabled = !savingFavorite,
                                            modifier = Modifier.semantics { contentDescription = "选择消息：" + (if (m.audio != null) "语音，" else "") + m.content.take(80) })
                                        Box(Modifier.weight(1f)) {
                                    MessageBubble(
                                        message = m,
                                        reactionAvatar = if (m.role == "user") state.aiAvatar else state.userAvatar,
                                        reactionLetter = if (m.role == "user") state.aiAvatarEmoji ?: avatarLetter(state.aiName, "TA") else avatarLetter(state.userName, "我"),
                                        showFace = row.showFace,
                                        canRetry = row.isLast && !state.replying,
                                        onRetry = { vm.retry(m.id) },
                                        onDelete = { vm.delete(m.id) },
                                        onEdit = if (!state.replying && !editSaving && MessageEdits.eligible(m)) ({
                                            if (editingMessage == null) beforeEditInput = input
                                            editingMessage = m; input = FeedShares.decode(m.feedShare)?.caption ?: m.content; editProblem = null; drawerOpen = false
                                        }) else null,
                                        onOpenImage = onOpenImage,
                                        transcribing = m.id in state.transcribing,
                                        expandVoiceText = appSettings.expandVoiceText,
                                        playingFile = playing,
                                        onPlay = { play(it, theirs = m.role == "assistant") },
                                        onRetryVoice = { vm.retryVoice(m.id) },
                                        quote = quote?.let { quoteLabel(it) },
                                        onOpenQuote = { quote?.let { jumpTo(it.id) } },
                                        onQuote = {
                                            vm.quote(m)
                                            inputFocus.requestFocus()
                                            keyboard?.show()
                                        },
                                        highlighted = m.id == flashed,
                                        onReact = { vm.react(m.id, it) },
                                        onRead = { readAloud(m.id, it) },
                                        onFavorite = { saveFavorite(setOf(m.id)) },
                                        onSelect = {
                                            stopPlaying()
                                            drawerOpen = false
                                            keyboard?.hide()
                                            focusManager.clearFocus()
                                            selected = setOf(m.id)
                                            selecting = true
                                        },
                                    )
                                    if (selecting) Box(Modifier.matchParentSize().clickable(enabled = !savingFavorite) { toggleFavorite(m.id) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.loaded && !state.hasApiKey && state.messages.isEmpty()) {
            NoKeyCard({ onOpenSettingsPage(SettingsPage.Model) }, Modifier.align(Alignment.Center).padding(horizontal = 28.dp))
        } else if (state.loaded && state.messages.isEmpty() && state.streaming == null) {
            GlassSurface(
                modifier = Modifier.align(Alignment.Center),
                style = palette.notice,
                shape = GlassShape.Capsule,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(
                    if (state.aiName.isBlank()) "说点什么吧" else "和${state.aiName}说点什么吧",
                    color = palette.contentSecondary,
                    fontSize = 15.sp,
                )
            }
        }
    }

    if (askVoiceSetup) {
        AlertDialog(
            onDismissRequest = { askVoiceSetup = false },
            title = { Text("先接一个转文字的服务") },
            text = { Text("语音要先转成文字再给${state.aiName.ifBlank { "TA" }}看。在设置「发语音」里选一个服务、填上 Key 就能用。") },
            confirmButton = {
                TextButton(onClick = {
                    askVoiceSetup = false
                    onOpenSettingsPage(SettingsPage.VoiceInput)
                }) { Text("去设置") }
            },
            dismissButton = { TextButton(onClick = { askVoiceSetup = false }) { Text("算了") } },
        )
    }

    if (askRead) {
        val ai = state.aiName.ifBlank { "TA" }
        AlertDialog(
            onDismissRequest = { askRead = false },
            title = { Text("先给${ai}选一个声音") },
            text = { Text("朗读要用${ai}的声音。在设置「TA 的声音」里选一个服务和音色就能用。") },
            confirmButton = {
                TextButton(onClick = {
                    askRead = false
                    onOpenSettingsPage(SettingsPage.Voice)
                }) { Text("去设置") }
            },
            dismissButton = { TextButton(onClick = { askRead = false }) { Text("算了") } },
        )
    }

    askCallSetup?.let { page ->
        val ai = state.aiName.ifBlank { "TA" }
        AlertDialog(
            onDismissRequest = { askCallSetup = null },
            title = { Text(if (page == SettingsPage.VoiceInput) "先接一个转文字的服务" else "先给${ai}选一个声音") },
            text = {
                Text(
                    if (page == SettingsPage.VoiceInput) {
                        "电话里你说的话要先转成文字，${ai}才听得懂。在设置「发语音」里选一个服务、填上 Key 就能用。"
                    } else {
                        "电话里${ai}要用声音回你。在设置「TA 的声音」里选一个服务和音色就能用。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askCallSetup = null
                    onOpenSettingsPage(page)
                }) { Text("去设置") }
            },
            dismissButton = { TextButton(onClick = { askCallSetup = null }) { Text("算了") } },
        )
    }

    readingCall?.let { id ->
        state.messages.firstOrNull { it.id == id && it.role == "call" }?.let { record ->
            CallTranscript(
                record = record,
                lines = state.messages.filter { it.call == id },
                aiName = state.aiName.ifBlank { "TA" },
                userName = state.userName.ifBlank { "我" },
                onDelete = {
                    readingCall = null
                    vm.delete(id)
                },
                onDismiss = { readingCall = null },
                onFavorite = {
                    val ids = state.messages.filter { it.call == id && FavoriteContent.eligible(it) }.map { it.id }.toSet()
                    saveFavorite(ids)
                    readingCall = null
                },
            )
        }
    }

    readingTool?.let { id ->
        state.messages.firstOrNull { it.id == id && it.role == "tool" }?.let { row ->
            val detail = remember(id, state.messages) { ToolDetails.find(state.messages, row) }
            ToolDetailDialog(detail, onDismiss = { readingTool = null }, onDelete = {
                readingTool = null; deletingSelected = setOf(id)
            }, onSelect = { readingTool = null; selected = setOf(id); selecting = true })
        }
    }

    deletingSelected?.let { ids ->
        AlertDialog(onDismissRequest = { if (!deletingMessages) deletingSelected = null },
            title = { Text("删除这 ${ids.size} 条记录？") },
            text = { Text("相关工具请求和结果会一并清理，后续聊天保留。只删除聊天记录，不撤销工具已经完成的操作。") },
            confirmButton = { TextButton(enabled = !deletingMessages, onClick = {
                deletingMessages = true
                scope.launch {
                    try { c.chat.deleteMessages(ids); selected = emptySet(); selecting = false; deletingSelected = null }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { voiceHint = e.message ?: "删除失败，请重试" }
                    finally { deletingMessages = false }
                }
            }) { Text(if (deletingMessages) "正在删除…" else "删除") } },
            dismissButton = { TextButton(enabled = !deletingMessages, onClick = { deletingSelected = null }) { Text("取消") } })
    }
    if (readingRecap) {
        RecapDialog(
            aiName = state.aiName,
            recap = state.recap,
            onSave = {
                vm.saveRecap(it)
                readingRecap = false
            },
            onDismiss = { readingRecap = false },
        )
    }

    vm.stickerDraft?.let { draft ->
        StickerDialog(draft, vm.stickerProblem, onSave = vm::saveSticker, onDismiss = vm::dropStickerDraft)
    }
    deletingSticker?.let { s ->
        DeleteStickerDialog(
            s,
            onConfirm = {
                vm.deleteSticker(s)
                deletingSticker = null
            },
            onDismiss = { deletingSticker = null },
        )
    }
}

@Composable
private fun bubbleMaxWidth(): Dp {
    val width = LocalConfiguration.current.screenWidthDp
    // At most 70% of the screen: a bubble running nearly edge to edge reads like a page, not a
    // message. With avatars, also no wider than leaves an avatar's room on the far side.
    val most = (width * BUBBLE_SHARE).dp
    return if (LocalFaces.current != null) minOf(most, width.dp - 24.dp - AvatarSlot * 2) else most
}

/** How much of the screen's width a bubble may take. */
private const val BUBBLE_SHARE = 0.7f

/** Room inside a bubble around its words. */
private val BubblePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@Composable
private fun TimeStamp(at: Long) {
    val palette = LocalGlassPalette.current
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        GlassSurface(
            style = palette.bar,
            shape = GlassShape.Capsule,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(Dates.chatStamp(at), color = palette.contentSecondary, fontSize = 12.sp)
        }
    }
}

/** Where the messages sent verbatim begin; tapping it shows the recap. */
@Composable
private fun RecapMark(aiName: String, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        GlassSurface(
            modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onClick),
            style = palette.bar,
            shape = GlassShape.Capsule,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text("往上的，${aiName.ifBlank { "TA" }}记成了前情提要 ›", color = palette.contentSecondary, fontSize = 12.sp)
        }
    }
}

/** The recap, to read and to put right. */
@Composable
private fun RecapDialog(aiName: String, recap: String?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(recap.orEmpty()) }
    val name = aiName.ifBlank { "TA" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("前情提要") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "往上的聊天不再原样发给$name，${name}靠这段记着；聊得越多，它会自己往下续。哪里记得不对，可以改。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(Recap.MAX_STORED) },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    reactionAvatar: String?,
    reactionLetter: String,
    showFace: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpenImage: (String) -> Unit,
    transcribing: Boolean = false,
    expandVoiceText: Boolean = false,
    playingFile: String? = null,
    onPlay: (String) -> Unit = {},
    onRetryVoice: () -> Unit = {},
    quote: String? = null,
    onOpenQuote: () -> Unit = {},
    onQuote: () -> Unit = {},
    highlighted: Boolean = false,
    onReact: (String) -> Unit = {},
    onRead: (String) -> Unit = {},
    onFavorite: () -> Unit = {},
    onSelect: () -> Unit = {},
    onEdit: (() -> Unit)? = null,
) {
    val palette = LocalGlassPalette.current
    val mine = message.role == "user"
    val glow by animateColorAsState(if (highlighted) palette.accent.copy(alpha = 0.14f) else Color.Transparent, label = "found")
    val audio = remember(message.audio) { MessageAudios.decode(message.audio) }
    var menu by remember { mutableStateOf(false) }
    var selectingText by remember(message.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val faces = LocalFaces.current
    val pictures = remember(message.images, message.feedShare) {
        val cardFiles = FeedShares.decode(message.feedShare)?.images.orEmpty().map { it.file }.toSet()
        MessageImages.decode(message.images).filterNot { it.file in cardFiles }
    }
    // Words and stickers, in order; the words alone are what 复制 copies.
    val book = LocalStickers.current
    val shared = remember(message.feedShare) { FeedShares.decode(message.feedShare) }
    val displayText = shared?.caption ?: message.content
    val pieces = remember(displayText, book) { StickerText.split(displayText, book) }
    val words = remember(pieces, shared) { if (shared != null) message.content else pieces.mapNotNull {
        when (it) {
            is StickerText.Piece.Words -> it.text
            is StickerText.Piece.Unavailable -> "表情包不可用：${it.name}"
            is StickerText.Piece.Sticker -> null
        }
    }.joinToString("\n") }
    val reactions = remember(message.reactions) { MessageReactions.decode(message.reactions) }

    Row(
        Modifier.fillMaxWidth().background(glow, RoundedCornerShape(18.dp)),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        // Without its face, the bubble still keeps the face's room: a run lines up.
        if (faces != null && !mine) {
            if (showFace) {
                Avatar(faces.ai.file, faces.ai.letter, AvatarSize, Modifier.pattable(ai = true))
                Spacer(Modifier.width(AvatarGap))
            } else {
                Spacer(Modifier.width(AvatarSlot))
            }
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            // One menu for the whole message, so a message that is only pictures has one too.
            Box {
                Column(
                    horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (pictures.isNotEmpty()) {
                        PictureGroup(pictures, onOpen = onOpenImage, onLongPress = { menu = true })
                    }
                    if (audio != null) {
                        VoiceBubble(
                            audio = audio,
                            mine = mine,
                            playing = playingFile == audio.file,
                            transcript = message.content,
                            transcribing = transcribing,
                            defaultExpanded = expandVoiceText,
                            messageId = message.id,
                            onClick = { onPlay(audio.file) },
                            onLongClick = { menu = true },
                        )
                    } else {
                        pieces.forEach { piece ->
                            when (piece) {
                                // A sticker stands on its own, outside any bubble, as chat apps draw them.
                                is StickerText.Piece.Sticker -> StickerView(piece.sticker) { menu = true }
                                is StickerText.Piece.Unavailable -> UnavailableSticker(piece.name) { menu = true }
                                is StickerText.Piece.Words -> ChatBubbleSurface(
                                    modifier = Modifier
                                        .widthIn(max = bubbleMaxWidth())
                                        .combinedClickable(
                                            interactionSource = null,
                                            indication = null,
                                            onClick = {},
                                            onLongClick = { menu = true },
                                        ),
                                    mine = mine,
                                ) { bubbleInk ->
                                    MessageText(
                                        piece.text,
                                        color = bubbleInk,
                                        style = LocalChatType.current.body,
                                    )
                                }
                            }
                        }
                    }
                    if (shared != null) FeedShareCard(shared, Modifier.widthIn(max = bubbleMaxWidth()), onLongClick = { menu = true }, onOpenImage = onOpenImage)
                    // The message this one answers, under it like in WeChat; a tap finds it.
                    if (quote != null) QuoteBox(quote, onOpenQuote)
                    if (reactions.isNotEmpty()) ReactionChips(reactions, reactionAvatar, reactionLetter) { menu = true }
                }
                val actions = buildList {
                    if (words.isNotBlank()) add(MessageMenuAction("复制") {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", words))) }
                    })
                    if (words.isNotBlank()) add(MessageMenuAction("选择文字") { selectingText = true })
                    onEdit?.let { add(MessageMenuAction("编辑", it)) }
                    if (!mine && message.error == null && audio == null && words.isNotBlank())
                        add(MessageMenuAction(if (playingFile == readingMark(message.id)) "停止朗读" else "朗读") { onRead(words) })
                    if (message.error == null && MessageQuotes.of(message) != null) add(MessageMenuAction("引用", onQuote))
                    if (audio != null && message.content.isBlank() && !transcribing) add(MessageMenuAction("重新转文字", onRetryVoice))
                    if (!mine && canRetry) add(MessageMenuAction("重新回答", onRetry))
                    if (FavoriteContent.eligible(message)) {
                        add(MessageMenuAction("收藏", onFavorite)); add(MessageMenuAction("多选", onSelect))
                    }
                    add(MessageMenuAction("删除", onDelete))
                }
                MessageActionMenu(menu, { menu = false }, actions,
                    if (!mine && message.error == null) reactions.map { it.emoji }.toSet() else null, onReact)
                if (selectingText) SelectMessageTextDialog(words, LocalChatType.current.body) { selectingText = false }
            }
            val error = message.error
            if (error != null) {
                // On its own capsule: this line sits between bubbles, i.e. straight on the
                // wallpaper, and red text over a dark photo is unreadable.
                GlassSurface(
                    modifier = Modifier.padding(top = 4.dp).widthIn(max = bubbleMaxWidth()),
                    style = palette.notice,
                    shape = GlassShape.Rounded(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            error,
                            color = if (error == ChatRepository.STOPPED) palette.contentSecondary else palette.error,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // A reply that failed is asked again; a voice message that wasn't transcribed, transcribed again.
                        if (canRetry && (!mine || audio != null)) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "重试",
                                color = palette.accentContent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.combinedClickable(onClick = if (mine) onRetryVoice else onRetry),
                            )
                        }
                        if (message.content.isEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "删除",
                                color = palette.contentSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.combinedClickable(onClick = onDelete),
                            )
                        }
                    }
                }
            }
        }
        if (faces != null && mine) {
            if (showFace) {
                Spacer(Modifier.width(AvatarGap))
                Avatar(faces.me.file, faces.me.letter, AvatarSize, Modifier.pattable(ai = false))
            } else {
                Spacer(Modifier.width(AvatarSlot))
            }
        }
    }
}

/**
 * A voice message: its length, which it plays on a tap, and underneath, what it said once it
 * has been turned into text. A longer recording draws a longer bubble, the way chat apps do.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VoiceBubble(
    audio: MessageAudio,
    mine: Boolean,
    playing: Boolean,
    transcript: String,
    transcribing: Boolean,
    defaultExpanded: Boolean,
    messageId: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    var expanded by rememberSaveable(messageId, defaultExpanded) { mutableStateOf(defaultExpanded) }
    ChatBubbleSurface(
        modifier = Modifier
            .widthIn(max = bubbleMaxWidth())
            .combinedClickable(interactionSource = null, indication = null, onClick = onClick, onLongClick = onLongClick),
        mine = mine,
    ) { ink ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "停下" else "播放语音",
                    tint = ink,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(Voice.duration(audio.ms), color = ink, fontSize = 15.sp)
            }
            when {
                transcript.isNotBlank() -> {
                    Row(
                        Modifier.heightIn(min = 44.dp).combinedClickable(
                            interactionSource = null, indication = null,
                            onClick = { expanded = !expanded }, onLongClick = onLongClick,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (expanded) "收起文字" else "查看文字", color = ink.copy(alpha = 0.75f), fontSize = 13.sp)
                        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null, tint = ink.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
                    }
                    if (expanded) MessageText(transcript, color = ink.copy(alpha = 0.85f), style = LocalChatType.current.small)
                }
                transcribing -> Text("转文字中…", color = ink.copy(alpha = 0.7f), fontSize = 13.sp)
            }
        }
    }
}

/** Above the input while recording: how long, how loud, and what letting go does; or a short hint. */
@Composable
private fun RecordingPill(
    backdrop: Backdrop,
    recording: Boolean,
    ms: Long,
    level: Float,
    cancelling: Boolean,
    hint: String?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    Row(
        modifier
            .liquidGlass(backdrop, palette.input, GlassShape.Capsule)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!recording) {
            Text(hint.orEmpty(), color = palette.content, fontSize = 14.sp)
            return@Row
        }
        val tint = if (cancelling) palette.error else palette.accent
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp + 12.dp * level).background(tint, CircleShape))
        }
        Spacer(Modifier.width(8.dp))
        val s = ms / 1000
        Text("${s / 60}:%02d".format(s % 60), color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(12.dp))
        Text(if (cancelling) "松开取消" else "松开发送，上滑取消", color = if (cancelling) palette.error else palette.contentSecondary, fontSize = 14.sp)
    }
}

/**
 * Pictures sent with a message, outside the bubble: a glass pane behind a photo would only
 * blur its edges. One picture keeps its shape; several become a grid of squares.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PictureGroup(pictures: List<MessageImage>, onOpen: (String) -> Unit, onLongPress: () -> Unit) {
    val c = appContainer()
    val maxWidth = minOf(bubbleMaxWidth(), 240.dp)
    fun Modifier.picture(file: String) = clip(RoundedCornerShape(18.dp)).combinedClickable(
        onClick = { onOpen(file) },
        onLongClick = onLongPress,
    )
    if (pictures.size == 1) {
        val p = pictures[0]
        val ratio = (p.width.toFloat() / p.height.coerceAtLeast(1)).coerceIn(0.6f, 1.8f)
        AsyncImage(
            model = c.images.file(p.file),
            contentDescription = "图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                // A tall photo is held to a height, so one picture doesn't fill the screen.
                .width(minOf(maxWidth, MaxPictureHeight * ratio))
                .aspectRatio(ratio)
                .picture(p.file),
        )
    } else {
        val cell = (maxWidth - 4.dp) / 2
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            pictures.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { p ->
                        AsyncImage(
                            model = c.images.file(p.file),
                            contentDescription = "图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(cell).picture(p.file),
                        )
                    }
                }
            }
        }
    }
}

/** Shares the actual stored files, alongside their tool result rather than interrupting the API tool chain. */
@Composable
private fun ToolPictureCard(pictures: List<MessageImage>, onOpen: (String) -> Unit, onLongPress: () -> Unit) {
    val faces = LocalFaces.current
    val palette = LocalGlassPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        if (faces != null) {
            Avatar(faces.ai.file, faces.ai.letter, AvatarSize)
            Spacer(Modifier.width(AvatarGap))
        }
        GlassSurface(shape = GlassShape.Rounded(22.dp), contentPadding = PaddingValues(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PictureGroup(pictures, onOpen, onLongPress)
                Text("来自 " + pictures.mapNotNull { it.source }.distinct().joinToString("、").ifBlank { "外部服务" },
                    modifier = Modifier.widthIn(max = minOf(bubbleMaxWidth(), 240.dp)),
                    color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                pictures.mapNotNull { it.credit }.distinct().forEach { credit ->
                    PhotoAttribution(credit, Modifier.widthIn(max = minOf(bubbleMaxWidth(), 240.dp)))
                }
            }
        }
    }
}

@Composable
private fun LiveBubble(live: StreamingReply, aiName: String, onAnswer: (ChatRepository.Answer) -> Unit) {
    val palette = LocalGlassPalette.current
    val faces = LocalFaces.current
    // What has come in so far, stickers drawn as they complete; one still being written isn't shown yet.
    val book = LocalStickers.current
    val pieces = remember(live.text, book) { StickerText.split(StickerText.finishedPart(live.text), book) }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (live.text.isNotEmpty() || (live.activity == null && live.asking == null)) {
            Row {
                if (faces != null) {
                    Avatar(faces.ai.file, faces.ai.letter, AvatarSize)
                    Spacer(Modifier.width(AvatarGap))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (pieces.isEmpty()) {
                        GlassSurface(
                            style = palette.bubble,
                            shape = GlassShape.Rounded(20.dp),
                            contentPadding = BubblePadding,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TypingDots()
                                if (live.thinking) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("在想", color = palette.contentSecondary, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    pieces.forEach { piece ->
                        when (piece) {
                            is StickerText.Piece.Sticker -> StickerView(piece.sticker) {}
                            is StickerText.Piece.Unavailable -> UnavailableSticker(piece.name) {}
                            is StickerText.Piece.Words -> ChatBubbleSurface(
                                modifier = Modifier.widthIn(max = bubbleMaxWidth()),
                            ) { ink ->
                                Text(piece.text, color = ink, style = LocalChatType.current.body)
                            }
                        }
                    }
                }
            }
        }
        live.asking?.let { AskCard(it, aiName, onAnswer) }
    }
}

/** The message a bubble answers: who said it and what, in a grey box under the bubble. */
@Composable
private fun QuoteBox(label: String, onOpen: () -> Unit) {
    val palette = LocalGlassPalette.current
    GlassSurface(
        modifier = Modifier
            .widthIn(max = bubbleMaxWidth())
            .clickable(interactionSource = null, indication = null, onClick = onOpen),
        style = palette.notice,
        shape = GlassShape.Rounded(10.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label.replace('\n', ' '),
            color = palette.contentSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A TA's call to an outside service, waiting for the person to allow it. */
@Composable
private fun AskCard(ask: McpAsk, aiName: String, onAnswer: (ChatRepository.Answer) -> Unit) {
    val palette = LocalGlassPalette.current
    val who = aiName.ifBlank { "TA" }
    GlassSurface(
        modifier = Modifier
            .padding(start = if (LocalFaces.current != null) AvatarSlot else 0.dp)
            .widthIn(max = bubbleMaxWidth()),
        style = palette.bubble,
        shape = GlassShape.Rounded(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Extension, contentDescription = null, tint = palette.accentContent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("${who}想用${ask.service}的「${ask.tool}」", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(ask.arguments, color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Pill("允许", accent = true, enabled = true) { onAnswer(ChatRepository.Answer.Yes) }
                Pill("以后都允许", accent = false, enabled = true) { onAnswer(ChatRepository.Answer.Always) }
                Pill("不允许", accent = false, enabled = true) { onAnswer(ChatRepository.Answer.No) }
            }
            Text("「以后都允许」只管这一个工具，在设置里能改回来。", color = palette.contentSecondary, fontSize = 12.sp)
        }
    }
}

/**
 * One line for something done on the way to a reply (记下了待办「交报告」), a notice from
 * the app, or the person's answer to a request ([mine], on their side). A capsule of its
 * own, like the error lines: it sits on the wallpaper.
 */
@Composable
private fun ToolNote(text: String, icon: ImageVector, running: Boolean = false, mine: Boolean = false, failed: Boolean = false, onClick: (() -> Unit)? = null) {
    val palette = LocalGlassPalette.current
    val slot = if (LocalFaces.current != null) AvatarSlot else 0.dp
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = if (mine) 0.dp else slot, end = if (mine) slot else 0.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        GlassSurface(
            modifier = Modifier.widthIn(max = bubbleMaxWidth()),
            style = palette.notice,
            shape = GlassShape.Rounded(14.dp),
            contentPadding = PaddingValues(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Row(
                modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (failed) Icons.Outlined.Warning else icon,
                    contentDescription = null,
                    tint = if (failed) palette.error else if (running) palette.contentSecondary else palette.accentContent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(text, color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                if (onClick != null) Text("  ›", color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

/** A tool call opened from its line in the chat: what was asked of the tool, and what it answered, to read and copy. */
@Composable
private fun ToolDetailDialog(detail: ToolDetail, onDismiss: () -> Unit, onDelete: () -> Unit, onSelect: () -> Unit) {
    val palette = LocalGlassPalette.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            style = palette.card,
            shape = GlassShape.Rounded(28.dp),
            contentPadding = PaddingValues(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("调用工具: ${detail.name}", color = palette.content, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Column(
                    Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val privateDiary = (detail.name == "write_diary" &&
                        com.cleo.cleos.ai.ToolArgs.parse(detail.arguments)?.let { com.cleo.cleos.ai.ToolArgs.bool(it, "secret") } == true) ||
                        (detail.name == "read_diary" && detail.result.contains("【私密日记 #"))
                    if (privateDiary) Text("这里包含 TA 留给自己的小秘密，请在日记页询问 TA 是否愿意分享。", color = palette.contentSecondary)
                    else {
                        if (detail.arguments.isNotBlank()) ToolDetailBlock("参数", detail.arguments)
                        ToolDetailBlock("结果", detail.result.ifBlank { "（空）" })
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onSelect) { Text("多选") }
                    TextButton(onClick = onDelete) { Text("删除") }
                    TextButton(onClick = onDismiss) { Text("关上") }
                }
            }
        }
    }
}
/** One labelled piece of text with a copy button; long ones are cut on screen, and copied whole. */
@Composable
private fun ToolDetailBlock(label: String, text: String) {
    val palette = LocalGlassPalette.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val shown = if (text.length > TOOL_DETAIL_SHOWN) text.take(TOOL_DETAIL_SHOWN) + "\n…（太长，这里只显示前 $TOOL_DETAIL_SHOWN 字，复制的是全文）" else text
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = palette.accentContent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) } }) { Text("复制") }
    }
    SelectionContainer {
        MessageText(
            shown,
            color = palette.content,
            style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.contentSecondary.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .padding(10.dp),
        )
    }
}

private const val TOOL_DETAIL_SHOWN = 20_000

/**
 * A phone call in the chat: one line where it began, saying how long it went on, in place of
 * everything said in it. Tapped, what was said ([said]: anything was), and a way to delete it.
 */
@Composable
private fun CallNote(message: MessageEntity, said: Boolean, onOpen: () -> Unit) {
    val palette = LocalGlassPalette.current
    val record = remember(message.content) { CallRecords.decode(message.content) } ?: return
    val talked = record.talkedMs
    val text = CallRecords.label(record)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        GlassSurface(
            modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onOpen),
            style = palette.notice,
            shape = GlassShape.Capsule,
            contentPadding = PaddingValues(start = 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Call, contentDescription = null, tint = palette.accentContent, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(text, color = palette.contentSecondary, fontSize = 13.sp)
                if (talked != null && said) {
                    Spacer(Modifier.width(6.dp))
                    Text("看说了什么", color = palette.accentContent, fontSize = 12.sp)
                }
            }
        }
    }
}

/** What was said in a call, in order, with a way to delete the call and all of it. */
@Composable
private fun CallTranscript(
    record: MessageEntity,
    lines: List<MessageEntity>,
    aiName: String,
    userName: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onFavorite: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    var deleting by remember { mutableStateOf(false) }
    val talked = remember(record.content) { CallRecords.decode(record.content)?.talkedMs }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deleting) "删除这通电话？" else "电话里说的") },
        text = {
            if (deleting) {
                Text("电话里说的话会一起删掉，${aiName}也就不记得这通电话了。")
            } else {
                Column(
                    Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        Dates.chatStamp(record.createdAt) + (talked?.let { " · 通话 ${CallRecords.clock(it)}" } ?: ""),
                        color = palette.contentSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    var any = false
                    for (m in lines) {
                        val note = m.note
                        when {
                            (m.role == "user" || m.role == "assistant") && m.content.isNotBlank() -> {
                                any = true
                                Text(
                                    if (m.role == "user") userName else aiName,
                                    color = palette.accentContent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(m.content, fontSize = 15.sp, lineHeight = 22.sp)
                                Spacer(Modifier.height(10.dp))
                            }
                            m.role == "tool" && !note.isNullOrBlank() -> {
                                Text("（$note）", color = palette.contentSecondary, fontSize = 12.sp)
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                    if (!any) Text("这通电话里没说上话。")
                }
            }
        },
        confirmButton = {
            if (deleting) {
                TextButton(onClick = onDelete) { Text("删除", color = palette.error) }
            } else {
                TextButton(onClick = onDismiss) { Text("关上") }
            }
        },
        dismissButton = {
            if (deleting) {
                TextButton(onClick = { deleting = false }) { Text("算了") }
            } else {
                Row {
                    if (lines.any(FavoriteContent::eligible)) TextButton(onClick = onFavorite) { Text("收藏文字") }
                    TextButton(onClick = { deleting = true }) { Text("删除") }
                }
            }
        },
    )
}

/**
 * The model asking to see a little secret. Only the person ever sees this card, so it can
 * show which entry (date and title) even though the model was never told the title.
 */
@Composable
private fun RequestCard(message: MessageEntity, aiName: String, enabled: Boolean, onAnswer: (Boolean) -> Unit) {
    val palette = LocalGlassPalette.current
    val request = remember(message.content) { SecretRequests.decode(message.content) } ?: return
    val who = aiName.ifBlank { "TA" }
    GlassSurface(
        modifier = Modifier
            .padding(start = if (LocalFaces.current != null) AvatarSlot else 0.dp)
            .widthIn(max = bubbleMaxWidth()),
        style = palette.bubble,
        shape = GlassShape.Rounded(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = palette.accentContent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("${who}想看你的小秘密", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                Dates.monthDay(LocalDate.ofEpochDay(request.day)) + " · " + request.title.ifBlank { "没有标题" },
                color = palette.contentSecondary,
                fontSize = 13.sp,
            )
            if (request.reason.isNotBlank()) {
                Text("「${request.reason}」", color = palette.content, fontSize = 15.sp, lineHeight = 21.sp)
            }
            when (request.status) {
                SecretRequest.PENDING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Pill("给${who}看", accent = true, enabled = enabled) { onAnswer(true) }
                        Pill("不给", accent = false, enabled = enabled) { onAnswer(false) }
                    }
                    Text("给看只是这一次，日记还是锁着的。", color = palette.contentSecondary, fontSize = 12.sp)
                }
                SecretRequest.GRANTED -> Text("给${who}看了", color = palette.contentSecondary, fontSize = 13.sp)
                SecretRequest.DECLINED -> Text("没给${who}看", color = palette.contentSecondary, fontSize = 13.sp)
                else -> Text("这个小秘密已经不在了", color = palette.contentSecondary, fontSize = 13.sp)
            }
        }
    }
}

/** A plain pill, not glass: it sits on a glass card, where glass would look like a hole. */
@Composable
private fun Pill(text: String, accent: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .background(if (accent) palette.accent else palette.content.copy(alpha = 0.08f), CircleShape)
            .clickable(enabled = enabled, interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (accent) Color.White else palette.content, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TypingDots() {
    val palette = LocalGlassPalette.current
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(520, delayMillis = i * 160), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).alpha(a).background(palette.content, CircleShape))
        }
    }
}

/**
 * One piece of glass holds everything: the picture button, the text, and send. While a
 * reply is being written, an empty box offers stop instead; with something typed it is
 * still send, since the person can go on talking. The message being quoted and pictures
 * waiting to go sit above the text, inside the same glass. A round send button beside the
 * field would be a second glass pane, rendered offscreen on every frame, for one button.
 */
@Composable
private fun ChatInputBar(
    backdrop: Backdrop,
    type: ChatType,
    text: String,
    onTextChange: (String) -> Unit,
    attachments: List<MessageImage>,
    attaching: Boolean,
    onPick: () -> Unit,
    onRemove: (MessageImage) -> Unit,
    quote: String?,
    onDropQuote: () -> Unit,
    focus: FocusRequester,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    share: FeedShare? = null,
    onRemoveShare: (() -> Unit)? = null,
    onOpenShareImage: (String) -> Unit = {},
    editing: Boolean = false,
    editHint: String = "",
    onCancelEdit: () -> Unit = {},
    sendEnabled: Boolean = true,
    recording: Boolean = false,
    onVoiceStart: () -> Boolean = { false },
    onVoiceMove: (Boolean) -> Unit = {},
    onVoiceEnd: (Boolean) -> Unit = {},
    drawerOpen: Boolean = false,
    onDrawer: () -> Unit = {},
    onFieldFocus: () -> Unit = {},
    drawer: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    val c = appContainer()
    val canSend = text.isNotBlank() || attachments.isNotEmpty() || share != null
    val action = when {
        busy && !canSend && !editing -> ChatComposerAction.Stop
        !canSend && !editing -> ChatComposerAction.Mic
        else -> ChatComposerAction.Send
    }
    ChatComposer(backdrop, type, text, onTextChange, focus, action,
        onAction = if (action == ChatComposerAction.Stop) onStop else onSend,
        onLeading = onPick, leadingEnabled = !editing && attachments.size < MAX_ATTACHMENTS,
        readOnly = editing && busy, onFieldFocus = onFieldFocus,
        drawerOpen = drawerOpen, onDrawer = onDrawer, drawerEnabled = !editing,
        actionEnabled = action != ChatComposerAction.Send || sendEnabled && canSend,
        actionLabel = when (action) {
            ChatComposerAction.Mic -> "按住说话"
            ChatComposerAction.Stop -> "停止"
            ChatComposerAction.Send -> if (editing) "修改并重新回答" else "发送"
        },
        recording = recording, onVoiceStart = onVoiceStart, onVoiceMove = onVoiceMove, onVoiceEnd = onVoiceEnd,
        modifier = modifier) {
        if (editing) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(editHint, color = palette.contentSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2)
                Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onCancelEdit), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "取消编辑", tint = palette.contentSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
        // The stickers, in the same glass as the rest, where the keyboard would otherwise be.
        if (share != null) FeedShareCard(share, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp), onRemove = onRemoveShare, onOpenImage = onOpenShareImage)
        if (drawerOpen) drawer()
        if (quote != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp)
                    .background(palette.content.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    quote,
                    color = palette.contentSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !editing, onClick = onDropQuote),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "不引用了", tint = palette.contentSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (attachments.isNotEmpty() || attaching) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { img -> AttachmentThumb(c.images.file(img.file), if (editing) null else ({ onRemove(img) })) }
                if (attaching) {
                    Box(Modifier.size(64.dp).background(palette.content.copy(alpha = 0.08f), RoundedCornerShape(14.dp)))
                }
            }
        }
    }
}

@Composable
private fun AttachmentThumb(file: File, onRemove: (() -> Unit)?) {
    Box(Modifier.size(64.dp)) {
        AsyncImage(
            model = file,
            contentDescription = "要发的图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
        )
        if (onRemove != null) Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(22.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "去掉这张", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun NoKeyCard(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    GlassSurface(modifier = modifier, shape = GlassShape.Rounded(28.dp), contentPadding = PaddingValues(24.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有接上模型", color = palette.content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            Text(
                "在设置里填一个 API 地址和 Key（DeepSeek、OpenAI 这类兼容接口都可以），就能开始聊了。",
                color = palette.contentSecondary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(16.dp))
            GlassButton(
                onClick = onOpenSettings,
                backdrop = com.cleo.cleos.glass.LocalWallpaperBackdrop.current,
                style = palette.accentSurface,
                contentColor = Color.White,
            ) {
                Text("去设置", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
