package com.cleo.cleos.ui.call

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.CallPhase
import com.cleo.cleos.data.CallRecords
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.*
import kotlinx.coroutines.delay

@Composable
internal fun GroupCallScreen(onMinimize: () -> Unit) {
    val c = appContainer()
    val state by c.groupCalls.state.collectAsStateWithLifecycle()
    val st = state ?: return
    val palette = LocalGlassPalette.current
    val ended = st.phase == CallPhase.Ended
    val captions = rememberCallCaptions(st.session, st.captions, ended) {
        st.members.minByOrNull { it.position }?.companionId
    }
    val compact = st.typing || captions.visible
    val canCut = st.phase in setOf(CallPhase.Thinking, CallPhase.Speaking)
    var draft by rememberSaveable(st.session) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeTyping() {
        keyboard?.hide()
        c.groupCalls.setTyping(st.session, false)
    }
    BackHandler(!ended, onBack = onMinimize)
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.answeredAt, ended) { while (!ended) { now = System.currentTimeMillis(); delay(500) } }
    val status = groupCallStatus(st) + when {
        !st.typing || st.held -> ""
        st.phase == CallPhase.Speaking -> " · 说完可发"
        st.phase == CallPhase.Thinking -> " · 稍后可发"
        else -> ""
    }
    CallPage(captions = captions, overlay = { wallpaper ->
        GlassIconButton(Icons.Rounded.KeyboardArrowDown, "收起群通话", onMinimize, wallpaper,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 14.dp, top = 8.dp),
            style = palette.topBar, enabled = !ended, size = 44.dp)
        if (!ended && st.answeredAt != null) {
            CallTranslationButton(captions, wallpaper,
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 14.dp, top = 8.dp))
            CallTranslationDialog(captions, st.captions, group = true, backdrop = wallpaper,
                modifier = Modifier.matchParentSize())
        }
    }, actions = { wallpaper ->
        CallInputActions(
            backdrop = wallpaper,
            typing = st.typing,
            onToggleTyping = if (st.answeredAt != null && !ended) {
                { if (st.typing) closeTyping() else c.groupCalls.setTyping(st.session, true) }
            } else null,
            interruptLabel = "打断这轮",
            onInterrupt = if (canCut) c.groupCalls::interrupt else null,
        )
    }, composer = { wallpaper ->
        if (st.typing && !ended) {
            Box(Modifier.padding(bottom = 8.dp)) {
                CallTextComposer(wallpaper, draft, { draft = it },
                    canSend = st.phase == CallPhase.Listening && !st.held,
                ) {
                    if (c.groupCalls.sendText(st.session, draft)) draft = ""
                }
            }
        }
    }, controls = { wallpaper ->
        CallControlButtons(wallpaper, st.muted, !ended, { c.groupCalls.mute(!st.muted) }, c.groupCalls::hangUp,
            hangUpDescription = "挂断群通话")
    }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)
            .then(if (!captions.visible) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 22.dp)) {
            Spacer(Modifier.height(if (compact) 8.dp else 34.dp))
            Text(st.title, color = palette.content, fontSize = 23.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 40.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            st.answeredAt?.let { Text(CallRecords.clock(((st.endedAt ?: now) - it).coerceAtLeast(0)),
                color = palette.contentSecondary, fontSize = 13.sp) }
            if (compact && st.members.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                st.members.forEach { member ->
                    Avatar(member.avatar, member.emoji ?: avatarLetter(member.name, "TA"), 38.dp)
                }
            }
            if (!compact) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                st.members.forEach { member ->
                    GlassSurface(Modifier.weight(1f), style = palette.card, contentPadding = PaddingValues(16.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(member.avatar, member.emoji ?: avatarLetter(member.name, "TA"), 64.dp)
                            Text(member.name, color = palette.content, fontSize = 15.sp)
                            Text(if (st.speakerId == member.companionId) when {
                                st.phase == CallPhase.Speaking -> "正在说"
                                st.preparingVoice -> "准备声音"
                                else -> "正在想"
                            } else "在这里",
                                color = palette.contentSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
            Text(status, color = palette.contentSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = "群通话状态：$status" })
            st.problem?.let { Text(it, color = palette.error, fontSize = 13.sp) }
            if (captions.visible) {
                CallCaptionList(captions, st.captions, Modifier.weight(1f))
            } else if (!ended && st.answeredAt != null) {
                CallCaptionHint()
            }
            if (!compact) Text("轮流发言 · 通话文字保留在本群", color = palette.contentSecondary, fontSize = 12.sp)
        }
    }
}
