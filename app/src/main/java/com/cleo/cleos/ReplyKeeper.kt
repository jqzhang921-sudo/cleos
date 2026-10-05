package com.cleo.cleos

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the app running while a TA finishes a reply the person left before it came. Phones freeze
 * an app in the background within seconds (ColorOS among them), and a reply streaming from the
 * model then stops mid-sentence: the person found replies cut off after switching away. A
 * foreground service is the one thing they leave running. It shows as "正在回你…" in the status
 * bar for as long as that takes and goes as soon as nothing is being written; what was written
 * comes as a notification (AppContainer).
 *
 * It goes up the moment a reply starts, while the app is still in front ([AppContainer] watches
 * [ChatRepository.working]), and not on leaving it, which is where it used to be started from and
 * is exactly the moment these phones freeze the process: a Huawei tablet then killed the app with
 * ForegroundServiceDidNotStartInTimeException, since the start was never delivered within the five
 * seconds Android allows it — and that is how it went, over and over. Starting from the front
 * costs a quiet line in the shade while a reply is being written (IMPORTANCE_LOW, silent), which
 * is the price of the start landing at a moment the phone will let it through.
 */
class ReplyKeeper : Service() {
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as CleosApp).container
        startForeground(ID, container.notifier.working(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watching == null) {
            watching = container.appScope.launch {
                // Until nothing is being written, or a while at most: a reply stuck on a model that
                // never answers must not keep the app up for good.
                withTimeoutOrNull(LIMIT_MS) { container.chat.working.first { it.isEmpty() } }
                withContext(Dispatchers.Main) { done() }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps how long this kind of service may run; long before that it is done anyway. */
    override fun onTimeout(startId: Int, fgsType: Int) = done()

    private fun done() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        watching?.cancel()
        watching = null
        super.onDestroy()
    }

    companion object {
        private const val ID = 7
        private const val LIMIT_MS = 10 * 60_000L

        /**
         * A reply is under way ([ChatRepository.working]). Called from the app's own wiring, while
         * the app is in front, never from an Activity leaving: that is the one moment this must not
         * be asked of the system. Where it isn't allowed at all (Android 12 on, out of the front),
         * it is caught and the reply simply takes its chances.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ReplyKeeper::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
