package com.cleo.cleos.ui.world

import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test

class WorldOutsideCardDataTest {
    private val article = WorldArticleEntity(id = 7, companionId = 1, title = "An original title", source = "NASA",
        content = "First paragraph.\n\n\nSecond 🗺 paragraph.", fingerprint = "saved", createdAt = 17, space = "outside")
    private fun state(text: String = article.content, offset: Int) =
        WorldOutsideCardData.from(1, article.copy(content = text, readOffset = offset), emptyList())!!.reading

    @Test fun aFetchAndTimestampDoNotProveReadingButSavedOffsetsDo() {
        assertEquals(WorldOutsideCardData.Reading.Unread, state(offset = 0))
        assertEquals(WorldOutsideCardData.Reading.Partial, state(offset = 5))
        assertEquals(WorldOutsideCardData.Reading.Complete, state(offset = article.content.length))
        assertEquals(WorldOutsideCardData.Reading.Unread,
            WorldOutsideCardData.from(1, article.copy(readAt = 999), emptyList())!!.reading)
    }

    @Test fun blankOrInvalidProgressNeverBecomesCompletedReading() {
        assertEquals(WorldOutsideCardData.Reading.Empty, state("", 0))
        assertEquals(WorldOutsideCardData.Reading.Empty, state(" \n", 2))
        for (offset in listOf(-1, article.content.length + 1, article.content.indexOf("🗺") + 1))
            assertEquals(WorldOutsideCardData.Reading.Unconfirmed, state(offset = offset))
    }

    @Test fun onlyThisTasPublicArticleAndRealAssociatedNotesArePresented() {
        fun note(id: Long, owner: Long = 1, target: Long = 7, content: String = "Saved thought") =
            WorldNoteEntity(id, owner, target, "run$id", content, "", "", null, 0, 5, 20)
        val notes = listOf(note(1), note(2, owner = 2), note(3, target = 8), note(4, content = "  "), note(5).copy(content = "", question = "Why?"))
        assertEquals(2, WorldOutsideCardData.from(1, article, notes)!!.noteCount)
        assertNull(WorldOutsideCardData.from(2, article, notes))
        assertNull(WorldOutsideCardData.from(null, article, notes))
        assertNull(WorldOutsideCardData.from(1, article.copy(space = "study"), notes))
        assertEquals(0, WorldOutsideCardData.from(1, article, emptyList())!!.noteCount)
    }

    @Test fun compactPreviewPreservesActualParagraphTextAndOriginalOffsets() {
        val original = article.copy(readOffset = 18)
        val view = WorldOutsideCardData.from(1, original, emptyList())!!
        assertEquals("First paragraph.\n\nSecond 🗺 paragraph.", view.preview)
        assertEquals(article.content, original.content)
        assertEquals(18, original.readOffset)
        assertEquals(article.title, original.title)
    }

    @Test fun publicationDatesAreNotReplacedWithFetchTimeOrInventedFromUnknownText() {
        assertEquals("2026年10月23日 22:39 UTC", WorldOutsideCardData.published("Fri, 23 Oct 2026 22:39:39 +0000"))
        assertEquals("2026年10月24日 08:15 UTC+08:00", WorldOutsideCardData.published("2026-10-24T08:15:00+08:00"))
        assertEquals("2026年10月24日", WorldOutsideCardData.published("2026-10-24"))
        assertEquals("未提供", WorldOutsideCardData.published(" "))
        assertEquals("sometime in autumn", WorldOutsideCardData.published("sometime in autumn"))
    }
}
