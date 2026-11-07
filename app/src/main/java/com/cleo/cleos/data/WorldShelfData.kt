package com.cleo.cleos.data

import com.cleo.cleos.data.db.*

/** User organization and bookmarks do not assert or advance TA reading. */
object WorldShelfData {
    const val MAX_BOOKMARKS = 500
    val kinds = linkedMapOf("book" to "书籍", "magazine" to "杂志", "article" to "文章")
    fun compareIssues(left: String, right: String): Int {
        val pattern = Regex("[0-9]+|[^0-9]+")
        val a = pattern.findAll(left).map { it.value }.toList(); val b = pattern.findAll(right).map { it.value }.toList()
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i]; val y = b[i]
            val result = if (x.first().isDigit() && y.first().isDigit()) {
                val xx = x.trimStart('0').ifEmpty { "0" }; val yy = y.trimStart('0').ifEmpty { "0" }
                xx.length.compareTo(yy.length).takeIf { it != 0 } ?: xx.compareTo(yy)
            } else x.compareTo(y, ignoreCase = true)
            if (result != 0) return result
        }
        return a.size.compareTo(b.size)
    }
    fun preview(text: String, offset: Int): String {
        var end = minOf(text.length, offset + 120)
        if (!WorldReading.boundary(text, end)) end--
        return text.substring(offset, end).trim().replace(Regex("^〔EPUB 阅读章节 [1-9][0-9]{0,3}〕\\s*"), "")
            .substringBefore("〔EPUB 阅读章节 ").trim().replace(Regex("\\s+"), " ")
    }
    fun label(value: String, max: Int): Boolean = value.length <= max && value == value.trim() && value.none { it.isISOControl() }
    fun valid(kind: String, series: String, issue: String): Boolean = kind in kinds && label(series, 80) && label(issue, 80) &&
        if (kind == "magazine") series.isNotEmpty() else series.isEmpty() && issue.isEmpty()

    fun validateBackup(tas: Set<Long>, articles: List<WorldArticleEntity>, marks: List<WorldBookmarkEntity>, metadata: List<WorldShelfEntity>) {
        val byId = articles.associateBy { it.id }
        require(marks.map { it.id }.distinct().size == marks.size && marks.map { it.articleId to it.offset }.distinct().size == marks.size) { "书签含重复位置或编号" }
        require(marks.groupingBy { it.companionId }.eachCount().values.all { it <= MAX_BOOKMARKS }) { "书签过多" }
        for (m in marks) {
            val a = byId[m.articleId]
            require(m.id > 0 && m.companionId in tas && a != null && a.space == "study" && a.companionId == m.companionId && m.fingerprint == a.fingerprint) { "书签归属或正文版本无效" }
            require(m.offset < a.content.length && WorldReading.boundary(a.content, m.offset) && label(m.label, 80) && m.createdAt > 0) { "书签位置或名称无效" }
        }
        require(metadata.map { it.articleId }.distinct().size == metadata.size) { "读物整理信息重复" }
        for (m in metadata) {
            val a = byId[m.articleId]
            require(a != null && a.space == "study" && a.companionId == m.companionId && m.companionId in tas && valid(m.kind, m.series, m.issue)) { "读物整理信息无效" }
        }
    }
}
