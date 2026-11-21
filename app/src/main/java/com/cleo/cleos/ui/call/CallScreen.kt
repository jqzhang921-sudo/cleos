package com.cleo.cleos.ui.call

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.CallPhase
import com.cleo.cleos.ai.CallState
import com.cleo.cleos.data.CallRecords
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.avatarLetter
import kotlinx.coroutines.delay

/**
 * A call with a TA (ai/Call.kt): their picture with rings that move with whoever is talking, how
 * long the call has gone on, and what is being said as words under it, for when the voice isn't
 * clear. An explicit button cuts the TA off; back asks before hanging up. The screen stays on: a call
 * on speaker is looked at.
 */
@Composable
fun CallScreen(onMinimizeGroup: () -> Unit) {
    val c = appContainer()
    val groupCall by c.groupCalls.state.collectAsStateWithLifecycle()
    if (groupCall != null) { GroupCallScreen(onMinimizeGroup); return }
    val call by c.calls.state.collectAsStateWithLifecycle()
    val st = call ?: return
    val level by c.calls.level.collectAsStateWithLifecycle()
    val palette = LocalGlassPalette.current
    var askHangUp by remember { mutableStateOf(false) }
    var draft by rememberSaveable(st.session) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeTyping() {
        keyboard?.hide()
        c.calls.setTyping(st.session, false)
    }
    val ended = st.phase == CallPhase.Ended
    val captions = rememberCallCaptions(st.session, st.captions, ended) {
        c.db.conversations().get(st.conversationId)?.companionId
    }
    val compact = st.typing || captions.visible
    BackHandler(enabled = !ended) { if (st.typing) closeTyping() else askHangUp = true }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.answeredAt, st.endedAt) {
        while (st.answeredAt != null && st.endedAt == null) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val talked = st.answeredAt?.let { (st.endedAt ?: now) - it }
    val canCut = st.canInterrupt && (st.phase == CallPhase.Thinking || st.phase == CallPhase.Speaking)

    CallPage(
        captions = captions,
        overlay = { wallpaper ->
            if (!ended && st.answeredAt != null) {
                CallTranslationButton(captions, wallpaper,
                    Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 14.dp))
                CallTranslationDialog(captions, st.captions, group = false, backdrop = wallpaper,
                    modifier = Modifier.matchParentSize())
            }
        },
        actions = { wallpaper ->
            CallInputActions(
                backdrop = wallpaper,
                typing = st.typing,
                onToggleTyping = if (st.answeredAt != null && !ended) {
                    { if (st.typing) closeTyping() else c.calls.setTyping(st.session, true) }
                } else null,
                interruptLabel = "打断",
                onInterrupt = if (canCut) c.calls::interrupt else null,
            )
        },
        composer = { wallpaper ->
            if (st.typing && !ended) {
                Box(Modifier.padding(bottom = 8.dp)) {
                    CallTextComposer(wallpaper, draft, { draft = it },
                        canSend = st.phase == CallPhase.Listening && !st.held,
                    ) {
                        if (c.calls.sendText(st.session, draft)) draft = ""
                    }
                }
            }
        },
        controls = { wallpaper ->
            CallControlButtons(wallpaper, st.muted, !ended, { c.calls.mute(!st.muted) }, c.calls::hangUp)
        },
    ) {
        val tight = maxHeight < 290.dp
        val compactHeader = compact || tight
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 28.dp)
                .then(if (!captions.visible) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (compactHeader) 8.dp else 72.dp))
            val avatarSize = when {
                tight -> 68.dp
                compact -> 88.dp
                else -> 116.dp
            }
            Rings(st, level, avatarSize, compactHeader) {
                Avatar(st.avatar, st.avatarEmoji ?: avatarLetter(st.name, "TA"), avatarSize)
            }
            Spacer(Modifier.height(if (compactHeader) 4.dp else 22.dp))
            Text(st.name.ifEmpty { "TA" }, color = palette.content,
                fontSize = if (compactHeader) 21.sp else 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(if (compactHeader) 4.dp else 8.dp))
            Text(status(st, talked), color = palette.contentSecondary, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            st.problem?.let { Text(it, color = palette.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            if (captions.visible) {
                CallCaptionList(captions, st.captions, Modifier.weight(1f).padding(top = 8.dp))
            } else if (!ended && st.answeredAt != null) {
                CallCaptionHint(Modifier.padding(top = 14.dp))
            }
        }
    }
    if (askHangUp) {
        AlertDialog(
            onDismissRequest = { askHangUp = false },
            title = { Text("挂断电话？") },
            confirmButton = {
                TextButton(onClick = {
                    askHangUp = false
                    c.calls.hangUp()
                }) { Text("挂断", color = palette.error) }
            },
            dismissButton = { TextButton(onClick = { askHangUp = false }) { Text("继续打") } },
        )
    }
}

/** How long, and what is happening: 03:12 · 在听. */
private fun status(st: CallState, talked: Long?): String {
    val what = when (st.phase) {
        CallPhase.Ringing -> return "正在呼叫…"
        CallPhase.Ended -> return if (talked != null) "通话结束 ${CallRecords.clock(talked)}" else "已挂断"
        CallPhase.Listening -> when {
            st.held -> "别的应用在出声，先等等"
            st.typing -> "打字模式 · 麦克风已暂停"
            st.muted -> "已静音"
            else -> "在听"
        }
        CallPhase.Hearing -> "在听你说"
        CallPhase.Thinking -> if (st.typing) "正在想 · 稍后可发" else "……"
        CallPhase.Speaking -> "${st.name.ifEmpty { "TA" }}在说" + if (st.typing) " · 说完可发" else ""
    }
    return if (talked != null) "${CallRecords.clock(talked)} · $what" else what
}

/**
 * Rings round the picture: while it rings, a slow pulse; then they swell with the loudness of
 * whoever is talking.
 */
@Composable
private fun Rings(st: CallState, level: Float, avatarSize: Dp, compact: Boolean, content: @Composable () -> Unit) {
    val palette = LocalGlassPalette.current
    val loud by animateFloatAsState(level, tween(120), label = "level")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val beat by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "beat",
    )
    val ringing = st.phase == CallPhase.Ringing
    val reach = if (compact) 8.dp else 36.dp
    Box(Modifier.size(avatarSize + (reach + 6.dp) * 2), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = avatarSize.toPx() / 2 + 6.dp.toPx()
            val swell = reach.toPx()
            val color = palette.accent
            if (ringing) {
                // Two rings going out one after the other, as long as it rings.
                for (k in 0..1) {
                    val t = (beat + k * 0.5f) % 1f
                    drawCircle(color.copy(alpha = 0.45f * (1 - t)), radius = base + swell * t, style = Stroke(2.dp.toPx()))
                }
            } else if (st.phase != CallPhase.Ended) {
                drawCircle(color.copy(alpha = 0.16f + 0.2f * loud), radius = base + swell * loud * 0.9f)
                drawCircle(color.copy(alpha = 0.10f + 0.12f * loud), radius = base + swell * loud * 0.5f + 4.dp.toPx())
            }
        }
        content()
    }
}
