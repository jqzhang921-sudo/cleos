package com.cleo.cleos.ui.world

import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test

class WorldJourneyDataTest {
    @Test fun savedTopicsKeepCuratedOrderBeyondTheRecentLimitWithoutChangingProgress() {
        val rows=(1L..30L).map { article.copy(id=it,createdAt=it) }
        val ordered=WorldJourneyData.chapters(7,rows,emptyList(),emptyList(),emptyList(),ordered=true)
        assertEquals((1L..30L).toList(),ordered.map {it.article.id});assertTrue(ordered.all {it.article.readOffset==0})
        assertEquals(24,WorldJourneyData.chapters(7,rows,emptyList(),emptyList(),emptyList()).size)
    }
    private val article = WorldArticleEntity(1, 7, "Sea", "Source", "First\n\nSecond\n\nThird", "fingerprint", 10,
        space = "outside", originUrl = "https://zh.wikipedia.org/wiki/Sea")
    private val picture = WorldPictureEntity(1, 7, "fingerprint", "world-00000000-0000-0000-0000-000000000001.png", 400, 300,
        "Sea", "Author", "Own work", "A sea", "CC0", "https://creativecommons.org/publicdomain/zero/1.0/",
        "https://commons.wikimedia.org/wiki/File:Sea.png", "https://upload.wikimedia.org/wikipedia/commons/a/ab/Sea.png", 1, "a".repeat(64))
    @Test fun onlyCurrentTasPublicArticlesBecomeChapters() {
        val input = listOf(article, article.copy(id = 2, space = "study"), article.copy(id = 3, companionId = 8), article.copy(id = 0))
        val result = WorldJourneyData.chapters(7, input, emptyList(), emptyList(), emptyList())
        assertEquals(listOf(1L), result.map { it.article.id }); assertNull(result.single().image)
        assertEquals(0, result.single().article.readOffset)
    }
    @Test fun deterministicNewestFirstBoundedChaptersKeepOriginalEntitiesUnchanged() {
        val input = (1L..30L).map { article.copy(id = it, createdAt = it / 2) }
        val result = WorldJourneyData.chapters(7, input.reversed() + input, emptyList(), emptyList(), emptyList())
        assertEquals(24, result.size); assertEquals(30L, result.first().article.id); assertEquals(7L, result.last().article.id)
        assertEquals(input.last(), result.first().article)
    }
    @Test fun changedOwnershipFingerprintPathOrLicenseCannotSupplyAnImage() {
        assertEquals(picture, WorldJourneyData.chapters(7, listOf(article), listOf(picture), emptyList(), emptyList()).single().image)
        for (bad in listOf(picture.copy(companionId = 8), picture.copy(fingerprint = "old"), picture.copy(file = "../secret.png"), picture.copy(license = "unknown")))
            assertNull(WorldJourneyData.chapters(7, listOf(article), listOf(bad), emptyList(), emptyList()).single().image)
    }
    @Test fun notesAndPixelImpressionsAreScopedToOwnerArticleAndCurrentPixels() {
        val note = WorldNoteEntity(1,7,1,"n","own note","", "",null,0,4,1)
        val look = WorldPictureLookEntity(1,7,1,picture.sha256,"look","own look","question",2)
        val result = WorldJourneyData.chapters(7, listOf(article), listOf(picture),
            listOf(note, note.copy(id = 2, companionId = 8, content = "FOREIGN"), note.copy(id = 3, articleId = 2, content = "OTHER")),
            listOf(look, look.copy(id = 2, companionId = 8, content = "FOREIGN_LOOK"), look.copy(id = 3, pictureSha256 = "b".repeat(64), content = "STALE"))).single()
        assertEquals(listOf("own look", "own note"), result.thoughts.map { it.content })
        assertEquals(1, WorldJourneyData.chapters(7, listOf(article), emptyList(), listOf(note), listOf(look)).single().thoughts.size)
    }
    @Test fun emptyResultsDoNotInventFeelingsOrVisitsAndOnlyNewestTwoExcerptsAreShown() {
        val notes = (1L..5L).map { WorldNoteEntity(it,7,1,"n$it","note$it","","",null,0,4,it) }
        assertTrue(WorldJourneyData.chapters(7,listOf(article),emptyList(),emptyList(),emptyList()).single().thoughts.isEmpty())
        assertEquals(listOf("note5", "note4"), WorldJourneyData.chapters(7,listOf(article),emptyList(),notes,emptyList()).single().thoughts.map { it.content })
    }
    @Test fun boundedSourceExcerptsPreserveParagraphsAndNeverSplitSurrogatePairs() {
        assertEquals(listOf("First", "Second"), WorldJourneyData.excerpt(article.content))
        assertTrue(WorldJourneyData.excerpt("   ").isEmpty())
        val text = "x".repeat(799) + "\uD83C\uDF0A" + "end"
        assertEquals("x".repeat(799), WorldJourneyData.excerpt(text).single())
    }
}
