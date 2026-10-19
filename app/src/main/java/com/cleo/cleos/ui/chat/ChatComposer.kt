package com.cleo.cleos.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cleo.cleos.glass.*

internal enum class ChatComposerAction { Mic, Send, Stop }

private val ComposerHeight = 50.dp

/** The same pane, typography and control sizes in private and group chats. */
@Composable
internal fun ChatComposer(
    backdrop: Backdrop,
    type: ChatType,
    text: String,
    onTextChange: (String) -> Unit,
    focus: FocusRequester,
    action: ChatComposerAction,
    onAction: () -> Unit,
    onLeading: () -> Unit,
    onDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector = Icons.Rounded.AddPhotoAlternate,
    leadingLabel: String = "发图片",
    leadingEnabled: Boolean = true,
    leadingExpanded: Boolean? = null,
    fieldLabel: String = "输入消息",
    placeholder: String = "说点什么…",
    fieldEnabled: Boolean = true,
    readOnly: Boolean = false,
    onFieldFocus: () -> Unit = {},
    drawerOpen: Boolean = false,
    drawerEnabled: Boolean = true,
    drawerLabel: String = if (drawerOpen) "收起表情包" else "表情包",
    actionEnabled: Boolean = true,
    actionLabel: String = when (action) {
        ChatComposerAction.Mic -> "按住说话"
        ChatComposerAction.Send -> "发送"
        ChatComposerAction.Stop -> "停止"
    },
    recording: Boolean = false,
    onVoiceStart: () -> Boolean = { false },
    onVoiceMove: (Boolean) -> Unit = {},
    onVoiceEnd: (Boolean) -> Unit = {},
    header: @Composable ColumnScope.() -> Unit = {},
) {
    val palette = LocalGlassPalette.current
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = ComposerHeight)
        .liquidGlass(backdrop, palette.input, GlassShape.Rounded(ComposerHeight / 2))) {
        header()
        // Button squares remain centred in a one-line capsule, and stay at the bottom as text grows.
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(ComposerHeight), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp).clip(CircleShape)
                    .semantics { leadingExpanded?.let { stateDescription = if (it) "已展开" else "已收起" } }
                    .clickable(enabled = leadingEnabled, onClick = onLeading), contentAlignment = Alignment.Center) {
                    Icon(leadingIcon, leadingLabel, tint = palette.contentSecondary.copy(alpha = if (leadingEnabled) 1f else .35f),
                        modifier = Modifier.size(24.dp))
                }
            }
            Box(Modifier.weight(1f).heightIn(min = ComposerHeight).padding(end = 4.dp, top = 13.dp, bottom = 13.dp),
                contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(placeholder, color = palette.contentSecondary, style = type.body)
                BasicTextField(text, onTextChange, enabled = fieldEnabled, readOnly = readOnly,
                    textStyle = type.body.copy(color = palette.content), cursorBrush = SolidColor(palette.accentContent), maxLines = 6,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        .onFocusChanged { if (it.isFocused) onFieldFocus() }.semantics { contentDescription = fieldLabel })
            }
            Box(Modifier.size(width = 40.dp, height = ComposerHeight), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp).clip(CircleShape)
                    .background(if (drawerOpen) palette.content.copy(alpha = .1f) else Color.Transparent)
                    .clickable(enabled = drawerEnabled, onClick = onDrawer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.EmojiEmotions, drawerLabel,
                        tint = (if (drawerOpen) palette.accentContent else palette.contentSecondary).copy(alpha = if (drawerEnabled) 1f else .35f),
                        modifier = Modifier.size(24.dp))
                }
            }
            Box(Modifier.size(ComposerHeight), contentAlignment = Alignment.Center) {
                // Plain fills inside one glass pane; nesting glass circles makes them look like holes.
                val button = Modifier.size(38.dp).clip(CircleShape)
                when (action) {
                    ChatComposerAction.Mic -> ComposerMic(button, recording, actionEnabled, actionLabel, onVoiceStart, onVoiceMove, onVoiceEnd)
                    ChatComposerAction.Stop -> Box(button.background(palette.content.copy(alpha = .1f))
                        .clickable(enabled = actionEnabled, onClick = onAction), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Stop, actionLabel, tint = palette.content, modifier = Modifier.size(20.dp))
                    }
                    ChatComposerAction.Send -> Box(button.background(palette.accent.copy(alpha = if (actionEnabled) 1f else .35f))
                        .clickable(enabled = actionEnabled, onClick = onAction), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ArrowUpward, actionLabel, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerMic(button: Modifier, recording: Boolean, enabled: Boolean, label: String,
    onStart: () -> Boolean, onMove: (Boolean) -> Unit, onEnd: (Boolean) -> Unit) {
    val palette = LocalGlassPalette.current
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    val active by rememberUpdatedState(enabled)
    Box(button.background(if (recording) palette.accent else palette.content.copy(alpha = .1f))
        .semantics { contentDescription = label; if (!enabled) disabled() }
        .pointerInput(Unit) {
            val away = 72.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume()
                if (!active || !start()) return@awaitEachGesture
                var cancel = false
                var ended = false
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val up = down.position.y - change.position.y > away
                        if (up != cancel) { cancel = up; move(cancel) }
                        change.consume()
                    }
                    ended = true; end(cancel)
                } finally { if (!ended) end(true) }
            }
        }, contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Mic, null, tint = (if (recording) Color.White else palette.content).copy(alpha = if (enabled) 1f else .35f),
            modifier = Modifier.size(22.dp))
    }
}
