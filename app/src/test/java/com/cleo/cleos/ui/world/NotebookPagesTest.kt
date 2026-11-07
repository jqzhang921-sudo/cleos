package com.cleo.cleos.ui.world

import org.junit.Assert.*
import org.junit.Test

class NotebookPagesTest {
    private fun lines(vararg ends: Int): List<NotebookLine> = ends.mapIndexed { i, end ->
        NotebookLine(if (i == 0) 0 else ends[i - 1], end, i * 26f, (i + 1) * 26f)
    }
    @Test fun everyCharacterAndNewlineSurvivesBoundaries() {
        val body = "山间\nA🙂B\n\n尾声"
        val breaks = listOf(3, 9, 10, body.length)
        val pages = NotebookPages.split(body.length, lines(*breaks.toIntArray()), 52f)
        assertEquals(body, pages.joinToString("") { body.substring(it.start, it.end) })
        assertEquals(listOf(NotebookPageSpan(0, 9), NotebookPageSpan(9, body.length)), pages)
        pages.forEach { if (it.end < body.length) assertFalse(body[it.end - 1].isHighSurrogate()) }
    }
    @Test fun resizeReanchorsByCharacterInsteadOfOldPageNumber() {
        val lines = lines(10, 20, 30, 40, 50, 60)
        val wide = NotebookPages.split(60, lines, 78f)
        val narrow = NotebookPages.split(60, lines, 27f)
        assertEquals(1, NotebookPages.containing(wide, 30))
        assertEquals(3, NotebookPages.containing(narrow, 30))
        assertEquals(5, NotebookPages.containing(narrow, 60))
        assertEquals(0, NotebookPages.containing(narrow, -20))
    }
    @Test fun tinyViewportDoesNotDropTallLineOrLoop() {
        assertEquals(listOf(NotebookPageSpan(0, 3), NotebookPageSpan(3, 5)), NotebookPages.split(5, lines(3, 5), 1f))
        assertEquals(listOf(NotebookPageSpan(0, 0)), NotebookPages.split(0, emptyList(), 1f))
    }
    @Test fun emptyAndTrailingLinesDoNotLoseText() {
        val lines = listOf(NotebookLine(0, 2, 0f, 26f), NotebookLine(2, 2, 26f, 52f))
        assertEquals(listOf(NotebookPageSpan(0, 2)), NotebookPages.split(2, lines, 26f))
    }
    @Test fun staleOrMalformedAnchorCannotFollowAnotherEntryOrChangedText() {
        val body = "A long story 🙂"
        val anchor = NotebookAnchor("writing:12", 300, NotebookPages.tag(body), 8)
        assertEquals(anchor, NotebookAnchor.decode(anchor.encode()))
        assertEquals(8, anchor.offsetFor("writing:12", 300, body))
        assertEquals(0, anchor.offsetFor("writing:13", 300, body))
        assertEquals(0, anchor.offsetFor("writing:12", 301, body))
        assertEquals(0, anchor.offsetFor("writing:12", 300, body + " changed"))
        assertEquals(body.length, anchor.copy(offset = 10000).offsetFor("writing:12", 300, body))
        listOf(null, "", "writing:12|300|x|-1", "writing:0|300|1:ab|2", "writing:12|0|1:ab|2",
            "writing:12|300|1:ab|-1", "SECRET|300|1:ab|1", "x".repeat(200)).forEach { assertNull(NotebookAnchor.decode(it)) }
    }
}
