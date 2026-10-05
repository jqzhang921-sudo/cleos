package com.cleo.cleos.ai

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cleo.cleos.CleosApp
import com.cleo.cleos.Notifier
import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.SettingsRepository
import com.cleo.cleos.data.StickerText
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.LaterEntity
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.db.WakeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A TA reaching out on its own, the one way this app lets it: while talking it notes something
 * down ("gone to cook; ask how it went in forty minutes"), and when the time comes it reads the
 * note again, with whatever was said since, and decides whether to say it.
 *
 * Only something the TA noted itself ever wakes it. There is deliberately no timer that wakes it
 * to ask "anything to say?", no "it's been a while", no count of unanswered messages making it
 * sadder. That was settled in an earlier app of the same kind: the TA gets an outlet for when it
 * wants to say something, not a schedule. A schedule becomes a quota (something gets said because
 * a slot is free); a TA asked every quarter of an hour whether to speak ends up speaking; and
 * "you haven't written in a while" trades on guilt. Plugins that do exactly these things are
 * common, and it is what they end up sounding like.
 *
 * The one exception, asked for by the person: a greeting when their day starts and when it ends
 * (RoutineRules), which is kept as far from a timer as it can be.
 *
 * The phone decides when background work really runs: on some systems "in forty minutes" turns
 * into an hour, now and then into many. So every note expires. Past its time it is dropped
 * unsaid rather than delivered hours late.
 */
object LaterRules {
    /** Notes one TA can have waiting at once: past this it is noting things instead of talking. */
    const val MAX_WAITING = 4

    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 7 * 24 * 60

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /**
     * How long past its time a note is still worth saying: as long as it waited, between half
     * an hour and three hours. "How did dinner go" is stale two hours on; something noted the
     * evening before for the morning still holds a while after it was due.
     */
    fun grace(waitMs: Long): Long = waitMs.coerceIn(30 * MINUTE, 3 * HOUR)

    /**
     * How long a note waits when it comes due while a reply is being written in its conversation, or
     * the person is typing there: only so as not to land in the middle of it. There used to be a
     * wider rule (anything within ten minutes of the person's last message waited ten minutes, on
     * the idea that they were still talking and the next reply would bring it up). On the phone it
     * made every short note late: "message me again in two minutes", asked mid-conversation, came
     * thirteen minutes on, every time. The TA reads the conversation before it speaks anyway.
     */
    const val SOON_MINUTES = 1L

    /** How long a note waits before trying again after the request failed. */
    const val RETRY_MINUTES = 10L

    /**
     * Wakes that said something, unanswered, after which nothing more is pushed until the person
     * writes. A fuse against a TA talking into silence, not a quota: it never comes into play
     * while the two are talking.
     */
    const val UNANSWERED_MAX = 2

    /** "40 分钟", "2 小时 15 分钟", "1 天 3 小时". */
    fun span(minutes: Long): String {
        val m = minutes.coerceAtLeast(1)
        return when {
            m < 60 -> "$m 分钟"
            m < 24 * 60 -> "${m / 60} 小时" + (if (m % 60 > 0) " ${m % 60} 分钟" else "")
            else -> "${m / (24 * 60)} 天" + (if (m % (24 * 60) >= 60) " ${m % (24 * 60) / 60} 小时" else "")
        }
    }

    private val clockTime = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val dayAndTime = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)

    /** "今天 16:40", "明天 08:00", "10月3日 09:00". */
    fun at(time: ZonedDateTime, now: ZonedDateTime): String {
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), time.toLocalDate())
        return when (days) {
            0L -> "今天 " + time.format(clockTime)
            1L -> "明天 " + time.format(clockTime)
            -1L -> "昨天 " + time.format(clockTime)
            else -> time.format(dayAndTime)
        }
    }

    /** What note_for_later answers with. */
    fun noted(minutes: Int, graceMs: Long, due: ZonedDateTime, now: ZonedDateTime): String =
        "记下了。大约 ${span(minutes.toLong())}以后（${at(due, now)}）你会再看到这一笔，到时候看看这之间聊了什么，再决定说不说。" +
            "手机有时会晚一点才叫醒你；晚过 ${span(graceMs / MINUTE)}，这一笔就作废。"

    /**
     * What the TA reads when a note comes due: a turn after the conversation that the person
     * never sees, saying so. The TA decides; SKIP is an answer, and so is putting it off once.
     * The last lines are the line the whole feature stands on: bring something, don't ask for
     * anything. [glance]: the day's calendar and todos (Glance), there to know, not to recite.
     */
    fun wakeText(note: LaterEntity, now: ZonedDateTime, canPutOff: Boolean, glance: String? = null): String {
        val written = ZonedDateTime.ofInstant(Instant.ofEpochMilli(note.createdAt), now.zone)
        return buildString {
            append("（这条不是对方发的，对方看不到。）\n")
            append("你之前给自己记了一笔，现在到时候了：\n「").append(note.what).append("」\n")
            append("记下的时候是").append(at(written, now))
            if (note.why.isNotBlank()) append("，当时").append(note.why.trim().trimEnd('。'))
            append("。\n").append(Prompt.timeLine(now)).append("。\n")
            append("看看从那以后你们又聊了什么，再决定：\n")
            append("· 现在说：像平常一样给对方发消息，主要说这件事。直接说，不用解释是记着的、被提醒的。\n")
            append("· 不说了（已经聊过了、过了时候、或者现在说不合适）：只回复 SKIP，后面可以跟一句为什么。\n")
            if (canPutOff) append("· 还想再等一等：用 note_for_later 重新记一笔，只能往后推这一次。\n")
            if (glance != null) append("对方接下来的日程和待办，让你知道一下；真有用再顺带一句，别念清单：\n").append(glance).append("\n")
            append("说的话要给对方带去点什么：关心那件事、分享、提醒。别问「在干嘛」「怎么不回我」，也别提对方多久没说话。")
        }
    }

    /**
     * Notes that came due while the person was talking, for the next reply to take in (on the
     * person's last message, beside the time, not in the system prompt: see Prompt).
     */
    fun dueLine(what: List<String>): String? =
        if (what.isEmpty()) null
        else "（你之前给自己记过、现在到时候了：" + what.joinToString("；") { "「$it」" } + "。合适就顺着聊天自然带上，不合适就算了。）"

    /** SKIP first thing, also in brackets: 「SKIP：…」, （SKIP）. */
    private val skip = Regex("^[\\s（(【\\[「]*SKIP", RegexOption.IGNORE_CASE)

    fun isSkip(text: String): Boolean = skip.containsMatchIn(text)

    /** What follows SKIP, as the reason in settings. */
    fun skipReason(text: String): String =
        skip.replace(text.trim(), "").trimStart(' ', '：', ':', '，', ',', '-', '—', '。', '）', ')', '】', ']', '」', '\n').trim().take(80)
}

/** Where a TA's notes go: the app side of note_for_later. */
interface LaterBook {
    /** What the model is told back. Throws [ToolFailure] when it can't be noted. */
    suspend fun note(companionId: Long, conversationId: Long, what: String, why: String, minutes: Int): String
}

/**
 * Notes, letters and the day's two greetings in the background: each waits in WorkManager until
 * its time, which the phone may stretch (see [LaterRules]). [showing]: whether that conversation
 * is on screen right now, in which case what arrives in it needs no notification.
 */
class Later(
    private val context: Context,
    private val db: AppDatabase,
    private val chat: ChatRepository,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
    private val showing: suspend (conversationId: Long) -> Boolean,
    private val companions: Companions,
    private val settings: SettingsRepository,
    private val glance: Glance,
    /**
     * Whether the person is using the phone right now: screen on and past the lock screen. How a
     * greeting tells they are up (or still up); a screen lit by a notification doesn't count.
     */
    private val inUse: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : LaterBook {
    /**
     * Set once there is something that would come as a notification: the screen then asks for
     * the permission, once, while the person is there to answer.
     */
    val wantsNotifications = MutableStateFlow(false)

    /**
     * The note being woken, by conversation: a note taken there meanwhile puts that one off, which
     * happens only once. By conversation, not TA: a conversation runs one turn at a time (a wake
     * waits for a reply there and the other way round), so what is noted in it belongs to that wake.
     */
    private val waking = ConcurrentHashMap<Long, LaterEntity>()

    private fun zoned(ms: Long) = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone())

    override suspend fun note(companionId: Long, conversationId: Long, what: String, why: String, minutes: Int): String {
        val now = clock()
        val current = waking[conversationId]
        if (current?.putOff == true) {
            throw ToolFailure("这件事已经往后推过一次了，不能再推：现在说，或者回复 SKIP。", "推过一次了")
        }
        dropExpired(companionId, now)
        if (db.later().allFor(companionId).size >= LaterRules.MAX_WAITING) {
            throw ToolFailure("已经记着 ${LaterRules.MAX_WAITING} 件事了，先别记新的，等前面的到了时候再说。", "记满了")
        }
        val m = minutes.coerceIn(LaterRules.MIN_MINUTES, LaterRules.MAX_MINUTES)
        val due = now + m * 60_000L
        val grace = LaterRules.grace(due - now)
        val id = db.later().insert(
            LaterEntity(
                companionId = companionId,
                conversationId = conversationId.takeIf { it > 0 },
                what = what,
                why = why,
                createdAt = now,
                dueAt = due,
                expiresAt = due + grace,
                putOff = current != null,
            ),
        )
        enqueue(KIND_WAKE, id, due, ExistingWorkPolicy.REPLACE)
        wantsNotifications.value = true
        return LaterRules.noted(m, grace, zoned(due), zoned(now))
    }

    /**
     * Note [id] came due. The minutes until it is tried again (a reply being written there, the
     * person typing, a request that failed), while it is still in time; null when it is done.
     */
    suspend fun wake(id: Long): Long? {
        val note = db.later().get(id) ?: return null
        val ta = db.companions().get(note.companionId) ?: return null
        val now = clock()
        if (!ta.proactive) {
            // Switched off since: what it noted goes unsaid.
            db.later().delete(id)
            return null
        }
        if (now >= note.expiresAt) {
            db.later().delete(id)
            log(ta, WakeEntity.EXPIRED, "手机晚了 ${LaterRules.span((now - note.dueAt) / 60_000)}才叫醒，过了时候")
            return null
        }
        // Woken early (the clock changed): in a while.
        if (now < note.dueAt) return LaterRules.SOON_MINUTES
        val conversationId = conversationFor(note, ta)
        // Not on top of a reply being written, or of what the person is typing: a minute later.
        if (chat.busy(conversationId) || chat.isTyping(conversationId)) return stillInTime(note, ta, LaterRules.SOON_MINUTES, "一直在聊，没找到空说")
        val lastSaid = db.messages().newest(conversationId, RECENT).firstOrNull { it.role == "user" && it.note == null }?.createdAt
        if (db.wakes().sentSince(ta.id, lastSaid ?: 0L) >= LaterRules.UNANSWERED_MAX) {
            // Left for the next reply to take in, if the person writes while it is still in time.
            log(ta, WakeEntity.HELD, "前面自己说的还没回，这件先不推，等你回来再说")
            return null
        }
        val ahead = glance.of(zoned(now), zoned(now).plusHours(GLANCE_HOURS), zoned(now))
        // Another wake already under way here keeps its place; this one will find the conversation busy.
        val mine = waking.putIfAbsent(conversationId, note) == null
        val result = try {
            chat.wake(conversationId, LaterRules.wakeText(note, zoned(now), canPutOff = !note.putOff, glance = ahead))
        } finally {
            if (mine) waking.remove(conversationId, note)
        }
        return when (result) {
            is ChatRepository.WakeResult.Sent -> {
                db.later().delete(id)
                log(ta, WakeEntity.SENT, result.messages.joinToString(" / ") { StickerText.plain(it.content) })
                said(ta, conversationId, result.messages)
                null
            }
            is ChatRepository.WakeResult.Skipped -> {
                db.later().delete(id)
                log(ta, WakeEntity.SKIPPED, result.why)
                null
            }
            ChatRepository.WakeResult.Busy -> stillInTime(note, ta, LaterRules.SOON_MINUTES, "一直在聊，没找到空说")
            is ChatRepository.WakeResult.Failed -> {
                log(ta, WakeEntity.FAILED, result.why)
                stillInTime(note, ta, LaterRules.RETRY_MINUTES, null)
            }
        }
    }

    /**
     * What the TA has said on its own in [conversationId] since the person last wrote, oldest first
     * (at most [NOTIFY_MAX]): what its notification shows, earlier wakes' messages included.
     */
    private suspend fun unanswered(conversationId: Long): List<MessageEntity> =
        db.messages().newest(conversationId, RECENT)
            .takeWhile { !(it.role == "user" && it.note == null) }
            .filter { it.role == "assistant" && it.proactive && it.error == null && it.content.isNotBlank() }
            .take(NOTIFY_MAX)
            .reversed()

    /** [minutes], if a try that much later would still be in time; if not, [note] goes, logged as [why] when there is one. */
    private suspend fun stillInTime(note: LaterEntity, ta: CompanionEntity, minutes: Long, why: String?): Long? {
        if (clock() + minutes * 60_000L < note.expiresAt) return minutes
        db.later().delete(note.id)
        why?.let { log(ta, WakeEntity.EXPIRED, it) }
        return null
    }

    private suspend fun conversationFor(note: LaterEntity, ta: CompanionEntity): Long =
        note.conversationId?.takeIf { db.conversations().get(it) != null }
            ?: db.conversations().latestFor(ta.id)?.id
            ?: chat.newConversation(ta.id)

    /** When the person's days usually start and end, from when they wrote lately. */
    suspend fun habits(): Habits {
        val since = clock() - RoutineRules.LOOKBACK_DAYS * 24 * 3_600_000L
        return RoutineRules.habits(db.messages().userTimesSince(since), zone())
    }

    /**
     * Today's [greeting]: looked into through its window, said (or not) once, then the next day's is
     * set up. The minutes until it is looked at again within the window; null once it is over for
     * today (the next one already queued then).
     */
    suspend fun greet(greeting: Greeting): Long? {
        val now = zoned(clock())
        val day = RoutineRules.dayOf(now)
        // Not enough to go on yet, or no morning (or night) to speak of: looked at again tomorrow.
        val window = RoutineRules.window(greeting, habits(), day, now.zone) ?: return nextDay(greeting, day)
        // There is one to come: the permission is asked for the next time the app is open.
        wantsNotifications.value = true
        if (settings.greetedOn(greeting) == day.toEpochDay()) return nextDay(greeting, day)
        if (now.isBefore(window.start)) return Duration.between(now, window.start).toMinutes().coerceAtLeast(1)
        if (now.isAfter(window.endInclusive)) return over(greeting, day)
        val ta = companions.current()
        if (!ta.proactive) return over(greeting, day)
        val conversationId = db.conversations().latestFor(ta.id)?.id ?: return over(greeting, day)
        val lastToday = db.messages().userTimesSince(RoutineRules.startOf(day, now.zone).toInstant().toEpochMilli()).maxOrNull()
        when (greeting) {
            // Come to talk already: whatever greeting there is, it is in the replies.
            Greeting.Morning -> if (lastToday != null) return over(greeting, day)
            // Talking right now: no goodnight of its own in the middle of it.
            Greeting.Night -> if (lastToday != null && now.toInstant().toEpochMilli() - lastToday < RoutineRules.TALKING.toMillis()) return over(greeting, day)
        }
        // Not up yet, or the phone put down: looked at again in a while.
        if (!inUse()) return RoutineRules.CHECK_MINUTES
        if (chat.busy(conversationId) || chat.isTyping(conversationId)) return LaterRules.SOON_MINUTES
        val lastSaid = db.messages().newest(conversationId, RECENT).firstOrNull { it.role == "user" && it.note == null }?.createdAt
        if (db.wakes().sentSince(ta.id, lastSaid ?: 0L) >= LaterRules.UNANSWERED_MAX) {
            log(ta, WakeEntity.HELD, "前面自己说的还没回，这次不打招呼")
            return over(greeting, day)
        }
        val tomorrow = RoutineRules.startOf(day.plusDays(1), now.zone)
        val text = when (greeting) {
            Greeting.Morning -> RoutineRules.morningText(now, glance.of(now, tomorrow, now))
            Greeting.Night -> RoutineRules.nightText(now, lastToday?.let(::zoned), glance.of(tomorrow, tomorrow.plusDays(1), now))
        }
        // What was said reads as a greeting by itself; a skip or a failure says which one it was.
        fun which(why: String) = (if (greeting == Greeting.Morning) "早上的招呼" else "睡前的招呼") + (if (why.isBlank()) "" else "：$why")
        return when (val result = chat.wake(conversationId, text)) {
            is ChatRepository.WakeResult.Sent -> {
                log(ta, WakeEntity.SENT, result.messages.joinToString(" / ") { StickerText.plain(it.content) })
                said(ta, conversationId, result.messages)
                over(greeting, day)
            }
            is ChatRepository.WakeResult.Skipped -> {
                log(ta, WakeEntity.SKIPPED, which(result.why))
                over(greeting, day)
            }
            ChatRepository.WakeResult.Busy -> LaterRules.SOON_MINUTES
            is ChatRepository.WakeResult.Failed -> {
                log(ta, WakeEntity.FAILED, which(result.why))
                if (now.plusMinutes(LaterRules.RETRY_MINUTES).isBefore(window.endInclusive)) LaterRules.RETRY_MINUTES else over(greeting, day)
            }
        }
    }

    /** Today's [greeting] is over: marked so, and the next one queued. */
    private suspend fun over(greeting: Greeting, day: LocalDate): Long? {
        settings.setGreetedOn(greeting, day.toEpochDay())
        return nextDay(greeting, day)
    }

    /**
     * The next day's look at [greeting]: at the start of its window as things stand, or of the day
     * when there is nothing to go on yet (the window is worked out afresh then). Never sooner than a
     * minute on, whatever the clock did: this runs from the job itself.
     */
    private suspend fun nextDay(greeting: Greeting, day: LocalDate): Long? {
        val next = day.plusDays(1)
        val at = (RoutineRules.window(greeting, habits(), next, zone())?.start ?: RoutineRules.startOf(next, zone())).toInstant().toEpochMilli()
        enqueue(kindOf(greeting), 0, maxOf(at, clock() + 60_000L), ExistingWorkPolicy.APPEND_OR_REPLACE)
        return null
    }

    private fun kindOf(greeting: Greeting) = if (greeting == Greeting.Morning) KIND_MORNING else KIND_NIGHT

    /** A TA's letter was written: a notification when it arrives. */
    fun letterWritten(letter: LetterEntity) {
        val at = letter.deliverAt ?: return
        enqueue(KIND_LETTER, letter.id, at, ExistingWorkPolicy.REPLACE)
        wantsNotifications.value = true
    }

    /** Letter [id]'s time came. The minutes until it is looked at again when it isn't yet (the clock changed); null when done. */
    suspend fun letterArrived(id: Long): Long? {
        val letter = db.letters().get(id) ?: return null
        if (letter.author != LetterEntity.AUTHOR_AI || letter.readAt != null) return null
        val at = letter.deliverAt ?: return null
        if (at > clock()) return LaterRules.RETRY_MINUTES
        val ta = db.companions().get(letter.companionId) ?: return null
        notifier.letter(ta, letter)
        return null
    }

    /**
     * At startup: work for every note still waiting and every letter on its way, in case it was
     * lost (by an update, or a phone that drops jobs on reboot). What is past its time goes.
     */
    fun reconcile() {
        scope.launch {
            val now = clock()
            for (note in db.later().all()) {
                if (note.expiresAt <= now) {
                    db.later().delete(note.id)
                    db.companions().get(note.companionId)?.let { log(it, WakeEntity.EXPIRED, "手机没在到时候叫醒") }
                } else {
                    enqueue(KIND_WAKE, note.id, maxOf(note.dueAt, now), ExistingWorkPolicy.KEEP)
                }
            }
            for (letter in db.letters().onTheirWay(now)) {
                enqueue(KIND_LETTER, letter.id, letter.deliverAt ?: continue, ExistingWorkPolicy.KEEP)
            }
            // The greetings, unless their chain is already waiting: each works out its own time.
            enqueue(KIND_MORNING, 0, now, ExistingWorkPolicy.KEEP)
            enqueue(KIND_NIGHT, 0, now, ExistingWorkPolicy.KEEP)
        }
    }

    private suspend fun dropExpired(companionId: Long, now: Long) {
        val gone = db.later().allFor(companionId).filter { it.expiresAt <= now }
        if (gone.isEmpty()) return
        db.later().delete(gone.map { it.id })
        db.companions().get(companionId)?.let { ta -> gone.forEach { _ -> log(ta, WakeEntity.EXPIRED, "手机没在到时候叫醒") } }
    }

    /** Whether the TA this was about is still there; the person can delete one in the middle of a wake. */
    private suspend fun stillHere(ta: CompanionEntity): Boolean = db.companions().get(ta.id) != null

    /**
     * Writing down what a wake did, for the line in settings. Nothing at all when [ta] went while
     * this was under way: the row points at a TA that is no longer there, and SQLite refuses to
     * write it (FOREIGN KEY constraint failed) — which, thrown out of a background coroutine, used
     * to take the whole app down. Asking and writing in one transaction, so that a TA being deleted
     * alongside lands wholly before or wholly after it and can't slip in between.
     */
    private suspend fun log(ta: CompanionEntity, outcome: String, detail: String) {
        db.withTransaction {
            if (!stillHere(ta)) return@withTransaction
            db.wakes().insert(WakeEntity(companionId = ta.id, at = clock(), outcome = outcome, detail = detail.take(DETAIL_MAX)))
            db.wakes().prune(ta.id, KEPT)
        }
    }

    /**
     * What a wake said, as a notification: unless it is on screen, or the TA was deleted while the
     * model was writing it. A notification for a TA that is gone leads nowhere and says nothing.
     */
    private suspend fun said(ta: CompanionEntity, conversationId: Long, messages: List<MessageEntity>) {
        if (!stillHere(ta) || showing(conversationId)) return
        notifier.messages(ta, conversationId, unanswered(conversationId).ifEmpty { messages })
    }

    /**
     * No "network required" on a wake, though it asks the TA's model. That constraint waits for a
     * network the phone has *validated*, and phones that validate against Google's servers never
     * do so in mainland China: the emulator here showed it, its job sat unsatisfied with the model
     * a few metres away. Offline, the request fails and the note tries again in a while, until it
     * is past its time.
     */
    private fun enqueue(kind: String, id: Long, at: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<LaterWorker>()
            .setInitialDelay((at - clock()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_KIND to kind, KEY_ID to id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("$kind-$id", policy, request)
    }

    /**
     * [kind] [id] once more, [minutes] after the run under way ends: a job of its
     * own, chained after it. WorkManager's own retry stretches every wait (10, 20, 30 minutes on),
     * and a note that came due mid-conversation should be looked at again soon after the talking
     * stops, not half an hour later when it may be past its time.
     */
    fun again(kind: String, id: Long, minutes: Long) {
        enqueue(kind, id, clock() + minutes * 60_000L, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_ID = "id"
        const val KIND_WAKE = "wake"
        const val KIND_LETTER = "letter"
        const val KIND_MORNING = "morning"
        const val KIND_NIGHT = "night"

        /** How far ahead a note's wake is shown the calendar. */
        private const val GLANCE_HOURS = 24L

        /** Messages looked at to tell whether the person is in the conversation. */
        private const val RECENT = 30

        /** Messages one notification carries at most: the newest. */
        private const val NOTIFY_MAX = 10

        /** Wakes kept per TA, for the line in settings. */
        private const val KEPT = 20
        private const val DETAIL_MAX = 200
    }
}

/** Runs one note, letter or greeting when its time comes (see [Later]). */
class LaterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val later = (applicationContext as CleosApp).container.later
        val id = inputData.getLong(Later.KEY_ID, -1)
        val kind = inputData.getString(Later.KEY_KIND) ?: Later.KIND_WAKE
        val wait = when (kind) {
            Later.KIND_LETTER -> later.letterArrived(id)
            Later.KIND_MORNING -> later.greet(Greeting.Morning)
            Later.KIND_NIGHT -> later.greet(Greeting.Night)
            else -> later.wake(id)
        }
        if (wait != null) later.again(kind, id, wait)
        return Result.success()
    }
}
