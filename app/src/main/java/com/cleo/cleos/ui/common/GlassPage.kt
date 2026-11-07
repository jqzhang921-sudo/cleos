package com.cleo.cleos.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.LocalGlassChrome
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.LocalWallpaperBackdrop
import com.cleo.cleos.glass.WallpaperOverscan
import com.cleo.cleos.glass.backdropSource
import com.cleo.cleos.glass.liquidGlass
import com.cleo.cleos.glass.rememberBackdrop

/** Height the floating top bar takes below the status bar; scrolling content pads by this. */
val TopBarHeight = 64.dp

/**
 * The shape of every screen: [body] scrolls underneath, [overlay] floats on top as glass.
 *
 * The body is recorded (over a copy of the wallpaper that only the recording gets) so the
 * overlay's glass can refract what scrolls under it. Glass inside the body can only look
 * at the plain wallpaper (LocalWallpaperBackdrop), because the body cannot contain glass
 * reading itself.
 */
@Composable
fun GlassPage(
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(Backdrop) -> Unit = {},
    body: @Composable BoxScope.() -> Unit,
) {
    val page = rememberBackdrop()
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .backdropSource(page, behind = LocalWallpaperBackdrop.current, overscan = WallpaperOverscan),
            content = body,
        )
        overlay(page)
    }
}

/**
 * Glass buttons on either side, the title in a glass capsule in the middle.
 * Buttons are passed in as slots so each screen picks its own.
 */
@Composable
fun GlassTopBar(
    title: String,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    /** Makes the title a button; [titleMenu] is drawn next to it, e.g. a menu it opens. */
    onTitleClick: (() -> Unit)? = null,
    titleMenu: @Composable () -> Unit = {},
    barHeight: Dp = TopBarHeight,
) {
    val palette = LocalGlassPalette.current
    CompositionLocalProvider(LocalGlassChrome provides palette.topBar) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(barHeight)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = leading)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .liquidGlass(backdrop, palette.topBarTitle, GlassShape.Capsule)
                    .then(
                        if (onTitleClick == null) Modifier else Modifier.clickable(interactionSource = null, indication = null, onClick = onTitleClick),
                    )
                    .padding(horizontal = 18.dp, vertical = if (subtitle == null) 10.dp else 6.dp),
            ) {
                androidx.compose.foundation.layout.Column {
                    Text(
                        title,
                        color = palette.content,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(subtitle, color = palette.contentSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                titleMenu()
            }
        }
        Spacer(Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
    }
}
