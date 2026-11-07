package com.cleo.cleos.data

import com.cleo.cleos.data.db.WorldArticleEntity
import org.junit.Assert.*
import org.junit.Test

class WorldBookVolumesTest {
    private fun part(n: Int, total: Int = 3) = WorldArticleEntity(
        id = n.toLong(), companionId = 1, title = "A Book - Part $n of $total",
        source = "EPUB 提取文字${if (n == 1) "（2 个章节无文字）" else ""} · A-Book-Part-$n.epub · An Author",
        content = "text".repeat(n * 10), fingerprint = "fingerprint-$n", createdAt = n.toLong(),
    )
    private val three get() = listOf(part(3), part(2), part(1))

    @Test fun completeImportedSetAppearsAsOneBookInVolumeOrder() {
        val group = WorldBookVolumes.books(three).single()
        assertTrue(group.grouped); assertEquals("A Book", group.title)
        assertEquals("An Author", group.author)
        assertEquals(listOf(1L, 2L, 3L), group.parts.map { it.id })
        assertEquals(240L, group.characters); assertEquals(0L, group.readCharacters)
        assertEquals(1L, group.opening.id); assertEquals(3L, group.createdAt)
        assertEquals(three.sortedBy { it.id }, group.parts)
    }
    @Test fun candidatesAdvanceOnlyAfterEarlierVolumeIsComplete() {
        assertEquals(listOf(1L), WorldBookVolumes.candidates(three).map { it.id })
        val oneRead = three.map { if (it.id == 1L) it.copy(readOffset = it.content.length) else it }
        assertEquals(listOf(2L), WorldBookVolumes.candidates(oneRead).map { it.id })
        val allRead = three.map { it.copy(readOffset = it.content.length) }
        assertTrue(WorldBookVolumes.candidates(allRead).isEmpty())
        val book = WorldBookVolumes.books(allRead).single()
        assertEquals(book.characters, book.readCharacters); assertEquals(3L, book.opening.id)
    }
    @Test fun preexistingOutOfOrderProgressIsKeptAndEarlierUnreadVolumeWins() {
        val old = three.map { if (it.id == 3L) it.copy(readOffset = 20, readAt = 123L) else it }
        val grouped = WorldBookVolumes.books(old).single()
        assertEquals(20L, grouped.readCharacters); assertEquals(1L, grouped.nextUnread!!.id)
        assertEquals(old.first(), grouped.parts.last())
    }
    @Test fun singleBooksAndOtherSpacesRemainIndependent() {
        val single = part(4).copy(id = 9, title = "Ordinary Book", source = "user")
        val outside = part(2).copy(id = 10, space = "outside")
        val books = WorldBookVolumes.books(listOf(single) + three + outside)
        assertEquals(listOf(9L, 1L, 10L), books.map { it.id })
        assertEquals(listOf(9L, 1L, 10L), WorldBookVolumes.candidates(listOf(single) + three + outside).map { it.id })
        assertEquals(single, books.first().parts.single())
    }
    @Test fun incompleteDuplicateAndConflictingTotalsAreNotGuessed() {
        assertEquals(2, WorldBookVolumes.books(three.drop(1)).size)
        val duplicate = part(2).copy(id = 12)
        assertEquals(4, WorldBookVolumes.books(three + duplicate).size)
        val conflict = three.map { if (it.id == 3L) it.copy(title = "A Book - Part 3 of 4") else it }
        assertEquals(3, WorldBookVolumes.books(conflict).size)
    }
    @Test fun ownershipAuthorAndEditionMustAgree() {
        val crossTa = three.map { if (it.id == 3L) it.copy(companionId = 2) else it }
        val otherAuthor = three.map { if (it.id == 3L) it.copy(source = it.source.replace("An Author", "Another Author")) else it }
        val otherFile = three.map { if (it.id == 3L) it.copy(source = it.source.replace("A-Book-Part", "Different-Edition-Part")) else it }
        for (input in listOf(crossTa, otherAuthor, otherFile)) assertEquals(3, WorldBookVolumes.books(input).size)
    }
    @Test fun onlyExplicitEpubVolumeTitlesAndFilenamesAreRecognized() {
        for (name in listOf("A Book Part 1", "A Book - Part 0 of 3", "A Book - Part 4 of 3", "A Book - Part 1 of 99")) {
            assertEquals(3, WorldBookVolumes.books(three.map { if (it.id == 1L) it.copy(title = name) else it }).size)
        }
        for (source in listOf("user", "PDF 提取文字 · A-Book-Part-1.epub · An Author", "EPUB 提取文字 · A-Book.epub · An Author", "EPUB 提取文字 · A-Book-Part-1.epub")) {
            assertEquals(3, WorldBookVolumes.books(three.map { if (it.id == 1L) it.copy(source = source) else it }).size)
        }
    }
    @Test fun groupingHandlesCaseAndTenVolumesNumericallyWithoutChangingSourceRows() {
        val ten = (10 downTo 1).map { part(it, 10) }
        assertEquals((1L..10L).toList(), WorldBookVolumes.books(ten).single().parts.map { it.id })
        val mixed = three.map { if (it.id == 2L) it.copy(title = it.title.uppercase(), source = it.source.replace("A-Book-Part", "a-book-part")) else it }
        assertEquals(1, WorldBookVolumes.books(mixed).size)
        assertEquals(ten.sortedBy { it.id }, WorldBookVolumes.books(ten).single().parts)
    }
    @Test fun deletingOneVolumeUngroupsWithoutDroppingRemainingVolumesOrTheirProgress() {
        val remaining = three.filter { it.id != 2L }.map { it.copy(readOffset = 1) }
        assertEquals(remaining, WorldBookVolumes.books(remaining).flatMap { it.parts })
    }
    @Test fun completeBookTitleCannotBeRequestedAgainAsANewWish() {
        assertTrue(WorldWishes.duplicate("A Book", emptyList(), three))
        assertFalse(WorldWishes.duplicate("A Book", emptyList(), three.drop(1)))
        assertFalse(WorldWishes.duplicate("A Different Book", emptyList(), three))
    }
}
