package com.cleo.cleos.ai
import androidx.room.withTransaction

import android.util.Log
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.SecretStore
import com.cleo.cleos.data.SettingsRepository
import com.cleo.cleos.data.StickerText
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.data.db.MemoryEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** When the person wants the reply to a letter, picked as they send it. */
enum class ReplyWhen(val key: String, val label: String) {
    Soon("soon", "写好就到"),
    Hour("hour", "一小时内"),
    Hours("hours", "几个小时"),
    Morning("morning", "明早"),
    ;

    companion object {
        /** A stored choice; nothing, or one this version doesn't know, is the hours. */
        fun of(key: String?): ReplyWhen = entries.firstOrNull { it.key == key } ?: Hours
    }
}

/**
 * When a TA's letter arrives. For a reply the person picks ([ReplyWhen]); within the choice
 * the wait is random, so it is not read as a timer ("half an hour after I send, every
 * time"), and seeded by the letter, so it comes out the same however often it is worked out.
 */
object LetterTiming {
    private val NIGHT_START: LocalTime = LocalTime.of(23, 0)
    private val MORNING: LocalTime = LocalTime.of(8, 0)

    /** When the reply to a letter sent at [sentAt] arrives. Hours unless another was picked. */
    fun replyAt(sentAt: Long, seed: Long, zone: ZoneId, choice: ReplyWhen = ReplyWhen.Hours): Long {
        val r = Random(seed)
        return when (choice) {
            // Picked by someone who is up and waiting for it: the night doesn't hold these back.
            ReplyWhen.Soon -> sentAt
            ReplyWhen.Hour -> sentAt + r.nextLong(Duration.ofMinutes(10).toMillis(), Duration.ofHours(1).toMillis() + 1)
            ReplyWhen.Hours -> awake(sentAt + r.nextLong(Duration.ofHours(1).toMillis(), Duration.ofHours(6).toMillis() + 1), r, zone)
            ReplyWhen.Morning -> morningAfter(sentAt, zone) + r.nextLong(0, Duration.ofHours(1).toMillis())
        }
    }

    /** The choice as the person reads it before sending. */
    fun describe(choice: ReplyWhen, now: Long, zone: ZoneId): String = when (choice) {
        ReplyWhen.Soon -> "回信一写好就到，夜里也是。"
        ReplyWhen.Hour -> "回信 1 小时以内到，夜里也是。"
        ReplyWhen.Hours -> "回信过 1～6 小时到，夜里的等到早上。"
        ReplyWhen.Morning -> {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val day = Instant.ofEpochMilli(morningAfter(now, zone)).atZone(zone).toLocalDate()
            if (day == today) "回信早上 8～9 点到。" else "回信明天早上 8～9 点到。"
        }
    }

    /**
     * A letter the TA wrote of their own accord: 25 to 100 minutes after it was written,
     * after the person has put the phone down, not as the answer to something they did.
     */
    fun ownAt(writtenAt: Long, seed: Long, zone: ZoneId): Long {
        val r = Random(seed)
        return awake(writtenAt + r.nextLong(Duration.ofMinutes(25).toMillis(), Duration.ofMinutes(100).toMillis() + 1), r, zone)
    }

    /** Nothing arrives in the night: what would, comes the next morning, some time after 8. */
    internal fun awake(at: Long, r: Random, zone: ZoneId): Long {
        val t = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
        if (t >= MORNING && t < NIGHT_START) return at
        return morningAfter(at, zone) + r.nextLong(0, Duration.ofHours(1).toMillis())
    }

    /** The first 8 o'clock after [at]: this morning's while it is still before 8, else tomorrow's. */
    private fun morningAfter(at: Long, zone: ZoneId): Long {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        val day = if (t.toLocalTime() < MORNING) t.toLocalDate() else t.toLocalDate().plusDays(1)
        return ZonedDateTime.of(day, MORNING, zone).toInstant().toEpochMilli()
    }
}

/**
 * When a TA writes a letter of their own. At least the gap the person set ([COOLDOWN] unless
 * changed) after their last try, whether it became a letter or not, so a TA with nothing to
 * say doesn't try every time the app opens. And only with something to write about since
 * their last letter: talk, or a diary entry. The model still decides; it can answer SKIP.
 *
 * Material counts from the last letter actually written, not the last try: counting from
 * the try would throw away what piled up every time the model skipped, and someone who
 * doesn't talk much would never collect enough.
 */
object LetterRules {
    val COOLDOWN: Duration = Duration.ofDays(5)

    /** Things the person said since the last letter that make a letter worth trying. */
    const val ENOUGH_SAID = 20

    fun ready(
        now: Long,
        ta: CompanionEntity,
        letters: List<LetterEntity>,
        saidSince: Int,
        diarySince: Int,
        cooldown: Duration = COOLDOWN,
    ): Boolean {
        val own = letters.filter { it.author == LetterEntity.AUTHOR_AI && it.sourceType == LetterEntity.RETROSPECTIVE }
        // One waiting to be read (or still on its way) is enough; a second would pile up.
        if (own.any { it.readAt == null }) return false
        val last = maxOf(ta.lastLetterTry ?: 0L, own.maxOfOrNull { it.createdAt } ?: 0L, ta.createdAt)
        if (now - last < cooldown.toMillis()) return false
        return saidSince >= ENOUGH_SAID || diarySince > 0
    }

    /** Where material counts from: the TA's last letter, or when they came to be. */
    fun materialSince(ta: CompanionEntity, letters: List<LetterEntity>): Long =
        letters.filter { it.author == LetterEntity.AUTHOR_AI && it.sourceType == LetterEntity.RETROSPECTIVE }.maxOfOrNull { it.createdAt } ?: ta.createdAt
}

/**
 * What a TA is told when writing a letter. The rules are the ones the person's earlier app
 * settled on: one or two real things instead of a summary, no padding, no letter
 * templates, no lists; the relationship is not named. The TA's persona comes along, since
 * the letter is theirs, but its chat habits (short messages, one thing per message) don't
 * apply to a letter, and it is told so.
 */
object LetterPrompt {
    const val SKIP = "SKIP"

    fun system(
        settings: AppSettings,
        ta: CompanionEntity,
        own: Boolean,
        memories: List<MemoryEntity> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildList {
        if (ta.name.isNotBlank()) add("你叫${ta.name.trim()}。")
        if (settings.userName.isNotBlank()) add("对方叫${settings.userName.trim()}。")
        if (ta.persona.isNotBlank()) add(ta.persona.trim())
        // No tools in a letter, so the details come along with each topic.
        MemoryDigest.forLetter(memories, zone)?.let(::add)
        add(
            "你正在给对方写一封信。信不是聊天：它慢、有距离，可以说些平时聊天里不会说的话。" +
                "不要预设你们是什么关系，也别给这段关系起名字，那由你们相处的方式决定。",
        )
        add(
            buildString {
                append("写法：\n")
                append("- 挑一两件具体的事写，不要流水账式地总结这段时间。\n")
                append("- 长度你自己定。有话就多写，没什么可写就三五行，不要凑。\n")
                append("- 怎么开头、怎么称呼对方都随你，但用你平时叫对方的方式，不要套「亲爱的某某」这类信件模板话。\n")
                append("- 不要写标题、日期和落款，界面会显示。\n")
                append("- 不要用列表和分点，也不用 Markdown。信就该像信。\n")
                append("- 上面如果有聊天时的说话习惯（比如说话简短、一条只说一件事），写信时不用照着，按这里的写法。")
                // Only a letter of its own may be skipped: someone who wrote gets an answer.
                if (own) append("\n- 如果这段时间实在没什么值得写的，就只回复 $SKIP 四个字母，不要勉强写一封。")
            },
        )
    }.joinToString("\n\n")

    /**
     * The material, in the user message: the earlier letters, the one being answered, what
     * was said lately, and diary entries since the last letter (the person's only when the
     * TA may read their diary; secrets never).
     */
    fun material(
        now: ZonedDateTime,
        letters: List<LetterEntity>,
        replyTo: LetterEntity?,
        said: List<MessageEntity>,
        diary: List<DiaryEntryEntity>,
        zone: ZoneId,
    ): String = buildString {
        append(Prompt.timeLine(now)).append("。\n\n")
        val earlier = letters.filter { !it.draft && it.id != replyTo?.id && (it.author == LetterEntity.AUTHOR_ME || (it.deliverAt ?: 0) <= now.toInstant().toEpochMilli()) }
            .sortedBy { it.createdAt }
            .takeLast(EARLIER_LETTERS)
        if (earlier.isNotEmpty()) {
            append("你们之前的信（从早到晚）：\n")
            for (l in earlier) {
                val who = if (l.author == LetterEntity.AUTHOR_AI) "你写的" else "对方写的"
                append("【").append(who).append(" · ").append(day(l.createdAt, zone)).append("】\n")
                append(l.content.trim().take(LETTER_MAX)).append("\n\n")
            }
        }
        if (replyTo != null) {
            append("对方刚寄来这封信（").append(day(replyTo.deliverAt ?: replyTo.createdAt, zone)).append("）：\n")
            append(replyTo.content.trim()).append("\n\n")
        }
        if (said.isNotEmpty()) {
            append("最近你们聊的（从早到晚）：\n")
            for (m in said.sortedBy { it.createdAt }) {
                append(if (m.role == "user") "对方：" else "你：").append(StickerText.plain(m.content).trim().replace('\n', ' ').take(SAID_MAX)).append('\n')
            }
            append('\n')
        }
        val own = diary.filter { it.author == DiaryEntryEntity.AUTHOR_AI }
        val theirs = diary.filter { it.author == DiaryEntryEntity.AUTHOR_ME && !it.secret }
        if (own.isNotEmpty()) {
            append("你上一封信之后写的日记：\n")
            own.forEach { append(entry(it)) }
            append('\n')
        }
        if (theirs.isNotEmpty()) {
            append("对方上一封信之后写的日记：\n")
            theirs.forEach { append(entry(it)) }
            append('\n')
        }
        append(if (replyTo != null) "给对方写一封回信。" else "给对方写一封信。")
    }

    /** A short answer with the word in it: the model doesn't always send only the four letters ("SKIP（没什么可写）"). */
    fun isSkip(text: String): Boolean = text.trim().length < 30 && SKIP in text.uppercase()

    private fun entry(e: DiaryEntryEntity): String {
        val text = DiaryBlocks.plainText(DiaryBlocks.decode(e.blocks)).trim().replace('\n', ' ').take(DIARY_MAX)
        val title = e.title.trim().takeIf { it.isNotEmpty() }?.let { "「$it」" }.orEmpty()
        return "- ${LocalDate.ofEpochDay(e.day).let { "${it.monthValue}月${it.dayOfMonth}日" }}$title $text\n"
    }

    private fun day(at: Long, zone: ZoneId): String = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().let { "${it.monthValue}月${it.dayOfMonth}日" }

    private const val EARLIER_LETTERS = 4
    private const val LETTER_MAX = 1200
    private const val SAID_MAX = 150
    private const val DIARY_MAX = 300
    const val SAID_LATELY = 30
    const val DIARY_LATELY = 5
}

/**
 * Letters: drafts, sending, and the TA's side. A TA's letter is written ahead of time and
 * stored with the time it arrives; the mailbox shows it from then on. The writing waits for no
 * background job: phones kill those. Whatever is due gets written the next time the app
 * comes to the front ([tick]). Only the notification that it has arrived is left to the
 * background ([written]): if the phone delays or drops it, the letter is in the mailbox anyway.
 */
class Letters(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val client: ChatClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** A TA's letter was written, to arrive at its deliverAt. */
    private val written: (LetterEntity) -> Unit = {},
) {
    private val writing = Mutex()

    /** A draft saved: a new one gets an id, an existing one is updated. Sent letters stay as they are. */
    suspend fun saveDraft(companionId: Long, id: Long, content: String): Long {
        val now = clock()
        if (id == 0L) {
            return db.letters().insert(LetterEntity(companionId = companionId, author = LetterEntity.AUTHOR_ME, content = content, createdAt = now))
        }
        val draft = db.letters().get(id)?.takeIf { it.draft && it.companionId == companionId && it.sourceType == LetterEntity.RETROSPECTIVE } ?: return id
        db.letters().update(draft.copy(content = content, createdAt = now))
        return id
    }

    /**
     * Sends a draft, with when its reply should come. The reply is written right after, to
     * arrive then. Waits for a tick already running rather than skipping: that one has
     * already listed what to answer.
     */
    suspend fun send(id: Long, choice: ReplyWhen = ReplyWhen.Hours) {
        val draft = db.letters().get(id)?.takeIf { it.draft && it.sourceType == LetterEntity.RETROSPECTIVE && it.content.isNotBlank() } ?: return
        val now = clock()
        val reply = LetterTiming.replyAt(now, seed = id, zone = zone(), choice = choice)
        db.letters().update(draft.copy(content = draft.content.trim(), createdAt = now, deliverAt = now, replyDueAt = reply))
        scope.launch { writing.withLock { due() } }
    }

    suspend fun markRead(id: Long) {
        db.letters().markRead(id, clock())
    }

    suspend fun delete(id: Long) = db.withTransaction {
        db.letters().unlink(id)
        db.letters().detachReplies(id)
        db.letters().delete(id)
    }

    /**
     * Writes what is due: replies to letters still unanswered, then, where the time has
     * come, a letter of a TA's own. One at a time; a tick while one runs does nothing.
     */
    fun tick() {
        if (!writing.tryLock()) return
        scope.launch {
            try {
                due()
            } finally {
                writing.unlock()
            }
        }
    }

    private suspend fun due() {
        try {
            answer()
            writeOwn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, a model that failed: what is due stays due, for the next tick.
            Log.w(TAG, "letters: ${e.message}")
        }
    }

    private suspend fun answer() {
        for (sent in db.letters().unanswered()) {
            val ta = db.companions().get(sent.companionId) ?: continue
            val text = write(ta, sent) ?: continue
            // Picked when it was sent. One sent before there was a choice gets the hours.
            val at = sent.replyDueAt ?: LetterTiming.replyAt(sent.deliverAt ?: sent.createdAt, seed = sent.id, zone = zone())
            val reply = LetterEntity(companionId = ta.id, author = LetterEntity.AUTHOR_AI, content = text, createdAt = clock(), deliverAt = at, replyTo = sent.id)
            written(reply.copy(id = db.letters().insert(reply)))
        }
    }

    private suspend fun writeOwn() {
        val s = settings.current()
        if (ToolGroup.Letters !in s.tools) return
        val cooldown = Duration.ofDays(s.letterEveryDays.coerceIn(1, 30).toLong())
        for (ta in db.companions().all()) {
            val letters = db.letters().allFor(ta.id)
            val since = LetterRules.materialSince(ta, letters)
            val own = if (ToolGroup.AiDiary in s.tools) ta.id else -1L
            val diary = db.diary().since(since, mine = ToolGroup.Diary in s.tools, own = own, limit = LetterPrompt.DIARY_LATELY)
            val now = clock()
            if (!LetterRules.ready(now, ta, letters, db.messages().saidSince(ta.id, since), diary.size, cooldown)) continue
            val text = write(ta, null) ?: continue
            db.companions().get(ta.id)?.let { db.companions().update(it.copy(lastLetterTry = now)) }
            if (LetterPrompt.isSkip(text)) continue
            val letter = LetterEntity(companionId = ta.id, author = LetterEntity.AUTHOR_AI, content = text, createdAt = now, deliverAt = LetterTiming.ownAt(now, seed = now, zone = zone()))
            written(letter.copy(id = db.letters().insert(letter)))
        }
    }

    /** The letter's text, or null when it can't be written now (no key yet, nothing came back). */
    private suspend fun write(ta: CompanionEntity, replyTo: LetterEntity?): String? {
        val key = secrets.key(ta.apiBaseUrl)?.takeIf { it.isNotBlank() } ?: return null
        val s = settings.current()
        val z = zone()
        val letters = db.letters().allFor(ta.id)
        val since = LetterRules.materialSince(ta, letters)
        val own = if (ToolGroup.AiDiary in s.tools) ta.id else -1L
        val diary = db.diary().since(since, mine = ToolGroup.Diary in s.tools, own = own, limit = LetterPrompt.DIARY_LATELY)
        val messages = listOf(
            ApiMessage(
                "system",
                LetterPrompt.system(
                    s,
                    ta,
                    own = replyTo == null,
                    memories = if (ToolGroup.Memory in s.tools) db.memories().allFor(ta.id) else emptyList(),
                    zone = z,
                ),
            ),
            ApiMessage(
                "user",
                LetterPrompt.material(
                    now = Instant.ofEpochMilli(clock()).atZone(z),
                    letters = letters,
                    replyTo = replyTo,
                    said = db.messages().saidLately(ta.id, LetterPrompt.SAID_LATELY),
                    diary = diary,
                    zone = z,
                ),
            ),
        )
        val text = StringBuilder()
        client.stream(ApiEndpoint(ta.apiBaseUrl, key, ta.apiModel), messages).collect { if (it is ChatEvent.Delta) text.append(it.text) }
        return text.toString().trim().ifEmpty { null }
    }

    private companion object {
        const val TAG = "Letters"
    }
}
