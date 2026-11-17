package com.cleo.cleos.data

import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Resolved, local-only visual parameters. A sent letter never looks up a mutable default. */
@Serializable
data class EnvelopeSnapshot(
    val id: String = "ink-white", val version: Int = 1,
    val name: String = "墨白简约", val envelope: Long = 0xFFF0EBE5,
    val paper: Long = 0xFFFFFCF7, val ink: Long = 0xFF332F35,
    val seal: Long = 0xFF7E658D,
    val envelopeInk: Long = ink,
    val flap: String = "v",
    val decoration: String = "none",
    val sealMark: String = "信",
    val paperPattern: String = "plain",
)

object InstantLetterData {
    const val MAX_BODY = 1_000_000
    const val MAX_TITLE = 200
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val defaultEnvelope = EnvelopeSnapshot()
    fun encode(theme: EnvelopeSnapshot) = json.encodeToString(theme)
    fun theme(raw: String?): EnvelopeSnapshot? = raw?.let { runCatching { json.decodeFromString<EnvelopeSnapshot>(it) }.getOrNull() }
        ?.takeIf { it.id.length in 1..100 && it.version > 0 && it.name.length <= 100 &&
            listOf(it.envelope, it.paper, it.ink, it.seal, it.envelopeInk).all { c -> c in 0..0xFFFFFFFFL && (c ushr 24) == 255L } &&
            it.flap in EnvelopeThemes.FLAPS && it.decoration in EnvelopeThemes.DECORATIONS && it.sealMark in EnvelopeThemes.SEALS && it.paperPattern in EnvelopeThemes.PAPERS }

    fun content(letter: LetterEntity, maximum: Int = 12_000): String = buildString {
        append("（信件 #${letter.id} · ${if (letter.author == LetterEntity.AUTHOR_ME) "对方寄给你的" else "你寄出的"}）\n")
        if (letter.title.isNotBlank()) append(letter.title).append('\n')
        val end = minOf(letter.content.length, maximum).let {
            if (it > 0 && it < letter.content.length && Character.isHighSurrogate(letter.content[it - 1]) && Character.isLowSurrogate(letter.content[it])) it - 1 else it
        }
        append(letter.content.take(end))
        if (letter.content.length > end) append("\n（正文较长，此处显示前 $end 字；其余正文仍保存在信件中。）")
    }

    /** Resolve at the boundary, never copy the canonical body into stored chat messages. */
    fun resolve(rows: List<MessageEntity>, letters: List<LetterEntity>, owner: Long, now: Long = System.currentTimeMillis()): List<MessageEntity> {
        val valid = letters.filter { it.companionId == owner && it.sourceType == LetterEntity.INSTANT && !it.draft &&
            (it.author == LetterEntity.AUTHOR_ME || (it.deliverAt ?: Long.MAX_VALUE) <= now) }.associateBy { it.id }
        return rows.map { m ->
            if (m.letterId == null) m else valid[m.letterId]
                ?.takeIf { (m.role == "user") == (it.author == LetterEntity.AUTHOR_ME) && m.role in setOf("user", "assistant") }
                ?.let { m.copy(content = content(it)) }
                ?: m.copy(role = "note", content = "信件不可用", note = "信件不可用")
        }
    }

    fun validate(letters: List<LetterEntity>, messages: List<MessageEntity>, owners: Map<Long, Long>) {
        require(letters.map { it.id }.distinct().size == letters.size) { "信件编号重复" }
        val byId = letters.associateBy { it.id }
        letters.forEach { l ->
            require(l.sourceType in setOf(LetterEntity.INSTANT, LetterEntity.RETROSPECTIVE)) { "信件类型无效" }
            if (l.sourceType == LetterEntity.INSTANT) {
                require(l.id > 0 && l.companionId > 0 && l.replyTo != l.id && l.author in setOf(LetterEntity.AUTHOR_ME, LetterEntity.AUTHOR_AI) && l.title.length <= MAX_TITLE && l.content.length <= MAX_BODY) { "即时信件内容无效" }
                require(l.author == LetterEntity.AUTHOR_ME || l.deliverAt != null) { "来信缺少投递时间" }
                require(theme(l.envelope) != null) { "信封样式缺失或无效" }
                require(l.draft || l.content.isNotBlank()) { "已寄出的信件为空" }
                l.replyTo?.let { reply -> require(byId[reply]?.companionId == l.companionId) { "回信对象不属于这个 TA" } }
            }
        }
        messages.filter { it.letterRequested }.forEach { m ->
            require(m.role == "user" && m.note == null && m.letterId == null && m.feedShare == null) { "写信请求标记无效" }
        }
        messages.filter { it.letterId != null }.forEach { m ->
            val l = requireNotNull(byId[m.letterId]) { "聊天关联的信件不存在" }
            require(l.sourceType == LetterEntity.INSTANT && !l.draft && owners[m.conversationId] == l.companionId &&
                m.role == (if (l.author == LetterEntity.AUTHOR_ME) "user" else "assistant") && m.content.isEmpty() &&
                m.note == null && m.feedShare == null && m.images == null && m.audio == null && m.call == null && m.toolCalls == null) { "信件与聊天的关联无效" }
        }
    }
}

/** Transactions arbitrate autosave, send, reopen and retries; no additional database. */
class InstantLetters(private val db: AppDatabase, private val settings: SettingsRepository? = null, private val clock: () -> Long = System::currentTimeMillis) {
    private val editing = Mutex()

    suspend fun draft(owner: Long, replyTo: Long? = null): LetterEntity = editing.withLock {
        db.withTransaction {
            check(db.companions().get(owner) != null) { "这位 TA 已经不在了" }
            db.letters().instantDraft(owner)?.let { return@withTransaction it }
            replyTo?.let { check(db.letters().get(it)?.let { l -> l.companionId == owner && !l.draft } == true) { "原信已不存在" } }
            val row = LetterEntity(companionId = owner, author = LetterEntity.AUTHOR_ME, content = "", createdAt = clock(),
                sourceType = LetterEntity.INSTANT, replyTo = replyTo, envelope = InstantLetterData.encode(EnvelopeThemes.default(settings?.current() ?: AppSettings())))
            row.copy(id = db.letters().insert(row))
        }
    }

    suspend fun save(id: Long, owner: Long, title: String, body: String): LetterEntity = editing.withLock {
        db.withTransaction {
            val row = db.letters().get(id) ?: error("草稿已不存在")
            check(row.companionId == owner && row.sourceType == LetterEntity.INSTANT) { "草稿不属于这位 TA" }
            check(row.draft) { "信已经寄出，不能再修改" }
            require(title.length <= InstantLetterData.MAX_TITLE && body.length <= InstantLetterData.MAX_BODY) { "信件太长，请先保留草稿并分封寄出" }
            row.copy(title = title, content = body, createdAt = clock()).also { db.letters().update(it) }
        }
    }

    data class Sent(val letter: LetterEntity, val message: MessageEntity, val fresh: Boolean)
    data class AiSent(val letterId: Long, val fresh: Boolean)

    suspend fun chooseEnvelope(id: Long, owner: Long, themeId: String): LetterEntity = editing.withLock {
        db.withTransaction {
            val row = db.letters().get(id) ?: error("草稿已不存在")
            check(row.companionId == owner && row.sourceType == LetterEntity.INSTANT && row.draft) { "已寄出的信件样式不能修改" }
            val theme = EnvelopeThemes.installed(settings?.current() ?: AppSettings()).firstOrNull { it.id == themeId } ?: error("信封主题已不存在")
            row.copy(envelope = InstantLetterData.encode(theme)).also { db.letters().update(it) }
        }
    }

    /** Only the host supplies origin/request identity; JSON arguments cannot grant permission. */
    suspend fun sendAi(owner: Long, ownerCreatedAt: Long, conversationId: Long, origin: String,
                       requestMessageId: Long?, title: String, body: String, themeId: String?, wake: Boolean,
                       beforeDelivery: suspend (Long, Long) -> Unit = { _, _ -> }): AiSent = editing.withLock {
        db.withTransaction {
            val ta = db.companions().get(owner)?.takeIf { it.createdAt == ownerCreatedAt } ?: error("这位 TA 已变化，没有寄出。")
            check(db.conversations().get(conversationId)?.companionId == owner) { "聊天已变化，没有寄出。" }
            check(origin.length in 1..200 && origin.startsWith("$owner:")) { "信件来源无效。" }
            db.letterDispatches().get(origin)?.let {
                check(it.companionId == owner) { "信件来源不匹配。" }
                return@withTransaction AiSent(it.letterId, false)
            }
            val requested = requestMessageId?.let { id ->
                val message = db.messages().get(id)?.takeIf { it.conversationId == conversationId }
                com.cleo.cleos.ai.InstantLetterRules.requested(message)?.takeIf { !wake }
            }
            check(requestMessageId == null || requested == requestMessageId) { "没有确认到用户的写信请求。" }
            val receipts = db.letterDispatches().forOwner(owner)
            val now = clock()
            if (wake) check(ta.freeTopicEnabled && !com.cleo.cleos.ai.IncomingCallRules.quiet(ta,java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()))) { "主动联系未开启或正在免打扰，没有寄出。" }
            com.cleo.cleos.ai.InstantLetterRules.held(ta.instantLetterMode, requested, receipts, now)?.let { error(it) }
            require(title.length <= InstantLetterData.MAX_TITLE && body.isNotBlank() && body.length <= InstantLetterData.MAX_BODY) { "信件正文为空或过长，没有寄出。" }
            check(!com.cleo.cleos.ai.InstantLetterRules.protocolPayload(body)) { "正文包含未解析的信件结构，没有寄出。" }
            check(com.cleo.cleos.ai.WakeOutput.inspect(body).text != null) { "没有可寄出的正文。" }
            val theme = EnvelopeThemes.forAi(settings?.current() ?: AppSettings(), themeId)
            val row = LetterEntity(companionId = owner, author = LetterEntity.AUTHOR_AI, content = body.trim(), createdAt = now,
                deliverAt = now, sourceType = LetterEntity.INSTANT, title = title.trim(), envelope = InstantLetterData.encode(theme))
            val letterId = db.letters().insert(row)
            // Tool receipts precede the visible envelope so API tool/result pairing stays valid.
            beforeDelivery(letterId, now)
            db.messages().insert(MessageEntity(conversationId = conversationId, role = "assistant", content = "", createdAt = now, letterId = letterId, proactive = wake))
            db.letterDispatches().insert(com.cleo.cleos.data.db.LetterDispatchEntity(origin, owner, letterId, now, requested))
            db.conversations().touch(conversationId, now)
            AiSent(letterId, true)
        }
    }

    suspend fun send(id: Long, owner: Long, ownerCreatedAt: Long, conversationId: Long): Sent = editing.withLock {
        db.withTransaction {
            check(db.companions().get(owner)?.createdAt == ownerCreatedAt) { "这位 TA 已经变了，请重新打开写信" }
            check(db.conversations().get(conversationId)?.companionId == owner) { "聊天已不存在，草稿仍保留" }
            val row = db.letters().get(id) ?: error("草稿已不存在")
            check(row.companionId == owner && row.sourceType == LetterEntity.INSTANT) { "信件对象不匹配" }
            if (!row.draft) return@withTransaction Sent(row, db.letters().linkedMessage(id) ?: error("这封信已经寄出，请到信箱查看"), false)
            require(row.content.isNotBlank()) { "写点内容再寄出吧" }
            check(InstantLetterData.theme(row.envelope) != null) { "信封样式不可用，草稿仍保留" }
            row.replyTo?.let { check(db.letters().get(it)?.companionId == owner) { "原信已被删除，草稿仍保留" } }
            val at = clock()
            // Preserve the preview's exact resolved appearance. No delayed legacy reply is scheduled.
            val sent = row.copy(title = row.title.trim(), createdAt = at, deliverAt = at)
            db.letters().update(sent)
            val message = MessageEntity(conversationId = conversationId, role = "user", content = "", createdAt = at, letterId = id)
            val messageId = db.messages().insert(message)
            db.conversations().touch(conversationId, at)
            Sent(sent, message.copy(id = messageId), true)
        }
    }
}
