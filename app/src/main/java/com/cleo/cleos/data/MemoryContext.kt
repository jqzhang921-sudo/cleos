package com.cleo.cleos.data

import com.cleo.cleos.data.db.MemoryEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.LocalDate

@Serializable
data class MemoryTrace(
    val target: String,
    val conversationId: Long,
    val messageId: Long,
    val messageHash: String,
    val at: Long,
    val role: String,
    /** A chat anchor is context, not a claim that every fact was explicitly said here. */
    val quoted: Boolean = false,
    val quoteStart: Int = 0,
    val quoteEnd: Int = 0,
)

object MemoryContext {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MemoryTrace.serializer())
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun messageHash(m: MessageEntity) = hash("${m.conversationId}:${m.id}:${m.createdAt}:${m.role}:${m.content}")
    fun eligible(m: MessageEntity) = m.id > 0 && m.conversationId > 0 && m.role in setOf("user", "assistant") &&
        m.note == null && m.error == null && m.letterId == null && m.content.isNotBlank()
    fun targets(m: MemoryEntity) = listOf("summary:" + hash(m.summary)) +
        MemoryDetails.decode(m.details).map { "detail:" + hash(it) }
    fun traces(m: MemoryEntity): List<MemoryTrace> = decode(m.provenance).filter { it.target in targets(m) }
    fun decode(raw: String): List<MemoryTrace> = if (raw.length > 24_000) emptyList() else runCatching {
        json.decodeFromString(serializer, raw).takeIf { rows -> rows.size <= 31 && rows.all {
            it.target.matches(Regex("(summary|detail):[0-9a-f]{64}")) && it.messageHash.matches(Regex("[0-9a-f]{64}")) &&
                it.messageId > 0 && it.conversationId > 0 && it.at >= 0 && it.role in setOf("user", "assistant") &&
                it.quoteStart >= 0 && (if (it.quoted) it.quoteEnd > it.quoteStart else it.quoteStart == 0 && it.quoteEnd == 0)
        } && rows.map { it.target }.distinct().size == rows.size }.orEmpty()
    }.getOrDefault(emptyList())

    fun trace(message: MessageEntity, quote: String? = null): MemoryTrace {
        require(eligible(message))
        val q = quote?.trim().orEmpty()
        val start = if (q.isEmpty()) 0 else message.content.indexOf(q)
        require(start >= 0) { "摘录与原消息不一致" }
        return MemoryTrace("", message.conversationId, message.id, messageHash(message), message.createdAt,
            message.role, q.isNotEmpty(), start, start + q.length)
    }
    /** Changes lose their old attribution. Unchanged facts retain theirs; new facts get this turn's anchor. */
    fun revised(before: MemoryEntity?, next: MemoryEntity, source: MemoryTrace? = null): MemoryEntity {
        val kept = before?.let(::traces).orEmpty().associateBy { it.target }
        val oldTargets = before?.let(::targets).orEmpty()
        val traces = targets(next).distinct().mapNotNull { target ->
            kept[target] ?: source?.takeIf { target !in oldTargets }?.copy(target = target)
        }
        return next.copy(provenance = json.encodeToString(serializer, traces))
    }
    fun matches(trace: MemoryTrace, message: MessageEntity) = eligible(message) &&
        trace.messageId == message.id && trace.conversationId == message.conversationId && trace.role == message.role && trace.at == message.createdAt &&
        trace.messageHash == messageHash(message) && trace.quoteEnd <= message.content.length
    fun day(raw: String): Long? = raw.trim().takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it).toEpochDay() }
    fun date(day: Long?) = day?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()
    fun valid(m: MemoryEntity) = m.temporalState in setOf("current", "past") && runCatching {
        m.validFromDay?.let { LocalDate.ofEpochDay(it) }; m.validUntilDay?.let { LocalDate.ofEpochDay(it) }
        m.validFromDay == null || m.validUntilDay == null || m.validFromDay <= m.validUntilDay
    }.getOrDefault(false)
    fun status(m: MemoryEntity, today: LocalDate = LocalDate.now()): String = when {
        m.temporalState == "past" -> "过去的经历"
        m.validUntilDay != null && today.toEpochDay() > m.validUntilDay -> "已过适用日期 · 历史参考"
        m.validFromDay != null && today.toEpochDay() < m.validFromDay -> "尚未到适用日期"
        m.kind == "recent" && m.validUntilDay == null -> "近期记录 · 是否仍适用需结合近况"
        else -> "当前参考"
    }
    fun timing(m: MemoryEntity, today: LocalDate = LocalDate.now()): String = buildString {
        append(status(m, today))
        if (m.validFromDay != null || m.validUntilDay != null)
            append("（适用日期：${date(m.validFromDay).ifEmpty { "未注明" }} 至 ${date(m.validUntilDay).ifEmpty { "未注明" }}）")
    }
    fun validateBackup(rows: List<MemoryEntity>, owners: Set<Long>) {
        require(rows.all { it.companionId in owners && valid(it) &&
            it.provenance.length <= 24_000 && (it.provenance == "[]" || decode(it.provenance).isNotEmpty()) }) {
            "记忆的归属、时间或来源格式无效"
        }
    }
}
