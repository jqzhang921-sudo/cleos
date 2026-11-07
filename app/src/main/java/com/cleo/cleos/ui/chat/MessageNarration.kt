package com.cleo.cleos.ui.chat

/** Presentation only: text and line breaks remain lossless for copy/context/history. */
internal object MessageNarration {
    data class Line(val text: String, val aside: Boolean)
    fun lines(raw: String): List<Line> {
        var fence: String? = null
        return raw.split('\n').map { line ->
            val trimmed = line.trim()
            val marker = when { trimmed.startsWith("```") -> "```"; trimmed.startsWith("~~~") -> "~~~"; else -> null }
            val code = fence != null || marker != null
            if (marker != null) fence = if (fence == marker) null else fence ?: marker
            Line(line, !code && standalone(trimmed))
        }
    }
    private fun standalone(text: String): Boolean {
        if (text.length < 3 || text.first() !in "(（" || text.contains("://")) return false
        val content = text.substring(1, text.length - 1).trim()
        if (content.isEmpty() || content.matches(Regex("[0-9０-９]+|[A-Za-z]"))) return false
        if (content.matches(Regex("消息编号\\s*[#＃]?\\s*[0-9０-９]+"))) return false
        if (content.any { it in "=+*/^<>" } && content.matches(Regex("[A-Za-z0-9_ \t=+*/^<>.,:%°−-]+"))) return false
        val stack = ArrayDeque<Char>()
        text.forEachIndexed { index, c ->
            when (c) {
                '(' -> stack.addLast(')')
                '（' -> stack.addLast('）')
                ')', '）' -> if (stack.removeLastOrNull() != c) return false
            }
            if (stack.isEmpty() && index < text.lastIndex) return false
        }
        return stack.isEmpty()
    }
}
