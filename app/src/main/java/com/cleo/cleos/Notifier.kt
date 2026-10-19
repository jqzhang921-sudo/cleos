package com.cleo.cleos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.data.StickerText
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.data.db.MessageEntity

/**
 * What a TA sends on its own (ai/Later.kt), a reply it finished after the person left, and letters
 * arriving, as notifications. Tapping one opens that conversation, or that letter.
 *
 * Both channels are created at high importance from the start. Android lets an app lower a
 * channel's importance but never raise it again (only the person can, in system settings): an
 * earlier app of the same kind shipped its channel at the default level, which on some phones
 * means no banner, and changing the code afterwards did nothing for anyone who had it already.
 * Whether it rings or stays silent is then up to the phone's own settings, as with any chat.
 */
class Notifier(private val context: Context, private val images: ImageStore) {
    fun channels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "TA 的消息", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "TA 发给你、你还没看到的消息：它自己想起来的，和你走开时它写完的回复"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LETTERS, "信", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "TA 写给你的信寄到了"
            },
        )
        // Low: it is only there while a reply is being written, and the app needs it up from before
        // the person leaves (ReplyKeeper).
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WORKING, "正在回复", NotificationManager.IMPORTANCE_LOW).apply {
                description = "TA 写回复的时候一直在，写完就消失；它在的时候，切走或者锁屏，这段回复也写得完"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "通话", NotificationManager.IMPORTANCE_LOW).apply {
                description = "和 TA 打电话时一直在，能从这里挂断；挂了就消失"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(NotificationChannel(CHANNEL_INCOMING, "TA 主动来电", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "已允许的 TA 邀请你语音通话；接听后才打开话筒"
            setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            enableVibration(true)
        })
    }

    fun incomingAllowed(): Boolean = allowed() &&
        context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL_INCOMING)?.importance != NotificationManager.IMPORTANCE_NONE

    fun incoming(ta: CompanionEntity, row: com.cleo.cleos.data.db.IncomingCallEntity, persistent: Boolean = false) {
        fun open(answer: Boolean) = PendingIntent.getActivity(context, 0,
            Intent(context,MainActivity::class.java)
                .setData(android.net.Uri.parse("cleos://incoming/${row.id}/${if(answer) "answer" else "open"}"))
                .putExtra(EXTRA_INCOMING,row.id).putExtra(EXTRA_ANSWER,answer)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun dismiss(status: String) = PendingIntent.getBroadcast(context,0,
            Intent(context,IncomingCallReceiver::class.java)
                .setData(android.net.Uri.parse("cleos://incoming/${row.id}/$status"))
                .putExtra(EXTRA_INCOMING,row.id).putExtra("outcome",status),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val alert = PendingIntent.getActivity(context, 0,
            Intent(context, IncomingCallActivity::class.java)
                .setData(android.net.Uri.parse("cleos://incoming/${row.id}/alert"))
                .putExtra(EXTRA_INCOMING,row.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val person=Person.Builder().setName(com.cleo.cleos.ai.IncomingAlertRules.title(ta.name)).setImportant(true)
            .apply { avatar(ta)?.let { setIcon(IconCompat.createWithBitmap(it)) } }.build()
        fun builder(fullScreen: Boolean = persistent)=NotificationCompat.Builder(context,CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call).setContentTitle(com.cleo.cleos.ai.IncomingAlertRules.title(ta.name))
            .setContentText("等待接听 · 接听后才打开话筒").setSubText("语音来电").setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(false)).setDeleteIntent(dismiss("missed"))
            .setOnlyAlertOnce(true).setOngoing(true).setAutoCancel(false)
            .setTimeoutAfter((row.expiresAt-System.currentTimeMillis()).coerceAtLeast(1))
            .apply { if(fullScreen) setFullScreenIntent(alert,true) }
        fun compatible(fullScreen: Boolean)=builder(fullScreen)
            .setStyle(NotificationCompat.BigTextStyle().bigText("等待接听 · 接听后才打开话筒\n${row.reason}"))
            .apply { avatar(ta)?.let { setLargeIcon(it) } }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"拒绝",dismiss("declined"))
            .addAction(android.R.drawable.stat_sys_phone_call,"接听",open(true))
            .build().apply { flags=flags or android.app.Notification.FLAG_INSISTENT }
        val manager=NotificationManagerCompat.from(context)
        try {
            val notification=builder()
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(person,dismiss("declined"),open(true)))
                .build().apply { flags=flags or android.app.Notification.FLAG_INSISTENT }
            manager.notify("incoming_call",1,notification)
        } catch (_: IllegalArgumentException) {
            // Some ROMs reject standalone CallStyle. Keep the same clear title and answer actions.
            try { manager.notify("incoming_call",1,compatible(persistent)) }
            catch (_: SecurityException) { manager.notify("incoming_call",1,compatible(false)) }
        } catch (_: SecurityException) {
            // A revoked/unsupported full-screen permission must not lose an otherwise valid call.
            manager.notify("incoming_call",1,compatible(false))
        }
    }
    fun cancelIncoming() { NotificationManagerCompat.from(context).cancel("incoming_call",1) }

    /**
     * What CallService shows while a call is on: who it is with, how long it has gone on once the
     * TA picked up ([since]; null while it rings), and a button that hangs up.
     */
    fun calling(name: String, since: Long?, hangUp: PendingIntent): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (since == null) "正在呼叫$name…" else "和${name}通话中")
            .setContentText("点这里回到电话")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .apply {
                if (since != null) {
                    setWhen(since)
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
            }
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    1,
                    Intent(context, MainActivity::class.java)
                        .putExtra(EXTRA_CALL, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(0, "挂断", hangUp)
            .build()

    /** False when notifications are off for the app, or (Android 13 on) not allowed yet. */
    fun allowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** What ReplyKeeper shows while it keeps the app running. */
    fun working(): android.app.Notification = NotificationCompat.Builder(context, CHANNEL_WORKING)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle("正在回你…")
        .setContentText("写完就走，写好的会发给你")
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    /**
     * [sent]: what the TA has said on its own in [conversationId] since the person last wrote there,
     * oldest first. One notification per conversation, so a later one replaces the earlier: it
     * carries the earlier messages along rather than dropping them from the shade.
     */
    fun messages(ta: CompanionEntity, conversationId: Long, sent: List<MessageEntity>) {
        if (sent.isEmpty() || !allowed()) return
        val name = ta.name.trim().ifEmpty { "TA" }
        val them = Person.Builder()
            .setName(name)
            .setKey("ta-${ta.id}")
            .apply { avatar(ta)?.let { setIcon(IconCompat.createWithBitmap(it)) } }
            .build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("我").build())
        for (m in sent) style.addMessage(if (m.audio != null) "[语音] ${m.content}" else StickerText.plain(m.content), m.createdAt, them)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(name)
            .setContentText(StickerText.plain(sent.last().content))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open(EXTRA_CONVERSATION, conversationId))
            .build()
        post(TAG_CONVERSATION, conversationId, notification)
    }

    fun letter(ta: CompanionEntity, letter: LetterEntity) {
        if (!allowed()) return
        val name = ta.name.trim().ifEmpty { "TA" }
        val notification = NotificationCompat.Builder(context, CHANNEL_LETTERS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(name)
            .setContentText("给你写了一封信，在信箱里。")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply { avatar(ta)?.let { setLargeIcon(it) } }
            .setContentIntent(open(EXTRA_LETTER, letter.id))
            .build()
        post(TAG_LETTER, letter.id, notification)
    }

    private fun post(tag: String, id: Long, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(tag, id.toInt(), notification)
        } catch (_: SecurityException) {
            // The permission was taken back between the check and now.
        }
    }

    private fun open(extra: String, id: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(extra, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            (extra.hashCode() * 31 + id).toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** The TA's picture, small and square; none for an emoji or the initial. */
    private fun avatar(ta: CompanionEntity): Bitmap? {
        val file = ta.avatar?.let(images::file)?.takeIf { it.exists() } ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= AVATAR_PX) sample *= 2
            val full = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val side = minOf(full.width, full.height)
            val square = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
            Bitmap.createScaledBitmap(square, AVATAR_PX, AVATAR_PX, true)
        }.getOrNull()
    }

    companion object {
        const val CHANNEL_MESSAGES = "ta_messages"
        const val CHANNEL_LETTERS = "letters"
        const val CHANNEL_WORKING = "replying"
        const val CHANNEL_CALLS = "calls"
        const val CHANNEL_INCOMING = "incoming_calls"
        const val EXTRA_INCOMING = "incoming_invitation"
        const val EXTRA_ANSWER = "answer_incoming"
        const val EXTRA_CALL = "open_call"
        const val EXTRA_CONVERSATION = "conversation"
        const val EXTRA_LETTER = "letter"
        private const val TAG_CONVERSATION = "conversation"
        private const val TAG_LETTER = "letter"
        private const val AVATAR_PX = 128
    }
}
