package com.cleo.cleos.ui.call

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val canCut = st.phase in setOf(CallPhase.Thinking, CallPhase.Speaking)
    BackHandler(!ended, onBack = onMinimize)
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.answeredAt, ended) { while (!ended) { now = System.currentTimeMillis(); delay(500) } }
    val name = st.members.firstOrNull { it.companionId == st.speakerId }?.name ?: "TA"
    val status = groupCallStatus(st)
    GlassPage(overlay = { page ->
        GlassIconButton(Icons.Rounded.KeyboardArrowDown, "收起群通话", onMinimize, page,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 14.dp, top = 8.dp),
            enabled = !ended, size = 44.dp)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (canCut) TextButton({ c.groupCalls.interrupt() }) { Text("打断这轮，我来说", color = palette.content) }
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                GlassIconButton(if (st.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    if (st.muted) "取消静音" else "静音", { c.groupCalls.mute(!st.muted) }, page, enabled = !ended, size = 60.dp)
                GlassIconButton(Icons.Rounded.CallEnd, "挂断群通话", { c.groupCalls.hangUp() }, page,
                    tint = palette.error, enabled = !ended, size = 68.dp)
            }
            Text("轮流发言 · 通话文字保留在本群", color = palette.contentSecondary, fontSize = 12.sp)
        }
    }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)
            .clickable(interactionSource = null, indication = null, enabled = canCut) { c.groupCalls.interrupt() }
            .verticalScroll(rememberScrollState()).padding(bottom = 220.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Spacer(Modifier.height(34.dp))
            Text(st.title, color = palette.content, fontSize = 23.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 40.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            st.answeredAt?.let { Text(CallRecords.clock(((st.endedAt ?: now) - it).coerceAtLeast(0)),
                color = palette.contentSecondary, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
            Text(status, color = palette.contentSecondary, fontSize = 14.sp,
                modifier = Modifier.semantics { contentDescription = "群通话状态：$status" })
            if (st.heard != null || st.saying != null) GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    st.heard?.let { Text("你：$it", color = palette.contentSecondary, fontSize = 14.sp) }
                    st.saying?.let { Text("$name：$it", color = palette.content, fontSize = 17.sp, lineHeight = 26.sp) }
                }
            }
            st.problem?.let { Text(it, color = palette.error, fontSize = 13.sp) }
        }
    }
}
