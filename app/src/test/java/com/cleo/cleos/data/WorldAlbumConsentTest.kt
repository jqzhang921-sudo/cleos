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
    @Test fun candidatesPreferUnseenThenLeastRecentlySeenAndRemainBounded() {
        val photos = (1..15).map { WorldAlbumPhotoEntity(it.toLong(), 1, "a$it.jpg", 400, 300, "0".repeat(64), "caption-$it", it.toLong()) }
        val looks = (1..5).map { WorldAlbumLookEntity(it.toLong(), 1, it.toLong(), "0".repeat(64), "run-$it", "", "", 100-it.toLong()) }
        val candidates = WorldAlbumConsent.candidates(photos.reversed(), looks)
        assertEquals((6L..15L).toList(), candidates.map { it.id })
        assertEquals(listOf(5L,4L,3L,2L,1L), WorldAlbumConsent.candidates(photos.take(5), looks).map { it.id })
    }
    @Test fun choiceCannotUseAnotherOwnerUnknownIdOrProse() {
        assertEquals(2L, WorldReader.albumChoice("""{"photo_id":2}""", setOf(1,2)))
        assertNull(WorldReader.albumChoice("""{"photo_id":null}""", setOf(1,2)))
        for (bad in listOf("SKIP", "", "{\"photo_id\":", """{"article_id":1}""", """{"photo_id":"1"}""", """{"photo_id":0}""", """{"photo_id":9}""", """{"photo_id":1,"note":"thought"}""")) {
            try { WorldReader.albumChoice(bad, setOf(1,2)); fail(bad) } catch (_: IllegalArgumentException) { }
        }
    }
}
