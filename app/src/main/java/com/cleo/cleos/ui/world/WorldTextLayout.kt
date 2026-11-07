package com.cleo.cleos.ui.world

import com.cleo.cleos.data.WorldReading

/** View-only ranges into the unchanged source. Display text is never a reading/quote offset. */
internal object WorldTextLayout {
    data class Section(val from: Int, val bodyFrom: Int, val to: Int, val number: Int, val preview: String, val toc: Boolean) {
        val label: String get() = "阅读分段 $number"
    }
    data class Part(val from: Int, val to: Int, val text: String, val heading: Boolean = false)
    private val marker = Regex("^〔EPUB 阅读章节 ([1-9][0-9]{0,3})〕[ \\t]*\\r?$", RegexOption.MULTILINE)

    fun sections(content: String): List<Section> {
        val marks = marker.findAll(content).take(1501).toList()
        if (marks.isEmpty() || marks.size > 1500 || marks.first().range.first != 0) return emptyList()
        val numbers = marks.map { it.groupValues[1].toInt() }
        if (numbers.any { it > 1500 } || numbers.zipWithNext().any { (a, b) -> a >= b }) return emptyList()
        return marks.mapIndexed { index, match ->
            val body = match.range.last + 1
            val end = marks.getOrNull(index + 1)?.range?.first ?: content.length
            val opening = content.substring(body, end).lineSequence().map(String::trim).firstOrNull(String::isNotBlank).orEmpty()
            val previewEnd = minOf(80, opening.length).let { if (WorldReading.boundary(opening, it)) it else it - 1 }
            val preview = opening.take(previewEnd)
            Section(match.range.first, body, end, numbers[index], preview, preview.lowercase() in setOf("目录", "目次", "contents", "table of contents"))
        }
    }

    fun parts(content: String, sections: List<Section>, raw: Boolean): List<Part> = buildList {
        fun body(from: Int, to: Int, format: Boolean, toc: Boolean = false) {
            var at = from
            while (at < to) {
                var end = WorldReading.end(content, at).coerceAtMost(to)
                if (!format) {
                    var lines = 0
                    for (i in at until end) if (content[i] == '\n' && ++lines == 80) { end = i + 1; break }
                }
                val text = content.substring(at, end).let { if (format) compact(it, toc) else it }
                if (text.isNotBlank() || !format) add(Part(at, end, text))
                at = end
            }
        }
        if (raw || sections.isEmpty()) body(0, content.length, false)
        else sections.forEach { section ->
            add(Part(section.from, section.bodyFrom, section.label, heading = true))
            body(section.bodyFrom, section.to, true, section.toc)
        }
    }

    /** Keep meaningful single line breaks/indentation; collapse blank runs, not the stored source. */
    fun compact(text: String, toc: Boolean = false): String = buildString {
        var blank = false
        text.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { raw ->
            val line = raw.trimEnd(' ', '\t')
            if (line.isBlank()) { if (isNotEmpty()) blank = true }
            else {
                if (isNotEmpty()) append(if (blank && !toc) "\n\n" else "\n")
                append(line); blank = false
            }
        }
    }

    fun indexAt(parts: List<Part>, offset: Int): Int = parts.indexOfLast { it.from <= offset }.coerceAtLeast(0)
}
