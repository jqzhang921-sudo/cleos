package com.cleo.cleos.ai

/** Recognize only the app's standalone message-locator header, not numbers in actual prose. */
internal object ReplyMarkers {
    private val header = Regex("^[ \\t]*[（(]消息编号[ \\t]*[#＃][ \\t]*[0-9０-９]+[）)][ \\t]*$")
    private val opening = Regex("^[ \\t]*[（(](?:消(?:息(?:编(?:号[ \\t]*(?:[#＃][ \\t]*[0-9０-９]*)?)?)?)?)?$")

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
        return if (streaming) visible.trimEnd() else visible
    }
}
