package com.cleo.cleos.focus

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cleo.cleos.CleosApp
import com.cleo.cleos.MainActivity
import com.cleo.cleos.R
import kotlinx.coroutines.launch

/** One system alarm, no continuously running service, network, microphone or app blocking. */
class FocusAlerts(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun exactAllowed() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    fun notificationsAllowed() = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        manager.getNotificationChannel(DONE)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun open() = PendingIntent.getActivity(context, 9184,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun alarm() = PendingIntent.getBroadcast(context, 9184,
        Intent(context, FocusReceiver::class.java).setAction("focus.deadline"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(s: FocusSession, name: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, FocusReceiver::class.java).setAction(name)
            .setData(Uri.parse("cleos://focus/${s.id}/$name")).putExtra("id", s.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun channels() {
        manager.createNotificationChannel(NotificationChannel(ONGOING, "专注计时", NotificationManager.IMPORTANCE_LOW).apply {
            description = "进行中的专注或休息，可暂停、继续或回到计时页"; setShowBadge(false)
        })
        manager.createNotificationChannel(NotificationChannel(DONE, "专注结束", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "专注或休息到时的提醒"; enableVibration(true)
        })
    }

    fun update(s: FocusSession?) {
        channels()
        alarms.cancel(alarm())
        if (s == null) { manager.cancel(TIMER_ID); return }
        if (s.status == "running") {
            val left = FocusTimer.remaining(s, android.os.SystemClock.elapsedRealtime())
            val deadline = s.segmentElapsed + s.remainingMs
            try {
                // A user-started timer must also finish its short rest interval in Doze.
                // Allow-while-idle alarms are throttled to one per nine minutes there.
                // TIME_SET reschedules this wall-clock alarm from the monotonic remainder.
                if (exactAllowed()) alarms.setAlarmClock(
                    AlarmManager.AlarmClockInfo(System.currentTimeMillis() + left, open()), alarm())
                else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, alarm())
            } catch (_: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, alarm())
            }
        }
        val running = s.status == "running"
        val label = if (s.phase == "rest") "休息" else "专注"
        val n = NotificationCompat.Builder(context, ONGOING)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle(if (running) "$label 中 · ${s.title}" else "$label 已暂停")
            .setContentText(if (running) "可以切换应用，计时会继续" else "${s.title} · 点此回到专注")
            .setContentIntent(open()).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, if (running) "暂停" else "继续", action(s, if (running) "pause" else "resume"))
            .setWhen(System.currentTimeMillis() + FocusTimer.remaining(s, android.os.SystemClock.elapsedRealtime()))
            .setUsesChronometer(running).setChronometerCountDown(true)
        try { manager.notify(TIMER_ID, n.build()) } catch (_: SecurityException) { /* User may decline notifications. */ }
    }

    fun completed(s: FocusSession) {
        channels()
        val rest = s.phase == "rest"
        val n = NotificationCompat.Builder(context, DONE).setSmallIcon(R.drawable.ic_notify)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle(if (rest) "休息结束了" else "这段专注完成了")
            .setContentText(if (rest) "准备好后，再开始下一段" else "${s.title} · 可以休息一下")
            .setContentIntent(open()).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        try { manager.notify(DONE_ID, n.build()) } catch (_: SecurityException) { }
    }

    companion object {
        const val EXTRA_OPEN = "open_focus"
        const val ONGOING = "focus_timer"
        const val DONE = "focus_complete"
        const val TIMER_ID = 9184
        const val DONE_ID = 9185
        fun permissionIntent(context: Context) = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
    }
}

class FocusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val c = (context.applicationContext as CleosApp).container
        c.appScope.launch {
            try {
                val id = intent.getStringExtra("id")
                if (id != null) c.focus.action(id, intent.action.orEmpty()) else c.focus.reconcile()
            } finally { pending.finish() }
        }
    }
}
