package com.cleo.cleos.ui.common

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Observe the user's system animation preference, including changes while a page is open. */
@Composable
internal fun systemMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun readEnabled() = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrElse { ValueAnimator.areAnimatorsEnabled() }
    var enabled by remember(resolver) { mutableStateOf(readEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = readEnabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}
