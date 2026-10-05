package com.cleo.cleos.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.Opening
import com.cleo.cleos.glass.GlassTab
import com.cleo.cleos.glass.GlassTabBar
import com.cleo.cleos.glass.LocalWallpaperBackdrop
import com.cleo.cleos.glass.WallpaperOverscan
import com.cleo.cleos.glass.backdropSource
import com.cleo.cleos.glass.rememberBackdrop
import com.cleo.cleos.ui.chat.ChatTab
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.diary.DiaryTab
import com.cleo.cleos.ui.home.HomeTab
import com.cleo.cleos.ui.settings.SettingsPage
import com.cleo.cleos.ui.todo.TodoTab

/**
 * Space the floating tab bar takes above the navigation bar (its 14dp overhang, the 64dp
 * bar, and a gap), which each tab reserves at the bottom of its content.
 */
private val TabBarReserve = 88.dp

/**
 * Whether the tab page reading it is the one on screen. The pages stay composed while another
 * shows (MainScreen), so what used to stop because leaving a tab threw its page away (the chat
 * counting as looked at, a voice message playing, "typing") has to stop on this instead.
 */
val LocalPageShown = compositionLocalOf { true }

/** How long after the app comes up the pages not yet opened are built, one after another, off screen. */
private const val WARM_UP_MS = 600L

@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenSettingsPage: (SettingsPage) -> Unit,
    onOpenConversations: () -> Unit,
    onOpenDiaryEntry: (id: Long, secret: Boolean) -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenLetters: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenFavorites: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // A notification about a conversation was tapped: the chat, which already shows it.
    val c = appContainer()
    val opening by c.opening.collectAsStateWithLifecycle()
    LaunchedEffect(opening) {
        if (opening is Opening.Chat) {
            tab = 0
            c.opening.value = null
        }
    }
    // Tab pages slide across rather than cutting. `from` is the page being left and
    // `slide` runs 0..1; while they differ both pages exist, and only then.
    var from by remember { mutableIntStateOf(tab) }
    // A new one for every switch, at 0 already in the frame the tab changes. One kept across
    // switches still stood at 1 from the last slide in that frame (it was set back to 0 by the
    // effect below, which only runs after it): for one frame the new page was drawn where it
    // ends up, over everything, and then went back to slide in. The flash, caught on the phone
    // in a screen recording slowed down ten times.
    val slide = remember(tab) { Animatable(0f) }
    var pageWidth by remember { mutableIntStateOf(0) }
    // Every page, once built, stays built, the way WeChat's and Telegram's tabs do: switching
    // then only moves pages and never builds one. Built on the spot, a page slid in empty and
    // filled in a few frames later, when its diary or todos came back from the database: the
    // flash that was left after pages stopped being rebuilt mid-slide. The ones not opened yet
    // are built a moment after the app comes up, so the first visit is as smooth as the next.
    var alive by remember { mutableStateOf(setOf(tab)) }
    LaunchedEffect(Unit) {
        for (index in 0..3) {
            delay(WARM_UP_MS)
            alive = alive + index
        }
    }
    val focus = LocalFocusManager.current
    LaunchedEffect(tab) {
        val target = tab
        alive = alive + target
        // A page left no longer goes away, and a field focused on it would keep the keyboard up over the next.
        focus.clearFocus()
        if (from == target) return@LaunchedEffect
        // Two frames for the page coming in to build and draw itself off to the side, where
        // nobody sees it, before anything moves: its first composition and its glass are the
        // heavy part, and inside the slide they cost a frame the eye catches.
        withFrameNanos { }
        withFrameNanos { }
        slide.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        // Left until the end on purpose: it is what keeps the outgoing page alive, and
        // if this coroutine is cancelled by another tap it stays put, so the next slide
        // starts from the page actually on screen instead of jumping.
        from = target
    }

    val holder = rememberSaveableStateHolder()
    val page = rememberBackdrop()
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    val bottomInset = navBottom + TabBarReserve
    val tabs = remember {
        listOf(
            GlassTab("聊天", Icons.Outlined.ChatBubbleOutline, Icons.Rounded.ChatBubble),
            GlassTab("日记", Icons.Outlined.AutoStories, Icons.Rounded.AutoStories),
            GlassTab("待办", Icons.Outlined.TaskAlt, Icons.Rounded.TaskAlt),
            GlassTab("主页", Icons.Outlined.Home, Icons.Rounded.Home),
        )
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { pageWidth = it.width }
                .backdropSource(page, behind = LocalWallpaperBackdrop.current, overscan = WallpaperOverscan),
        ) {
            @Composable
            fun Page(index: Int, placed: Boolean, shift: () -> Float) {
                Box(
                    // ⚠️ Slid as a picture: the page stays where it rests as far as layout
                    // knows, and only its drawing is shifted, from its own layer (the page
                    // drawn once and moved as it is). It used to be moved by placing it: a
                    // real layout position each frame, so every piece of glass on it re-read
                    // the wallpaper under its new spot, and every piece crossing the edge of
                    // the screen built its effect anew (LiquidGlass clamps what it samples to
                    // the part on screen), dozens a frame on two pages: the frames dropped
                    // and the flash. The one thing given up: for the 0.3 s of the slide, the
                    // wallpaper seen through a bubble moves along with it. The top bar looks
                    // through at the chat itself, which moves with it, so it stays right.
                    //
                    // Reading the animation in the draw and nowhere else keeps the page out
                    // of recomposition and layout: it is only drawn somewhere else.
                    // A page off screen is measured but not placed, so nothing of it is drawn.
                    Modifier
                        .fillMaxSize()
                        .layout { measurable, constraints ->
                            val p = measurable.measure(constraints)
                            layout(p.width, p.height) {
                                if (placed) p.place(0, 0)
                            }
                        }
                        .drawWithContent { translate(left = shift() * pageWidth) { this@drawWithContent.drawContent() } }
                        .graphicsLayer(),
                ) {
                    holder.SaveableStateProvider(index) {
                        when (index) {
                            0 -> ChatTab(bottomInset, onOpenSettings, onOpenSettingsPage, onOpenConversations, onOpenImage)
                            1 -> DiaryTab(bottomInset, onOpenDiaryEntry)
                            2 -> TodoTab(bottomInset)
                            else -> HomeTab(bottomInset, onOpenSettings, onOpenLetters, onOpenMemory, onOpenFavorites)
                        }
                    }
                }
            }

            // Only these two are ever on screen: tab 0 to tab 3 slides once, it does not flip
            // through 1 and 2 — they would show for a few frames, each paying to build itself,
            // for a glimpse nobody asked for.
            //
            // Each page under its own key, wherever it stands in the list: the page on screen
            // before a slide is the same page during it, and the one that slid in is the same
            // page after. Drawn from two places in the code instead (one for still, two for
            // sliding), every start and end of a slide threw the page on screen away and built
            // it again, and for a frame its glass had nothing under it: the black flash.
            val dir = if (tab > from) 1f else -1f
            for (index in (alive + from + tab).sorted()) {
                key(index) {
                    CompositionLocalProvider(LocalPageShown provides (index == tab)) {
                        Page(index, placed = index == tab || index == from) {
                            when {
                                from == tab -> 0f
                                index == from -> -slide.value * dir
                                else -> (1f - slide.value) * dir
                            }
                        }
                    }
                }
            }
        }
        // The keyboard covers the bar anyway; sliding it away lets the input sit on the keys.
        AnimatedVisibility(
            visible = !keyboardOpen,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            GlassTabBar(
                tabs = tabs,
                selectedIndex = tab,
                onSelect = { tab = it },
                backdrop = page,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp)
                    .fillMaxWidth(),
            )
        }
    }
}
