package com.cleo.cleos.ai

/** Hide standalone locator headers and orphan trailing reply wrappers, preserving literal prose/code. */
internal object ReplyMarkers {
    private val header = Regex("^[ \\t]*[（(]消息编号[ \\t]*[#＃][ \\t]*[0-9０-９]+[）)][ \\t]*$")
    private val opening = Regex("^[ \\t]*[（(](?:消(?:息(?:编(?:号[ \\t]*(?:[#＃][ \\t]*[0-9０-９]*)?)?)?)?)?$")
    private val orphanEnd = Regex("</text>\\s*$", RegexOption.IGNORE_CASE)
    private val pendingEnd = Regex("<(?:/(?:t(?:e(?:x(?:t(?:>)?)?)?)?)?)?$", RegexOption.IGNORE_CASE)
    private val textOpening = Regex("<text(?:\\s|>)", RegexOption.IGNORE_CASE)

    fun clean(raw: String, streaming: Boolean = false): String {
        val lines = raw.split('\n')
        val visible = lines.mapIndexedNotNull { index, line ->
            val candidate = line.removeSuffix("\r")
            when {
                header.matches(candidate) -> null
                streaming && index == lines.lastIndex && opening.matches(candidate) -> null
                else -> line
            }
        }.joinToString("\n")
        // Hold trailing separators until the next line is known: otherwise removing an
        // echoed header could retract a newline already sent to streaming speech.
        val candidate=if(streaming)visible.trimEnd() else visible
        // Only an orphan closing wrapper at the end of prose. Preserve paired XML,
        // inline examples and fenced code. Literal requests bypass this upstream.
        if(candidate.contains("```") || textOpening.containsMatchIn(candidate))return candidate
        val tail=orphanEnd.find(candidate) ?: if(streaming)pendingEnd.find(candidate) else null
        return tail?.let {candidate.substring(0,it.range.first).trimEnd()} ?: candidate
    }
}
