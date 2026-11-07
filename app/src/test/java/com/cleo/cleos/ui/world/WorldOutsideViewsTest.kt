package com.cleo.cleos.ui.world

import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test

class WorldOutsideViewsTest {
    private val article = WorldArticleEntity(1, 7, "Sea", "Source", "First 🌊 paragraph", "fingerprint", 10,
        space = "outside", originUrl = "https://zh.wikipedia.org/wiki/Sea")
    private val picture = WorldPictureEntity(1, 7, "fingerprint", "world-00000000-0000-0000-0000-000000000001.png", 400, 300,
        "Sea", "Author", "Own work", "A sea", "CC0", "https://creativecommons.org/publicdomain/zero/1.0/",
        "https://commons.wikimedia.org/wiki/File:Sea.png", "https://upload.wikimedia.org/wikipedia/commons/a/ab/Sea.png", 12, "a".repeat(64))
    private val note = WorldNoteEntity(1, 7, 1, "n", "A saved thought", "A real question?", "First", 0, 0, 5, 13)
    private val look = WorldPictureLookEntity(1, 7, 1, picture.sha256, "look", "A pixel impression", "", 14)

    @Test fun noRecordsCannotCreateAStoryOrInventNotes() {
        assertTrue(WorldOutsideViews.notes(7, emptyList(), listOf(note), listOf(look), listOf(picture)).isEmpty())
        assertTrue(WorldOutsideViews.trail(7, emptyList(), listOf(note), listOf(look), listOf(picture)).isEmpty())
        assertTrue(WorldOutsideViews.trail(null, listOf(article), listOf(note), listOf(look), listOf(picture)).isEmpty())
    }
    @Test fun feedHasOnlyOwnedPublicRowsAndStableNewestFirstSourceFiltering() {
        val newer = article.copy(id = 2, source = "Second", createdAt = 20)
        val rows = listOf(article, newer, article, article.copy(id = 3, companionId = 8), article.copy(id = 4, space = "study"))
        assertEquals(listOf(2L, 1L), WorldOutsideViews.articles(7, rows).map { it.id })
        assertEquals(listOf(article), WorldOutsideViews.articles(7, rows, "Source"))
        assertTrue(WorldOutsideViews.articles(7, rows, "Deleted source").isEmpty())
    }
    @Test fun notesExcludeOtherTasBooksAndMissingSourcesWithoutCopyingPrivateWriting() {
        val rows = listOf(article, article.copy(id = 2, space = "study"), article.copy(id = 3, companionId = 8))
        val notes = listOf(note, note.copy(id = 2, companionId = 8), note.copy(id = 3, articleId = 2), note.copy(id = 4, articleId = 3), note.copy(id = 5, articleId = 99))
        assertEquals(listOf("note:1"), WorldOutsideViews.notes(7, rows, notes, emptyList(), emptyList()).map { it.key })
        assertEquals(listOf("saved:1", "note:1").toSet(), WorldOutsideViews.trail(7, rows, notes, emptyList(), emptyList()).map { it.key }.toSet())
    }
    @Test fun quoteMustMatchCurrentSourceAndCompleteUnicodeBoundaries() {
        fun quote(n: WorldNoteEntity) = WorldOutsideViews.notes(7, listOf(article), listOf(n), emptyList(), emptyList()).single().quote
        assertEquals("First", quote(note))
        assertEquals("", quote(note.copy(quote = "changed")))
        assertEquals("", quote(note.copy(quoteStart = -1)))
        assertEquals("", quote(note.copy(quoteStart = Int.MAX_VALUE)))
        assertEquals("🌊", quote(note.copy(quote = "🌊", quoteStart = 6)))
        assertEquals("", quote(note.copy(quote = "\uDF0A", quoteStart = 7)))
    }
    @Test fun downloadIsNotEvidenceOfSeeingPixelsButAnEmptyAcknowledgementIs() {
        val fetched = WorldOutsideViews.trail(7, listOf(article), emptyList(), emptyList(), listOf(picture))
        assertEquals(setOf("saved:1", "picture:1"), fetched.map { it.key }.toSet())
        val empty = look.copy(content = "", question = "")
        assertTrue(WorldOutsideViews.notes(7, listOf(article), emptyList(), listOf(empty), listOf(picture)).isEmpty())
        assertTrue(WorldOutsideViews.trail(7, listOf(article), emptyList(), listOf(empty), listOf(picture)).any { it.key == "look:1" })
    }
    @Test fun historicalImageNotesDoNotBecomeProofAboutCurrentPixels() {
        fun view(pictures: List<WorldPictureEntity>) = WorldOutsideViews.notes(7, listOf(article), emptyList(), listOf(look), pictures).single()
        assertFalse(view(listOf(picture)).historicalImage)
        assertTrue(view(listOf(picture.copy(sha256 = "b".repeat(64)))).historicalImage)
        assertTrue(view(listOf(picture.copy(license = "unknown"))).historicalImage)
        assertTrue(view(emptyList()).historicalImage)
        assertEquals(look.content, view(emptyList()).content)
        assertTrue(WorldOutsideViews.notes(7, listOf(article), emptyList(), listOf(look.copy(companionId = 8)), listOf(picture)).isEmpty())
        assertFalse(WorldOutsideViews.trail(7, listOf(article), emptyList(), listOf(look.copy(pictureSha256 = "")), listOf(picture)).any { it.key == "look:1" })
    }
    @Test fun readingStepNeedsBothValidProgressAndAnActualSavedTimestamp() {
        fun read(a: WorldArticleEntity) = WorldOutsideViews.trail(7, listOf(a), emptyList(), emptyList(), emptyList()).filter { it.key == "read:1" }
        assertTrue(read(article.copy(readAt = 20)).isEmpty())
        assertTrue(read(article.copy(readOffset = article.content.length)).isEmpty())
        assertTrue(read(article.copy(readOffset = 7, readAt = 20)).isEmpty())
        assertTrue(read(article.copy(readOffset = 100, readAt = 20)).isEmpty())
        assertEquals("读过部分摘要", read(article.copy(readOffset = 5, readAt = 20)).single().label)
        val complete = article.copy(readOffset = article.content.length, readAt = 21)
        assertEquals("读完这份摘要", read(complete).single().label)
        assertEquals(21L, read(complete).single().at)
        assertEquals(article.content.length, complete.readOffset)
    }
    @Test fun removingAnArticleRemovesItsDerivedViewsWithoutInventingPermanentHistory() {
        assertEquals(2, WorldOutsideViews.notes(7, listOf(article), listOf(note), listOf(look), listOf(picture)).size)
        assertTrue(WorldOutsideViews.notes(7, emptyList(), listOf(note), listOf(look), listOf(picture)).isEmpty())
        assertTrue(WorldOutsideViews.trail(7, emptyList(), listOf(note), listOf(look), listOf(picture)).isEmpty())
    }
    @Test fun recentTrailIsBoundedAndDeterministicWithoutChangingOriginalRecords() {
        val rows = (1L..150L).map { article.copy(id = it, createdAt = it) }
        val result = WorldOutsideViews.trail(7, rows.reversed(), emptyList(), emptyList(), emptyList())
        assertEquals(100, result.size); assertEquals(150L, result.first().article.id); assertEquals(51L, result.last().article.id)
        assertEquals(result, WorldOutsideViews.trail(7, rows, emptyList(), emptyList(), emptyList()))
        assertTrue(rows.all { it.readOffset == 0 && it.readAt == null })
    }
}
