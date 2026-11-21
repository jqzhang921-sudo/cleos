package com.cleo.cleos.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleo.cleos.ai.CallInput
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.ComposerHeight
import com.cleo.cleos.glass.GlassButton
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.composerGlass

@Composable
internal fun CallInputActions(
    backdrop: Backdrop,
    typing: Boolean,
    onToggleTyping: (() -> Unit)?,
    interruptLabel: String,
    onInterrupt: (() -> Unit)?,
) {
    if (onToggleTyping == null && onInterrupt == null) return
    val palette = LocalGlassPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.weight(1.55f), contentAlignment = Alignment.Center) {
            if (onToggleTyping != null) GlassButton(
                onClick = onToggleTyping,
                backdrop = backdrop,
                modifier = Modifier.heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (typing) Icons.Rounded.RecordVoiceOver else Icons.Rounded.Keyboard,
                        contentDescription = null, modifier = Modifier.size(17.dp))
                    Text(if (typing) "切回语音" else "打字回复", style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center)
                }
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (onInterrupt != null) TextButton(onInterrupt, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(interruptLabel, color = palette.content, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
internal fun CallTextComposer(
    backdrop: Backdrop,
    draft: String,
    onDraftChange: (String) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val body = MaterialTheme.typography.bodyLarge
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Row(Modifier.composerGlass(backdrop), verticalAlignment = Alignment.Bottom) {
        BasicTextField(
            value = draft,
            onValueChange = { if (it.length <= CallInput.MAX_TEXT) onDraftChange(it) },
            modifier = Modifier.weight(1f).heightIn(min = ComposerHeight).focusRequester(focus)
                .semantics { contentDescription = "输入通话回复" },
            textStyle = body.copy(color = palette.content),
            cursorBrush = SolidColor(palette.accentContent),
            minLines = 1,
            maxLines = 3,
            decorationBox = { field ->
                Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 13.dp, bottom = 13.dp),
                    contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) Text(
                        "说点什么…",
                        color = palette.contentSecondary,
                        style = body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    field()
                }
            },
        )
        val enabled = canSend && draft.isNotBlank()
        IconButton(onSend, enabled = enabled, modifier = Modifier.size(ComposerHeight)) {
            Box(
                Modifier.size(38.dp).background(
                    if (enabled) palette.accent else palette.content.copy(alpha = 0.07f),
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ArrowUpward, "发送",
                    tint = if (enabled) Color.White else palette.contentSecondary,
                    modifier = Modifier.size(22.dp))
            }
        }
    }
}
