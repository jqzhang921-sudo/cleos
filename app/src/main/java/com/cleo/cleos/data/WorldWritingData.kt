package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.*

object WorldWritingData {
    val kinds = linkedMapOf("note" to "随想", "draft" to "草稿", "story" to "小故事")
    data class Text(val kind: String, val title: String, val content: String)
    fun valid(kind: String, title: String, content: String): Boolean = kind in kinds &&
        title.length <= 120 && title == title.trim() && clean(title, false) &&
        content.isNotBlank() && content.length <= 4000 && content == content.trim() && clean(content, true)
    private fun clean(s: String, multiline: Boolean): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isISOControl() && !(multiline && c in "\n\t")) return false
            if (c.isHighSurrogate()) { if (i + 1 == s.length || !s[i + 1].isLowSurrogate()) return false; i++ }
            else if (c.isLowSurrogate()) return false
            i++
        }
        return true
    }
    fun result(raw: String): Text? {
        require(raw.length <= 8000)
        val data = Json.parseToJsonElement(raw).jsonObject
        require(data.keys == setOf("action", "kind", "title", "content"))
        fun field(key: String) = requireNotNull((data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        val action = field("action"); val kind = field("kind"); val title = field("title").trim(); val body = field("content").trim()
        if (action == "pause") { require(kind.isEmpty() && title.isEmpty() && body.isEmpty()); return null }
        require(action == "write" && valid(kind, title, body))
        return Text(kind, title, body)
    }
    fun validate(owners: Set<Long>, writings: List<WorldWritingEntity>, notes: List<WorldNoteEntity> = emptyList(),
        looks: List<WorldPictureLookEntity> = emptyList(), album: List<WorldAlbumLookEntity> = emptyList()) {
        require(writings.map { it.id }.distinct().size == writings.size) { "自由随笔编号重复" }
        writings.forEach { require(it.id > 0 && it.companionId in owners && it.createdAt > 0 &&
            it.runId.isNotBlank() && it.runId.length <= 100 && clean(it.runId, false) && valid(it.kind, it.title, it.content)) { "自由随笔内容或归属无效" } }
        val runs = writings.map { it.runId } + notes.map { it.runId } + looks.map { it.runId } + album.map { it.runId }
        require(runs.distinct().size == runs.size) { "世界记录包含重复结果" }
        require((writings.map { it.companionId } + notes.map { it.companionId } + looks.map { it.companionId } + album.map { it.companionId })
            .groupingBy { it }.eachCount().values.all { it <= WorldReading.MAX_NOTES }) { "世界记录过多" }
    }
}
