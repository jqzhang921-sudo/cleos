package com.cleo.cleos.ai

import android.util.Log
import androidx.room.withTransaction
import com.cleo.cleos.data.CallRecords
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.MessageQuotes
import com.cleo.cleos.data.SecretStore
import com.cleo.cleos.data.SettingsRepository
import com.cleo.cleos.data.StickerText
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.ConversationEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Continuity past the window. The newest messages go to the model as they are; what falls
 * out of that window is first folded into a running summary, the recap, which goes along
 * with every message after. A long conversation keeps its thread instead of losing its
 * start all at once.
 *
 * The window moves in steps, not one message at a time: it grows by [step] messages, then
 * the oldest are folded and it is back to its size. In between, every request starts the
 * same way and only grows at the end, so an endpoint that caches prefixes (DeepSeek does)
 * keeps hitting its cache.
 */
object Recap {
    /** The length the model is asked to keep a recap to, in characters. */
    const val LENGTH = 1500

    /** The most kept of a recap, whoever wrote it. */
    const val MAX_STORED = 4000

    /** Messages folded in one request at most. */
    const val MAX_FOLD = 150

    /**
     * A conversation from before there were recaps (an import, say) gets only its last this
     * many messages before the window folded in. What came before them was already out of
     * the window, and what mattered of it is in the TA's memory.
     */
    const val CATCH_UP = 600

    private const val LINE_MAX = 400

    /** A quoted message in a line of the transcript: enough to tell which one. */
    private const val QUOTE_MAX = 40
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val HEADINGS = listOf("【新的前情提要】", "【前情提要】", "新的前情提要：", "前情提要：", "前情提要:")

    /** How far past the window messages pile up before they are folded. */
    fun step(window: Int): Int = maxOf(10, window / 2)

    /** What goes to the model verbatim: what isn't folded yet, but no more than the window and a step. */
    fun sent(live: List<MessageEntity>, window: Int): List<MessageEntity> = live.takeLast(window + step(window))

    /**
     * What to fold now, oldest first; nothing while the window has room. Folding leaves the
     * window's worth, and a fold ends right before one of the person's messages: the window
     * then opens on their turn, and no reply is parted from what it answers.
     */
    fun toFold(live: List<MessageEntity>, window: Int): List<MessageEntity> {
        if (live.size <= window + step(window)) return emptyList()
        var end = live.size - window
        while (end > 0 && live[end].role != "user") end--
        if (end == 0) return emptyList()
        val start = maxOf(0, end - CATCH_UP)
        // A long backlog goes in pieces, each also ending before one of theirs.
        var stop = minOf(end, start + MAX_FOLD)
        while (stop < end && live[stop].role != "user") stop++
        return live.subList(start, stop).toList()
    }

    /** The request that folds [batch] into [recap]: what the TA kept so far, and what came after. */
    fun request(ta: CompanionEntity, userName: String, recap: String?, batch: List<MessageEntity>, zone: ZoneId): List<ApiMessage> {
        val them = userName.trim().ifEmpty { "对方" }
        val system = buildString {
            if (ta.name.isNotBlank()) append("你是${ta.name.trim()}。")
            append("你和${them}一直在手机上聊天。聊得久了，早先的聊天记录不会再原样给你看，你靠自己记的「前情提要」接着聊。")
            append("现在把下面这段新的聊天记录并进前情提要，写出新的一版。\n\n")
            append("写法：\n")
            append("- 用「我」称呼自己，用「$them」称呼对方。\n")
            append("- 按时间顺序写发生过的事：具体的事，${them}的状态和心情，约好的事，还没聊完的话题，你们之间的称呼和玩笑。带上日期。\n")
            append("- 越早的写得越简略，最近的留细一点。旧的前情提要里还重要的要留着，可以压缩。\n")
            append("- 不逐句复述，不评价，不写开头语和总结语，不用 Markdown。\n")
            append("- 不超过 $LENGTH 字。只输出前情提要本身。")
        }
        val user = buildString {
            append("【旧的前情提要】\n").append(recap?.trim()?.ifEmpty { null } ?: "（还没有）").append("\n\n")
            append("【接下来的聊天记录】\n").append(transcript(batch, zone))
        }
        return listOf(ApiMessage("system", system), ApiMessage("user", user))
    }

    /** The model's answer as a recap: without a heading it may have put on top, and not too long. */
    fun clean(text: String): String? {
        var t = text.trim()
        for (h in HEADINGS) t = t.removePrefix(h).trimStart()
        return t.trim().take(MAX_STORED).ifEmpty { null }
    }

    /** The messages as the model reads them to fold: day by day, with the time and who said it. */
    fun transcript(batch: List<MessageEntity>, zone: ZoneId): String = buildString {
        var day: LocalDate? = null
        for (m in batch) {
            val line = lineOf(m) ?: continue
            val t = Instant.ofEpochMilli(m.createdAt).atZone(zone)
            val d = t.toLocalDate()
            if (d != day) {
                day = d
                append(d.monthValue).append('月').append(d.dayOfMonth).append("日\n")
            }
            append(t.format(TIME)).append(' ').append(line).append('\n')
        }
    }.trimEnd()

    private fun lineOf(m: MessageEntity): String? {
        fun said(text: String) = StickerText.plain(text).trim().replace('\n', ' ').take(LINE_MAX)
        // Said on the phone: marked on each line, since a fold can start in the middle of a call.
        val phone = if (m.call != null) "（电话里）" else ""
        return when {
            m.error != null -> null
            // A call: where it began, and how long it went on. One where nothing was said is nothing.
            m.role == "call" -> CallRecords.decode(m.content)?.talkedMs?.let { "（对方打来电话，打了${CallRecords.spoken(it)}）" }
            // The person's answer to a request for a secret: the line the chat shows, not the entry.
            m.role == "user" && m.note != null -> "（${m.note}）"
            m.role == "user" -> {
                val pictures = MessageImages.decode(m.images).size
                val words = said(m.content).ifEmpty { null }?.let { if (m.audio != null) "（语音）$it" else it }
                listOfNotNull(words, if (pictures > 0) "[发了${pictures}张图]" else null)
                    .joinToString(" ")
                    .ifEmpty { null }
                    ?.let { "对方$phone：${answering(m)}$it" }
            }
            m.role == "assistant" && m.content.isNotBlank() -> "我$phone：${answering(m)}${said(m.content)}"
            else -> null
        }
    }

    /** Which earlier message [m] answers, when it quotes one: 「回我说的“…”」, before its words. */
    private fun answering(m: MessageEntity): String {
        val q = MessageQuotes.decode(m.quote) ?: return ""
        val words = q.text.replace('\n', ' ').take(QUOTE_MAX)
        val whose = if (q.role == "assistant") "我" else "对方"
        return "（回${whose}说的“$words”）"
    }

    /** The recap as the TA reads it, at the end of the chat's system prompt. */
    fun forChat(recap: String?): String? = recap?.trim()?.ifEmpty { null }?.let {
        "【前情提要】更早的聊天不再原样给你看，这是那些聊天里发生过的事，你自己记的，比聊天记录粗。接着往下聊就好，不用提起前情提要：\n$it"
    }
}

/**
 * Folds conversations forward in the background, after a reply: one fold at a time per
 * conversation. A fold that fails (offline, no key yet) changes nothing; the window just
 * grows until the next reply tries again.
 */
class Recaps(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val client: ChatClient,
    private val scope: CoroutineScope,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val folding = ConcurrentHashMap.newKeySet<Long>()

    /** What of the conversation isn't folded yet, oldest first. */
    suspend fun live(conversation: ConversationEntity): List<MessageEntity> =
        db.messages().after(conversation.id, conversation.recapUntilAt ?: Long.MIN_VALUE, conversation.recapUntilId ?: Long.MIN_VALUE)

    /** Folds what piled up past the window, in the background; nothing while a fold of it runs. */
    fun foldLater(conversationId: Long) {
        if (!folding.add(conversationId)) return
        scope.launch {
            try {
                fold(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recap: ${e.message}")
            } finally {
                folding.remove(conversationId)
            }
        }
    }

    private suspend fun fold(conversationId: Long) {
        repeat(MAX_ROUNDS) {
            val conversation = db.conversations().get(conversationId) ?: return
            val ta = db.companions().get(conversation.companionId) ?: return
            val s = settings.current()
            val batch = Recap.toFold(live(conversation), s.historySize)
            if (batch.isEmpty()) return
            val key = secrets.key(ta.apiBaseUrl)?.takeIf { it.isNotBlank() } ?: return
            val text = StringBuilder()
            client.stream(ApiEndpoint(ta.apiBaseUrl, key, ta.apiModel), Recap.request(ta, s.userName, conversation.recap, batch, zone()))
                .collect { if (it is ChatEvent.Delta) text.append(it.text) }
            val recap = Recap.clean(text.toString()) ?: return
            val last = batch.last()
            // Kept only if the recap wasn't edited meanwhile; if it was, the next round folds on top of the edit.
            db.withTransaction {
                // A message edit or deletion during the request also invalidates this summary.
                if (batch.all { db.messages().get(it.id) == it }) {
                    db.conversations().foldRecap(conversationId, recap, last.createdAt, last.id, was = conversation.recap)
                }
            }
        }
    }

    private companion object {
        const val TAG = "Recaps"

        /** Folds in one go at most: a backlog of [Recap.CATCH_UP] messages takes four or five. */
        const val MAX_ROUNDS = 8
    }
}
