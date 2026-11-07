package com.cleo.cleos.data

import com.cleo.cleos.data.db.CompanionEntity
import org.junit.Assert.*
import org.junit.Test

class WorldReadingContextTest {
    private val ta = CompanionEntity(id = 10, name = "Fixture", persona = "Interested in wind", createdAt = 1,
        apiBaseUrl = "https://example.invalid/v1", apiModel = "synthetic")
    @Test fun backgroundLetterUpdatesAndUnrelatedAppearanceOrChatPreferencesDoNotInvalidateARead() {
        assertTrue(WorldReading.sameContext(ta, ta.copy(lastLetterTry = 50)))
        assertTrue(WorldReading.sameContext(ta, ta.copy(avatar = "new.jpg", userAvatar = "human.jpg", replyWaitSeconds = 7,
            freeTopicEnabled = true, feedVisitEnabled = true, incomingCallsEnabled = true)))
    }
    @Test fun changedIdentityPromptAndModelStillRejectTheOldResult() {
        assertFalse(WorldReading.sameContext(ta, null))
        for (changed in listOf(ta.copy(id = 11), ta.copy(createdAt = 2), ta.copy(name = "Other"), ta.copy(persona = "Changed"),
            ta.copy(apiBaseUrl = "https://other.invalid"), ta.copy(apiModel = "changed")))
            assertFalse(WorldReading.sameContext(ta, changed))
        assertTrue(WorldReading.sameContext(ta, ta))
    }
}
