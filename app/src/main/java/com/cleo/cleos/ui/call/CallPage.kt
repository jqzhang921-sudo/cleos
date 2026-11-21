package com.cleo.cleos.ui.call

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.LocalWallpaperBackdrop

private val HangUpTint = Color(0xFFD72635).copy(alpha = 0.82f)

@Composable
internal fun CallPage(
    captions: CallCaptionUi,
    actions: @Composable (Backdrop) -> Unit,
    composer: @Composable (Backdrop) -> Unit,
    controls: @Composable (Backdrop) -> Unit,
    overlay: @Composable BoxScope.(Backdrop) -> Unit = {},
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    val wallpaper = LocalWallpaperBackdrop.current
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > WindowInsets.navigationBars.getBottom(density)
    val bottom = WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        .windowInsetsPadding(bottom)) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().clickable(
                    interactionSource = null,
                    indication = null,
                    onClickLabel = if (captions.visible) "隐藏字幕" else "显示字幕",
                ) { captions.visible = !captions.visible },
                content = content,
            )
            actions(wallpaper)
            composer(wallpaper)
            // Leave the call controls at their normal position instead of moving them beside the IME.
            if (!keyboardOpen) controls(wallpaper)
        }
        overlay(wallpaper)
    }
}

@Composable
internal fun CallControlButtons(
    backdrop: Backdrop,
    muted: Boolean,
    enabled: Boolean,
    onMute: () -> Unit,
    onHangUp: () -> Unit,
    hangUpDescription: String = "挂断",
) {
    val palette = LocalGlassPalette.current
    val glass = palette.topBar
    val redGlass = glass.copy(tint = HangUpTint)
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GlassIconButton(
                if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                if (muted) "取消静音" else "静音",
                onMute, backdrop, style = glass, enabled = enabled,
                tint = if (muted) palette.error else palette.content, size = 60.dp,
            )
            Text(if (muted) "已静音" else "静音", color = palette.contentSecondary, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GlassIconButton(
                Icons.Rounded.CallEnd, hangUpDescription, onHangUp, backdrop,
                style = redGlass, enabled = enabled, tint = Color.White, size = 60.dp,
            )
            Text("挂断", color = palette.contentSecondary, fontSize = 12.sp)
        }
    }
}
