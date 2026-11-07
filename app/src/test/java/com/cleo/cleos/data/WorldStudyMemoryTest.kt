package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorldStudyMemoryTest {
    private val a = WorldArticleEntity(1, 10, "Wind", "PRIVATE_FILENAME", "Read. UNREAD_CANARY", "fp", 20, 5, 30)
    private val wish = WorldWishEntity(1, 10, "Wind", "Author", "I wanted to understand wind", "wind", "w", 10)
    private val note = WorldNoteEntity(1, 10, 1, "n", "Why does wind return?", "Does it?", "", null, 0, 5, 30)
    private fun snapshot(articles: List<WorldArticleEntity> = listOf(a), wishes: List<WorldWishEntity> = listOf(wish), query: String = "") =
        WorldStudyMemory.snapshot(10, articles, wishes, query)
    private fun JsonObject.books() = getValue("books").jsonArray
    private fun reading(article: WorldArticleEntity = a, shelf: List<WorldArticleEntity> = listOf(a), notes: List<WorldNoteEntity> = listOf(note), memories: List<MemoryEntity> = emptyList()) =
        WorldStudyMemory.reading(10, article, shelf, listOf(wish), notes, memories)

    @Test fun unreadPartialAndFinishedImportedTextHaveDistinctStatesEvenWithoutNotes() {
        for ((offset, state) in listOf(0 to "on_shelf_unread", 5 to "reading", a.content.length to "finished_imported_text")) {
            val row = snapshot(listOf(a.copy(readOffset = offset))).books().single().jsonObject
            assertEquals(state, row["state"]!!.jsonPrimitive.content)
            assertEquals(offset.toLong(), row["read_characters"]!!.jsonPrimitive.long)
        }
    }
    @Test fun rawSourceAndUnreadTextNeverEnterSnapshot() {
        val raw = snapshot().toString()
        assertFalse(raw.contains("PRIVATE_FILENAME")); assertFalse(raw.contains("UNREAD_CANARY"))
        assertTrue(raw.contains(wish.reason))
    }
    @Test fun wrongOwnerAndPublicSourcesAreExcludedBeforeMatchingAndLimiting() {
        val s = snapshot(listOf(a.copy(companionId = 20), a.copy(space = "outside")), listOf(wish.copy(companionId = 20)))
        assertTrue(s.books().isEmpty()); assertTrue(s["wishes"]!!.jsonArray.isEmpty())
    }
    @Test fun titleMatchIsCandidateUntilUserExplicitlyLinks() {
        val row = snapshot()["wishes"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, row["linked_book_id"])
        assertEquals(1L, row["matching_title_book_ids"]!!.jsonArray.single().jsonPrimitive.long)
        val linked = snapshot(wishes = listOf(wish.copy(status = "added", articleId = 1)))["wishes"]!!.jsonArray.single().jsonObject
        assertEquals(1L, linked["linked_book_id"]!!.jsonPrimitive.long)
    }
    @Test fun deletedLinkedBookDoesNotResurrectSourceButKeepsWantedHistory() {
        val s = snapshot(emptyList(), listOf(wish.copy(status = "added", articleId = null)))
        assertTrue(s.books().isEmpty())
        assertTrue(s["wishes"]!!.jsonArray.single().jsonObject["linked_source_missing"]!!.jsonPrimitive.boolean)
    }
    @Test fun bookQueryFindsLinkedAlternateTitleWithoutGuessingTranslations() {
        val linked = wish.copy(title = "风之书", articleId = 1, status = "added")
        assertEquals(1, snapshot(wishes = listOf(linked), query = "风之书").books().size)
        assertTrue(snapshot(query = "风之书").books().isEmpty())
    }
    @Test fun noMatchingQueryDoesNotReturnArbitraryRecentBooks() {
        val s = snapshot(query = "absent"); assertTrue(s.books().isEmpty()); assertTrue(s["wishes"]!!.jsonArray.isEmpty())
    }
    @Test fun boundedEscapedUnicodePayloadRemainsValidJson() {
        val rows = (1L..50).map { a.copy(id = it, title = "🙂\\\"".repeat(20), createdAt = it) }
        val s = snapshot(rows, (1L..20).map { wish.copy(id = it, reason = "\"\\".repeat(250)) })
        assertTrue(s.toString().length < 3000); assertEquals(s, Json.parseToJsonElement(s.toString()))
        val clipped = WorldStudyMemory.clip("a🙂z", 2); assertFalse(clipped.any { it.isSurrogate() })
    }
    @Test fun completeEpubVolumesShareIdentityAndAggregateProgress() {
        val first = a.copy(title = "Wind - Part 1 of 2", source = "EPUB 提取文字 · Wind-Part-1.epub · Author")
        val second = a.copy(id = 2, title = "Wind - Part 2 of 2", source = "EPUB 提取文字 · Wind-Part-2.epub · Author", readOffset = 0)
        val b = snapshot(listOf(first, second)).books().single().jsonObject
        assertEquals("Wind", b["title"]!!.jsonPrimitive.content); assertEquals(2, b["parts"]!!.jsonPrimitive.int)
        assertEquals(5L, b["read_characters"]!!.jsonPrimitive.long)
    }
    @Test fun continuationKeepsOnlyCurrentBookConfirmedPriorNotes() {
        val raw = reading(shelf = listOf(a, a.copy(id = 2)), notes = listOf(note, note.copy(id = 2, articleId = 2, content = "OTHER_BOOK"),
            note.copy(id = 3, companionId = 20, content = "OTHER_OWNER"), note.copy(id = 4, toOffset = 18, content = "FUTURE_NOTE"))).toString()
        assertTrue(raw.contains(note.content)); assertFalse(raw.contains("OTHER_BOOK")); assertFalse(raw.contains("OTHER_OWNER")); assertFalse(raw.contains("FUTURE_NOTE"))
        assertFalse(raw.contains("UNREAD_CANARY"))
    }
    @Test fun secondVolumeCanCarryConfirmedNoteFromFirstVolume() {
        val first = a.copy(title = "Wind - Part 1 of 2", source = "EPUB 提取文字 · Wind-Part-1.epub · Author")
        val second = a.copy(id = 2, title = "Wind - Part 2 of 2", source = "EPUB 提取文字 · Wind-Part-2.epub · Author", readOffset = 0)
        assertTrue(reading(second, listOf(first, second)).toString().contains(note.content))
    }
    @Test fun readingPreferencesOnlyUseOwnRelevantSummariesNotDetails() {
        val m = MemoryEntity(1, 10, "interest", "读书口味", "喜欢文学与小说", "[\"DETAIL_CANARY\"]", false, "ai", 1, 2)
        val raw = reading(memories = listOf(m, m.copy(id = 2, companionId = 20, summary = "OTHER_OWNER"),
            m.copy(id = 3, name = "电话号码", summary = "UNRELATED_PHONE"))).toString()
        assertTrue(raw.contains(m.summary)); assertFalse(raw.contains("DETAIL_CANARY")); assertFalse(raw.contains("OTHER_OWNER")); assertFalse(raw.contains("UNRELATED_PHONE"))
        assertTrue(reading()["reading_preferences"]!!.jsonArray.isEmpty())
    }
    @Test fun deletingBookOrPriorNoteRemovesItsContinuityOnNextProjection() {
        assertTrue(reading(shelf = emptyList())["previous_notes"]!!.jsonArray.isEmpty())
        assertTrue(reading(notes = emptyList())["previous_notes"]!!.jsonArray.isEmpty())
    }
    @Test fun silentReadingStillCarriesOnlyTheConfirmedPreviousTail() {
        val r = reading(notes = emptyList())["previous_read_excerpt"]!!.jsonObject
        assertEquals("Read.", r["text"]!!.jsonPrimitive.content)
        assertEquals(5, r["to"]!!.jsonPrimitive.int)
        assertFalse(r.toString().contains("UNREAD_CANARY"))
        assertEquals(JsonNull, reading(a.copy(readOffset = 0), listOf(a.copy(readOffset = 0)))["previous_read_excerpt"])
    }
    @Test fun newVolumeCanRecallPreviousVolumesConfirmedTailWithoutSplittingASurrogate() {
        val body = "🙂".repeat(400)
        val first = a.copy(title = "Wind - Part 1 of 2", source = "EPUB 提取文字 · Wind-Part-1.epub · Author", content = body, readOffset = body.length)
        val second = a.copy(id = 2, title = "Wind - Part 2 of 2", source = "EPUB 提取文字 · Wind-Part-2.epub · Author", readOffset = 0)
        val r = reading(second, listOf(first, second), emptyList())["previous_read_excerpt"]!!.jsonObject
        val start = r["from"]!!.jsonPrimitive.int; val end = r["to"]!!.jsonPrimitive.int
        assertTrue(WorldReading.boundary(body,start)); assertEquals(body.substring(start,end),r["text"]!!.jsonPrimitive.content)
        assertEquals(1L,r["article_id"]!!.jsonPrimitive.long)
    }
}
