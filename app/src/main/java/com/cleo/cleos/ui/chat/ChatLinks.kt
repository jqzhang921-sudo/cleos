package com.cleo.cleos.ui.chat

import java.net.URI

/** Display-only parsing: the stored message and its copyable text stay unchanged. */
internal object ChatLinks {
    data class Part(val text: String, val url: String? = null)

    private val start = Regex("""\[([^\]\n]+)]\((https?://)|https?://""", RegexOption.IGNORE_CASE)
    private const val boundaries = "<>\"'`\\，。；：！？、（）【】《》「」『』"

    fun parts(text: String): List<Part> = buildList {
        var cursor = 0
        while (cursor < text.length) {
            val match = start.find(text, cursor) ?: break
            val label = match.groups[1]?.value
            val urlStart = if (label != null) match.groups[2]!!.range.first else match.range.first
            var end = match.range.last + 1
            var parentheses = 0
            while (end < text.length) {
                val c = text[end]
                if (c.isWhitespace() || c in boundaries) break
                if (c == '(') parentheses++
                if (c == ')') {
                    if (parentheses == 0) break
                    parentheses--
                }
                end++
            }
            val markdown = label != null && text.getOrNull(end) == ')'
            if (!markdown) {
                while (end > urlStart && text[end - 1] in ".,;:!?") end--
            }
            val url = text.substring(urlStart, end)
            val valid = runCatching {
                val uri = URI(url)
                uri.scheme.lowercase() in setOf("http", "https") && !uri.rawAuthority.isNullOrBlank()
            }.getOrDefault(false)
            if (valid) {
                val prefixEnd = if (markdown) match.range.first else urlStart
                if (prefixEnd > cursor) add(Part(text.substring(cursor, prefixEnd)))
                add(Part(if (markdown) label else url, url))
                cursor = end + if (markdown) 1 else 0
            } else {
                // Keep unsupported / malformed text intact and continue after this candidate.
                val next = maxOf(end, match.range.last + 1)
                add(Part(text.substring(cursor, next)))
                cursor = next
            }
        }
        if (cursor < text.length) add(Part(text.substring(cursor)))
    }
}
