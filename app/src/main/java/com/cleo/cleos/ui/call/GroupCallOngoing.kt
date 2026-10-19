package com.cleo.cleos.ui.call

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.ai.CallPhase
import com.cleo.cleos.ai.GroupCallState
import com.cleo.cleos.glass.*
import kotlin.math.roundToInt

internal fun groupCallStatus(st: GroupCallState): String {
    val name = st.members.firstOrNull { it.companionId == st.speakerId }?.name ?: "TA"
    return when {
        st.phase == CallPhase.Ended -> "通话已结束"
        st.held -> "声音被其他应用占用，正在等候"
        st.phase == CallPhase.Ringing -> "正在接通…"
        st.phase == CallPhase.Thinking && st.speakerId == null -> "正在听清你说的话…"
        st.phase == CallPhase.Thinking && st.preparingVoice -> "${name}在准备声音…"
        st.phase == CallPhase.Thinking -> "${name}在想…"
        st.phase == CallPhase.Speaking -> "${name}在说"
        st.muted -> "话筒已静音"
        st.phase == CallPhase.Hearing -> "在听你说"
        else -> "说句话，停一下大家就会接话"
    }
}

/** An overlay, never a layout slot: minimizing a call must not resize the page underneath. */
@Composable
internal fun GroupCallOngoing(st: GroupCallState, onOpen: () -> Unit) {
    val palette = LocalGlassPalette.current
    // Fractions keep a dragged capsule on screen when the keyboard or orientation changes.
    var horizontal by rememberSaveable(st.session) { mutableFloatStateOf(1f) }
    var vertical by rememberSaveable(st.session) { mutableFloatStateOf(0.18f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val status = groupCallStatus(st)
    BoxWithConstraints(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
        .padding(12.dp)) {
        val maxX = (constraints.maxWidth - size.width).coerceAtLeast(0).toFloat()
        val maxY = (constraints.maxHeight - size.height).coerceAtLeast(0).toFloat()
        GlassButton(onOpen, LocalWallpaperBackdrop.current,
            modifier = Modifier
                .offset { IntOffset((horizontal * maxX).roundToInt(), (vertical * maxY).roundToInt()) }
                .widthIn(max = 144.dp).heightIn(min = 48.dp)
                .onSizeChanged { size = it }
                .pointerInput(st.session, maxX, maxY) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        horizontal = if (maxX > 0) (horizontal + delta.x / maxX).coerceIn(0f, 1f) else horizontal
                        vertical = if (maxY > 0) (vertical + delta.y / maxY).coerceIn(0f, 1f) else vertical
                    }
                }
                .semantics { contentDescription = "返回群通话"; stateDescription = "${st.title}，$status" },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Call, null, tint = palette.content, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("群通话", color = palette.content, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1)
                    Text(status, color = palette.contentSecondary, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = palette.contentSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
