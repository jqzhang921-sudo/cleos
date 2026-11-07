package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import java.security.MessageDigest

/** Text offsets are UTF-16 positions in the immutable, normalized source, never model-written. */
object WorldReading {
    // Keep one UTF-8 SQLite row below CursorWindow's usual 2 MiB limit, including CJK.
    const val MAX_TEXT = 500_000
    const val MAX_ARTICLES = 50
    const val MAX_NOTES = 500
    const val SEGMENT = 3_000
    const val DAILY = 5
    /** Only inputs used by WorldReader define this result's identity; a letter heartbeat is not a prompt edit. */
    fun sameContext(expected: CompanionEntity, current: CompanionEntity?): Boolean = current != null &&
        current.id == expected.id && current.createdAt == expected.createdAt && current.name == expected.name &&
        current.persona == expected.persona && current.apiBaseUrl == expected.apiBaseUrl && current.apiModel == expected.apiModel
    fun normalize(raw: String) = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
    fun fingerprint(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun boundary(text: String, offset: Int) = offset in 0..text.length &&
        !(offset > 0 && offset < text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())
    fun end(text: String, start: Int): Int {
        require(boundary(text, start))
        var end = (start + SEGMENT).coerceAtMost(text.length)
        if (!boundary(text, end)) end--
        if (end < text.length) {
            val paragraph = text.lastIndexOf('\n', end - 1)
            if (paragraph >= start + SEGMENT / 2) end = paragraph + 1
        }
        return end
    }
    fun validateBackup(tas: Set<Long>, articles: List<WorldArticleEntity>, notes: List<WorldNoteEntity>) {
        require(articles.map { it.id }.toSet().size == articles.size && notes.map { it.id }.toSet().size == notes.size) { "世界备份含重复编号" }
        require(articles.map { it.companionId to it.fingerprint }.toSet().size == articles.size) { "世界备份含重复文章" }
        val outside = articles.filter { it.space == "outside" }
        require(outside.map { it.companionId to it.originUrl }.toSet().size == outside.size) { "窗外含重复来源链接" }
        require(notes.map { it.runId }.toSet().size == notes.size) { "世界备份含重复阅读结果" }
        require(articles.filter { it.space == "study" }.groupingBy { it.companionId }.eachCount().values.all { it <= MAX_ARTICLES }) { "书架内容过多" }
        require(articles.filter { it.space == "outside" }.groupingBy { it.companionId }.eachCount().values.all { it <= WorldOutside.MAX_ITEMS }) { "窗外见闻过多" }
        require(notes.groupingBy { it.companionId }.eachCount().values.all { it <= MAX_NOTES }) { "随手本内容过多" }
        val byId = articles.associateBy { it.id }
        for (a in articles) {
            require(a.space in setOf("study", "outside")) { "未知的世界空间" }
            if (a.space == "study") require(a.originUrl.isEmpty() && a.published.isEmpty() && a.searchQuery.isEmpty()) { "书房来源格式无效" }
            else require(WorldOutside.validUrl(a.originUrl) && WorldOutside.validAttribution(a.source, a.originUrl) && a.published.length <= 100 && a.published.none { it.isISOControl() } && a.createdAt > 0 && a.content.length <= WorldOutside.MAX_TEXT) { "见闻来源或摘要无效" }
            if (WorldTopicSearch.validUrl(a.originUrl)) require(a.source == WorldTopicSearch.SOURCE &&
                WorldTopicSearch.query(a.searchQuery) == a.searchQuery && a.published.isEmpty()) { "百科检索词或来源无效" }
            else require(a.searchQuery.isEmpty()) { "订阅摘要不能冒充百科检索结果" }
            require(a.id > 0 && a.companionId in tas && a.title.isNotBlank() && a.title.length <= 120 && a.source.length <= 500) { "文章归属或标题无效" }
            require(a.content.isNotBlank() && a.content.length <= MAX_TEXT && a.content == normalize(a.content) && a.fingerprint == fingerprint(a.content)) { "文章正文或版本不完整" }
            require(boundary(a.content, a.readOffset)) { "阅读位置无效" }
        }
        for (n in notes) {
            val a = byId[n.articleId]
            require(n.id > 0 && a != null && a.companionId == n.companionId && n.companionId in tas && n.runId.isNotBlank() && n.runId.length <= 100) { "笔记引用了其他 TA 或不存在的文章" }
            require(n.content.length <= 4_000 && n.question.length <= 600 && n.quote.length <= 500 && (n.content.isNotBlank() || n.question.isNotBlank())) { "笔记正文无效" }
            require(boundary(a.content, n.fromOffset) && boundary(a.content, n.toOffset) && n.fromOffset < n.toOffset && n.toOffset <= a.readOffset) { "笔记超出了实际阅读范围" }
            require(if (n.quote.isEmpty()) n.quoteStart == null else n.quoteStart != null && n.quoteStart >= n.fromOffset && n.quoteStart + n.quote.length <= n.toOffset && a.content.substring(n.quoteStart, n.quoteStart + n.quote.length) == n.quote) { "摘录与原文不一致" }
        }
    }
}
