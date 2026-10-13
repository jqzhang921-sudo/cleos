package com.cleo.cleos.ai

import com.cleo.cleos.data.db.GroupMemoryEntity
import com.cleo.cleos.data.db.GroupMessageEntity
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId

/** Bounded local retrieval; only the current group's user-curated notes are eligible. */
object GroupMemoryRules {
    const val MAX_NOTES = 100
    const val MAX_TITLE = 60
    const val MAX_CONTENT = 2000
    const val MAX_CONTEXT = 6000
    const val MAX_SELECTED = 12
    private const val HEADER = "[本群的小本本：用户整理的参考记录]\n"
    fun title(raw: String) = raw.trim().also { require(it.isNotEmpty() && it.length <= MAX_TITLE) { "标题需要 1–$MAX_TITLE 字" } }
    fun content(raw: String) = raw.trim().also { require(it.isNotEmpty() && it.length <= MAX_CONTENT) { "记录需要 1–$MAX_CONTENT 字" } }
    fun canRemember(row: GroupMessageEntity) = row.status == "sent" && row.role in setOf("user", "assistant") && row.content.isNotBlank()
    fun source(groupId: Long, row: GroupMessageEntity) {
        require(row.groupId == groupId && canRemember(row)) { "只能记下本群已经发送的文字" }
    }
    private fun terms(raw: String): Set<String> {
        val lower = raw.lowercase(java.util.Locale.ROOT)
        val words = Regex("[a-z0-9]{2,}|[\\p{IsHan}]+").findAll(lower).flatMap { match ->
            val s = match.value
            if (s.first().code > 127 && s.length > 1) s.windowed(2).asSequence() else sequenceOf(s)
        }.toSet()
        return words - setOf("我们", "你们", "他们", "什么", "可以", "这个", "那个", "一下", "记得", "the", "please", "that", "this")
    }
    fun selected(groupId: Long, notes: List<GroupMemoryEntity>, query: String): List<GroupMemoryEntity> {
        val wanted = terms(query.take(GroupChatRules.MAX_TEXT))
        return notes.filter { it.groupId == groupId }
            .sortedWith(compareByDescending<GroupMemoryEntity> { row ->
                val titleTerms = terms(row.title)
                val contentTerms = terms(row.content)
                fun matches(term: String, text: String, tokens: Set<String>) = term in tokens ||
                    (term.length == 1 && term.first().code > 127 && text.contains(term))
                wanted.count { matches(it, row.title, titleTerms) } * 3 + wanted.count { matches(it, row.content, contentTerms) }
            }.thenByDescending { it.updatedAt }.thenByDescending { it.id })
            .take(MAX_SELECTED)
    }
    fun sourceLabel(note: GroupMemoryEntity, row: GroupMessageEntity?): String {
        if (note.sourceKind == "manual") return "手动记下"
        val original = row?.takeIf { it.id == note.sourceMessageId && it.groupId == note.groupId }
            ?: return "来自群消息 · 原消息已移除"
        val author = if (original.role == "user") "用户" else original.speakerName.ifBlank { "TA" }
        return "来自${author}的群消息"
    }
    fun context(groupId: Long, notes: List<GroupMemoryEntity>, query: String, sources: List<GroupMessageEntity>): String {
        val original = sources.filter { it.groupId == groupId }.associateBy { it.id }
        val entries = mutableListOf<JsonObject>()
        for (row in selected(groupId, notes, query)) {
            val source = original[row.sourceMessageId]
            val entry = buildJsonObject {
                put("记录编号", row.id); put("标题", row.title); put("内容", row.content)
                put("整理时间", Instant.ofEpochMilli(row.updatedAt).atZone(ZoneId.systemDefault()).toString())
                put("来源", sourceLabel(row, source))
                if (source != null) {
                    put("原消息编号", source.id); put("原发言角色", source.role)
                    source.speakerId?.let { put("原作者成员编号", it) }
                    put("原发言时间", Instant.ofEpochMilli(source.createdAt).atZone(ZoneId.systemDefault()).toString())
                }
            }
            // Keep each selected record whole. Do not silently cut away a correction or its source.
            val candidate = JsonArray(entries + entry).toString()
            if (HEADER.length + candidate.length <= MAX_CONTEXT) entries += entry
        }
        return if (entries.isEmpty()) "" else "$HEADER${JsonArray(entries)}"
    }
}
