package com.cleo.cleos

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.ui.CleosNavHost
import com.cleo.cleos.ui.theme.CleosTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as CleosApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // null until the settings file has been read once (a few milliseconds);
            // drawing before that would flash the default wallpaper and colours.
            val settings: AppSettings? by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            settings?.let { s ->
                CleosTheme(s, container.images) {
                    CleosNavHost()
                    com.cleo.cleos.ui.common.UpdatePrompt(container)
                }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1500)
                    container.updates.check()
                }
                AskForNotifications(container, s)
            }
        }
        // Recreated (rotated, or brought back after the system let it go): the tap was already followed.
        if (savedInstanceState == null) follow(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        follow(intent)
    }

    /** A notification tapped: to its conversation, or its letter. */
    private fun follow(intent: Intent?) {
        val conversation = intent?.getLongExtra(Notifier.EXTRA_CONVERSATION, -1L) ?: -1L
        val letter = intent?.getLongExtra(Notifier.EXTRA_LETTER, -1L) ?: -1L
        when {
            conversation > 0 -> container.openConversation(conversation)
            letter > 0 -> container.openLetter(letter)
        }
    }

    override fun onStart() {
        super.onStart()
        container.visible = true
    }

    override fun onStop() {
        container.visible = false
        // The reply under way keeps the app up by itself: ReplyKeeper goes up when it starts, not
        // here. Leaving is the one moment these phones freeze the process, and a service asked for
        // then is never started in time (AppContainer).
        super.onStop()
    }

    // Letters due get written when the app comes to the front: a background job would be
    // killed on many phones, so nothing waits for one.
    override fun onResume() {
        super.onResume()
        container.letters.tick()
    }
}

/**
 * The notification permission (Android 13 on), asked for the first time something would come as
 * one, while the person is here to answer: once. After that it is theirs to change, and the
 * settings say where.
 */
@Composable
private fun AskForNotifications(container: AppContainer, settings: AppSettings) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val wanted by container.later.wantsNotifications.collectAsStateWithLifecycle()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(wanted, settings.notificationsAsked) {
        if (wanted && !settings.notificationsAsked && !container.notifier.allowed()) {
            container.settings.update { it.copy(notificationsAsked = true) }
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
