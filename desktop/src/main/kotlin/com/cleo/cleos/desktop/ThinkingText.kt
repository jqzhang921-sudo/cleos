package com.cleo.cleos.desktop

/** Adapted from Cleos Android's ThinkSplitter. Only leading thinking blocks count;
 * chunks may split either tag. A disconnected reasoning block never becomes body text. */
internal class ThinkingText {
    private enum class Mode { Start, Inside, After, Plain }
    private var mode = Mode.Start
    private val held = StringBuilder()
    private var closer = ""
    fun split(text: String): List<TextEvent> = when (mode) {
        Mode.Start -> start(text)
        Mode.Inside -> inside(text)
        Mode.After -> after(text)
        Mode.Plain -> listOf(TextEvent.Text(text))
    }
    fun finish(): List<TextEvent> {
        val rest = held.toString(); held.clear()
        val out = when {
            rest.isEmpty() -> emptyList()
            mode == Mode.Inside -> listOf(TextEvent.Thought(rest))
            mode == Mode.Start -> listOf(TextEvent.Text(rest))
            else -> emptyList()
        }
        mode = Mode.Plain; return out
    }
    private fun start(text: String): List<TextEvent> {
        held.append(text)
        val all = held.toString(); val lead = all.trimStart()
        val tag = listOf("<think>", "<thinking>").firstOrNull { lead.startsWith(it) }
        if (tag != null) { held.clear(); mode = Mode.Inside; closer = "</" + tag.substring(1); return inside(lead.substring(tag.length)) }
        if (listOf("<think>", "<thinking>").any { it.startsWith(lead) }) return emptyList()
        held.clear(); mode = Mode.Plain; return listOf(TextEvent.Text(all))
    }
    private fun inside(text: String): List<TextEvent> {
        held.append(text); val all = held.toString(); held.clear()
        val end = all.indexOf(closer)
        if (end >= 0) { mode = Mode.After; return listOfNotNull(all.take(end).takeIf { it.isNotEmpty() }?.let { TextEvent.Thought(it) }) + after(all.substring(end + closer.length)) }
        val keep = (minOf(closer.length - 1, all.length) downTo 1).firstOrNull { all.endsWith(closer.take(it)) } ?: 0
        held.append(all, all.length - keep, all.length)
        return listOfNotNull(all.take(all.length - keep).takeIf { it.isNotEmpty() }?.let { TextEvent.Thought(it) })
    }
    private fun after(text: String): List<TextEvent> {
        val t = text.trimStart(); if (t.isEmpty()) return emptyList()
        mode = Mode.Plain; return listOf(TextEvent.Text(t))
    }
}
