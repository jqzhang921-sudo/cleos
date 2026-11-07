package com.cleo.cleos.ui.world

/** UTF-16 offsets from the actual shaped text, not an estimate based on character count. */
internal data class NotebookPageSpan(val start: Int, val end: Int)
internal data class NotebookLine(val start: Int, val end: Int, val top: Float, val bottom: Float)

internal object NotebookPages {
    fun split(textLength: Int, lines: List<NotebookLine>, height: Float): List<NotebookPageSpan> {
        if (textLength == 0) return listOf(NotebookPageSpan(0, 0))
        require(height.isFinite() && height > 0)
        require(lines.isNotEmpty() && lines.first().start == 0)
        require(lines.all { it.start in 0..textLength && it.end in it.start..textLength && it.bottom >= it.top })
        require(lines.zipWithNext().all { (a, b) -> a.start <= b.start && a.bottom <= b.bottom })
        val pages = mutableListOf<NotebookPageSpan>()
        var line = 0; var start = 0
        while (line < lines.size && start < textLength) {
            val top = lines[line].top
            var next = line + 1 // A single tall line is kept intact; the UI offers vertical fallback.
            while (next < lines.size && lines[next].bottom - top <= height + .1f) next++
            val end = if (next == lines.size) textLength else lines[next].start
            if (end > start) { pages += NotebookPageSpan(start, end); start = end }
            line = next
        }
        if (start < textLength) pages += NotebookPageSpan(start, textLength)
        return pages.ifEmpty { listOf(NotebookPageSpan(0, textLength)) }
    }

    fun containing(pages: List<NotebookPageSpan>, offset: Int): Int =
        pages.indexOfLast { it.start <= offset.coerceAtLeast(0) }.coerceAtLeast(0)

    fun tag(text: String): String = "${text.length}:${text.hashCode().toUInt().toString(16)}"
}

internal data class NotebookAnchor(val key: String, val at: Long, val tag: String, val offset: Int) {
    fun encode() = "$key|$at|$tag|$offset"
    companion object {
        fun decode(value: String?): NotebookAnchor? = runCatching {
            if (value == null || value.length > 160) return null
            val p = value.split('|'); if (p.size != 4) return null
            if (!p[0].matches(Regex("(?:writing|note|look):[1-9][0-9]*"))) return null
            if (!p[2].matches(Regex("[0-9]+:[0-9a-f]+"))) return null
            NotebookAnchor(p[0], p[1].toLong().also { require(it > 0) }, p[2], p[3].toInt().also { require(it >= 0) })
        }.getOrNull()
    }
    fun offsetFor(key: String, at: Long, body: String): Int =
        if (this.key == key && this.at == at && tag == NotebookPages.tag(body)) offset.coerceAtMost(body.length) else 0
}

internal fun WorldNotebookEntries.Entry.notebookTitle(): String = writing?.title?.ifBlank { "一段随笔" }
    ?: source?.title ?: if (look != null) "看图记录" else "阅读笔记"

internal fun WorldNotebookEntries.Entry.notebookKind(): String = writing?.let {
    com.cleo.cleos.data.WorldWritingData.kinds[it.kind] ?: "随笔"
} ?: if (look != null) "看图记录" else "阅读笔记"

/** Display-only labels are added without changing the saved content, quote or question. */
internal fun WorldNotebookEntries.Entry.notebookBody(): String = buildList {
    if (content.isNotBlank()) add(content)
    if (quote.isNotBlank()) add("摘录\n“$quote”")
    if (question.isNotBlank()) add("还想知道\n$question")
}.joinToString("\n\n").ifBlank { "TA 看过这张图，没有留下笔记。" }
