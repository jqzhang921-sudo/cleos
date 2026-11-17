package com.cleo.cleos.data

import com.cleo.cleos.ai.WorldReader
import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test

class WorldAlbumConsentTest {
    private val ta = CompanionEntity(id = 1, name = "Reader", persona = "Role", apiBaseUrl = "https://synthetic.invalid/v1", apiModel = "vision", createdAt = 1)
    @Test fun permissionBindsOnlyReadingInputsOwnerAndKeyRevision() {
        val hash = WorldAlbumConsent.fingerprint(ta, "SYNTHETIC_KEY", "revision-1")
        assertTrue(hash.matches(Regex("[a-f0-9]{64}"))); assertFalse(hash.contains("SYNTHETIC_KEY"))
        assertEquals(hash, WorldAlbumConsent.fingerprint(ta.copy(lastLetterTry = 5, avatar = "changed.jpg"), "SYNTHETIC_KEY", "revision-1"))
        for (other in listOf(ta.copy(id = 2), ta.copy(createdAt = 2), ta.copy(name = "Other"), ta.copy(persona = "Other"), ta.copy(apiModel = "other"), ta.copy(apiBaseUrl = "https://other.invalid")))
            assertNotEquals(hash, WorldAlbumConsent.fingerprint(other, "SYNTHETIC_KEY", "revision-1"))
        assertNotEquals(hash, WorldAlbumConsent.fingerprint(ta, "OTHER", "revision-1"))
        assertNotEquals(hash, WorldAlbumConsent.fingerprint(ta, "SYNTHETIC_KEY", "revision-2"))
    }
    @Test fun automaticCandidatesExcludeConfirmedPhotosAndRemainBounded() {
        val photos = (1..15).map { WorldAlbumPhotoEntity(it.toLong(), 1, "a$it.jpg", 400, 300, it.toString(16).padStart(64, '0'), "caption-$it", it.toLong()) }
        val looks = (1..5).map { WorldAlbumLookEntity(it.toLong(), 1, it.toLong(), photos[it-1].sha256, "run-$it", "", "", 100-it.toLong()) }
        val candidates = WorldAlbumConsent.candidates(photos.reversed(), looks)
        assertEquals((6L..15L).toList(), candidates.map { it.id })
        assertTrue(WorldAlbumConsent.candidates(photos.take(5), looks).isEmpty())
    }
    private val photo = WorldAlbumPhotoEntity(1, 1, "a.jpg", 400, 300, "1".repeat(64), "caption", 1)
    private val look = WorldAlbumLookEntity(1, 1, 1, photo.sha256, "seen", "old view", "", 10)

    @Test fun duplicatePixelsAreNotAutomaticallyViewedAgain() {
        val duplicate = photo.copy(id = 2, file = "copy.jpg", createdAt = 2)
        assertEquals(listOf(photo), WorldAlbumConsent.candidates(listOf(duplicate, photo), emptyList()))
        assertTrue(WorldAlbumConsent.candidates(listOf(duplicate), listOf(look)).isEmpty())
        assertEquals(listOf(look), WorldAlbumConsent.history(duplicate, listOf(look)))
    }
    @Test fun otherOwnersOrOldPixelsDoNotCountAsSeenOrEnterHistory() {
        val wrongOwner = look.copy(companionId = 2)
        val wrongPixels = look.copy(photoSha256 = "2".repeat(64))
        assertEquals(listOf(photo), WorldAlbumConsent.candidates(listOf(photo), listOf(wrongOwner, wrongPixels)))
        assertTrue(WorldAlbumConsent.history(photo, listOf(wrongOwner, wrongPixels)).isEmpty())
    }
    @Test fun confirmedViewWithoutCommentStillPreventsAutomaticRepeat() {
        assertTrue(WorldAlbumConsent.candidates(listOf(photo), listOf(look.copy(content = ""))).isEmpty())
        assertEquals(listOf(photo), WorldAlbumConsent.candidates(listOf(photo), emptyList()))
    }
    @Test fun samePhotoHistoryHasStableNewestFirstOrdering() {
        val newer = look.copy(id = 2, createdAt = 20, runId = "new")
        val sameTime = newer.copy(id = 3, runId = "tie")
        assertEquals(listOf(sameTime, newer, look), WorldAlbumConsent.history(photo, listOf(look, newer, sameTime)))
    }
    @Test fun choiceCannotUseAnotherOwnerUnknownIdOrProse() {
        assertEquals(2L, WorldReader.albumChoice("""{"photo_id":2}""", setOf(1,2)))
        assertNull(WorldReader.albumChoice("""{"photo_id":null}""", setOf(1,2)))
        for (bad in listOf("SKIP", "", "{\"photo_id\":", """{"article_id":1}""", """{"photo_id":"1"}""", """{"photo_id":0}""", """{"photo_id":9}""", """{"photo_id":1,"note":"thought"}""")) {
            try { WorldReader.albumChoice(bad, setOf(1,2)); fail(bad) } catch (_: IllegalArgumentException) { }
        }
    }
}
