package com.cleo.cleos

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a call with a TA (ai/Call.kt) going while the screen is off or the person is in another
 * app. Android lets an app use the microphone from the background only through a foreground
 * service of the microphone kind, started while the app is on screen: the call starts it as it
 * begins, right after the person tapped call, and stops it as it ends. Its notification says who
 * the call is with and for how long, and hangs up.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as CleosApp).container
        if (intent?.action == ACTION_HANG_UP) {
            container.calls.hangUp()
            container.groupCalls.hangUp()
            return START_NOT_STICKY
        }
        // Up before anything else: a service started this way that doesn't say so in time is an error.
        try {
            startForeground(ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            // Not allowed now (the app went to the back before it got here): the call runs while the app is in front.
            stopSelf()
            return START_NOT_STICKY
        }
        val privateActive = container.calls.state.value?.phase?.let { it != com.cleo.cleos.ai.CallPhase.Ended } == true
        val groupActive = container.groupCalls.state.value?.phase?.let { it != com.cleo.cleos.ai.CallPhase.Ended } == true
        if (!privateActive && !groupActive) done()
        return START_NOT_STICKY
    }

    /** Android 15 caps how long some services may run; a call hangs up rather than being cut. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as CleosApp).container.calls.hangUp()
        (application as CleosApp).container.groupCalls.hangUp()
        done()
    }

    private fun done() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ID = 8
        private const val ACTION_HANG_UP = "com.cleo.cleos.HANG_UP"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }

        /** The notification again, once the TA has picked up: from then on it counts the minutes. */
        fun refresh(context: Context) {
            try {
                NotificationManagerCompat.from(context).notify(ID, notification(context))
            } catch (_: SecurityException) {
                // Notifications not allowed: the service runs without one showing.
            }
        }

        private fun notification(context: Context): android.app.Notification {
            val container = (context.applicationContext as CleosApp).container
            val call = container.calls.state.value
            val groupCall = container.groupCalls.state.value
            val hangUp = PendingIntent.getService(
                context,
                0,
                Intent(context, CallService::class.java).setAction(ACTION_HANG_UP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return container.notifier.calling(groupCall?.title ?: call?.name?.ifEmpty { null } ?: "TA",
                groupCall?.answeredAt ?: call?.answeredAt, hangUp)
        }
    }
}
