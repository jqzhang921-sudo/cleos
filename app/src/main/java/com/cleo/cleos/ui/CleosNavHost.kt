package com.cleo.cleos.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.cleo.cleos.Opening
import com.cleo.cleos.ui.call.CallScreen
import com.cleo.cleos.ui.chat.ConversationsScreen
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.chat.SearchScreen
import com.cleo.cleos.ui.diary.DiaryEditorScreen
import com.cleo.cleos.ui.diary.ImageViewerScreen
import com.cleo.cleos.ui.lab.GlassLabScreen
import com.cleo.cleos.ui.letters.LetterScreen
import com.cleo.cleos.ui.letters.LettersScreen
import com.cleo.cleos.ui.memory.MemoryEditScreen
import com.cleo.cleos.ui.memory.MemoryScreen
import com.cleo.cleos.ui.settings.LoreEditScreen
import com.cleo.cleos.ui.settings.LoreScreen
import com.cleo.cleos.ui.settings.McpEditScreen
import com.cleo.cleos.ui.settings.PersonaScreen
import com.cleo.cleos.ui.settings.SettingsPage
import com.cleo.cleos.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
object MainRoute

@Serializable
object FeedRoute

/** [page]: a SettingsPage's name to open straight on, null for the list of them. */
@Serializable
data class SettingsRoute(val page: String? = null)

@Serializable
object LabRoute

@Serializable
object BubbleLabRoute

@Serializable
object ConversationsRoute

/** Finding what was said with the current TA. */
@Serializable
object SearchRoute

/** id 0 means a new entry; [secret] says whether a new one starts locked. */
@Serializable
data class DiaryRoute(val id: Long, val secret: Boolean = false)

@Serializable
data class ImageRoute(val file: String)

@Serializable
object LettersRoute

/** id 0 means a new letter to the current TA. */
@Serializable
data class LetterRoute(val id: Long)

@Serializable
object MemoryRoute

@Serializable
object FavoritesRoute

@Serializable
data class FavoriteRoute(val id: Long)

/** id 0 means a new memory, written by the person, for the current TA. */
@Serializable
data class MemoryEditRoute(val id: Long)

/** An MCP service to edit; an empty id adds one. */
@Serializable
data class McpEditRoute(val id: String)

/** A TA's persona, written on a page of its own. */
@Serializable
data class PersonaRoute(val companionId: Long)

/** A TA's 设定 (world book entries), reached from the settings' list of groups. */
@Serializable
object LoreRoute

/** One 设定 entry; id 0 means a new one, for the current TA. */
@Serializable
data class LoreEditRoute(val id: Long)

/** A call with a TA: up for as long as one is on, over whatever else was open. */
@Serializable
object CallRoute

/**
 * Screens cross-fade rather than slide. Glass samples what is behind it at the position
 * it was last laid out at; a screen sliding in via a transform moves without being laid
 * out again, so its glass would show the wallpaper from where the screen started.
 */
@Composable
fun CleosNavHost() {
    val nav = rememberNavController()
    // A tapped notification: back to the main screen, whose chat tab shows the conversation
    // (MainScreen takes it from there), or on to the letter.
    val c = appContainer()
    val opening by c.opening.collectAsStateWithLifecycle()
    LaunchedEffect(opening) {
        when (val o = opening) {
            is Opening.Chat -> nav.popBackStack(MainRoute, inclusive = false)
            is Opening.Diary -> {
                nav.navigate(DiaryRoute(o.id))
                c.opening.value = null
            }
            is Opening.Letter -> {
                nav.popBackStack(MainRoute, inclusive = false)
                nav.navigate(LetterRoute(o.id))
                c.opening.value = null
            }
            null -> Unit
        }
    }
    // A call on: its screen, whatever was open (a notification tapped meanwhile, the app opened again
    // from the launcher). Over: back to where the person was.
    val call by c.calls.state.collectAsStateWithLifecycle()
    val calling = call != null
    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(calling, entry) {
        val here = entry ?: return@LaunchedEffect
        val onCall = here.destination.hasRoute<CallRoute>()
        if (calling && !onCall) {
            nav.navigate(CallRoute) { launchSingleTop = true }
        } else if (!calling && onCall) {
            nav.popBackStack()
        }
    }
    NavHost(
        navController = nav,
        startDestination = MainRoute,
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) },
    ) {
        composable<MainRoute> {
            MainScreen(
                onOpenSettings = { nav.go(SettingsRoute()) },
                onOpenSettingsPage = { nav.go(SettingsRoute(it.name)) },
                onOpenConversations = { nav.go(ConversationsRoute) },
                onOpenFeed = { nav.go(FeedRoute) },
                onOpenDiaryEntry = { id, secret -> nav.go(DiaryRoute(id, secret)) },
                onOpenImage = { nav.go(ImageRoute(it)) },
                onOpenLetters = { nav.go(LettersRoute) },
                onOpenMemory = { nav.go(MemoryRoute) },
                onOpenFavorites = { nav.go(FavoritesRoute) },
            )
        }
        composable<FeedRoute> { com.cleo.cleos.ui.feed.FeedScreen(onBack = nav::back) }
        composable<LettersRoute> { LettersScreen(onBack = nav::back, onOpen = { nav.go(LetterRoute(it)) }) }
        composable<LetterRoute> { entry -> LetterScreen(entry.toRoute<LetterRoute>().id, onBack = nav::back) }
        composable<FavoritesRoute> {
            com.cleo.cleos.ui.favorites.FavoritesScreen(onBack = nav::back, onOpen = { nav.go(FavoriteRoute(it)) })
        }
        composable<FavoriteRoute> { entry ->
            com.cleo.cleos.ui.favorites.FavoriteScreen(entry.toRoute<FavoriteRoute>().id, onBack = nav::back,
                onFound = { nav.popBackStack(MainRoute, inclusive = false) }, onImage = { nav.go(ImageRoute(it)) })
        }
        composable<MemoryRoute> { MemoryScreen(onBack = nav::back, onOpen = { nav.go(MemoryEditRoute(it)) }) }
        composable<MemoryEditRoute> { entry -> MemoryEditScreen(entry.toRoute<MemoryEditRoute>().id, onBack = nav::back) }
        composable<SettingsRoute> { entry ->
            SettingsScreen(
                start = SettingsPage.of(entry.toRoute<SettingsRoute>().page),
                onBack = nav::back,
                onOpenLab = { nav.go(LabRoute) },
                onOpenBubbleLab = { nav.go(BubbleLabRoute) },
                onOpenMcp = { nav.go(McpEditRoute(it)) },
                onOpenPersona = { nav.go(PersonaRoute(it)) },
                onOpenLore = { nav.go(LoreRoute) },
            )
        }
        composable<PersonaRoute> { entry -> PersonaScreen(entry.toRoute<PersonaRoute>().companionId, onBack = nav::back) }
        composable<LoreRoute> { LoreScreen(onBack = nav::back, onOpen = { nav.go(LoreEditRoute(it)) }) }
        composable<LoreEditRoute> { entry -> LoreEditScreen(entry.toRoute<LoreEditRoute>().id, onBack = nav::back) }
        composable<LabRoute> { GlassLabScreen(onBack = nav::back) }
        composable<BubbleLabRoute> { com.cleo.cleos.ui.settings.BubbleLabScreen(onBack = nav::back) }
        composable<McpEditRoute> { entry -> McpEditScreen(entry.toRoute<McpEditRoute>().id, onBack = nav::back) }
        composable<ConversationsRoute> { ConversationsScreen(onBack = nav::back, onSearch = { nav.go(SearchRoute) }) }
        // A result opens in the chat: back past the conversation list too.
        composable<SearchRoute> { SearchScreen(onBack = nav::back, onFound = { nav.popBackStack(MainRoute, inclusive = false) }) }
        composable<DiaryRoute> { entry ->
            val route = entry.toRoute<DiaryRoute>()
            DiaryEditorScreen(
                id = route.id,
                startSecret = route.secret,
                onBack = nav::back,
                onOpenImage = { nav.go(ImageRoute(it)) },
            )
        }
        composable<ImageRoute> { entry -> ImageViewerScreen(entry.toRoute<ImageRoute>().file, onBack = nav::back) }
        composable<CallRoute> { CallScreen() }
    }
}

/**
 * Pops only while the current screen is fully shown. A second tap on "back" during the
 * fade-out would otherwise pop the screen underneath too, down to an empty host.
 */
private fun NavHostController.back() {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) popBackStack()
}

/**
 * Same guard going forward: a quick double tap (or a key repeating on a focused button)
 * would otherwise stack the same screen two or three times, and "back" would then seem
 * not to work.
 */
private fun NavHostController.go(route: Any) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) navigate(route)
}
