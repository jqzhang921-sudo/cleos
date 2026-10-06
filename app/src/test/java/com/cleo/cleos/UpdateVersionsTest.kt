package com.cleo.cleos

import org.junit.Assert.*
import org.junit.Test

class UpdateVersionsTest {
    @Test fun automaticCheckIsThrottledAndManualCheckCanRetry() {
        val last = 1000L
        assertFalse(UpdateRules.shouldCheck(false, 2000L, last))
        assertTrue(UpdateRules.shouldCheck(true, 2000L, last))
        assertTrue(UpdateRules.shouldCheck(false, last + 86400000L, last))
        assertTrue(UpdateRules.shouldCheck(false, 10L, 0L))
        assertTrue(UpdateRules.shouldCheck(false, 10L, last))
    }
    @Test fun skipAndReminderApplyToOneVersionAndManualCanOverride() {
        assertFalse(UpdateRules.shouldPrompt(false, "0.42.0", "0.42.0", null))
        assertFalse(UpdateRules.shouldPrompt(false, "0.42.0", null, "0.42.0"))
        assertTrue(UpdateRules.shouldPrompt(false, "0.43.0", "0.42.0", "0.42.0"))
        assertTrue(UpdateRules.shouldPrompt(true, "0.42.0", "0.42.0", "0.42.0"))
    }

    @Test fun comparesNumericSegmentsRatherThanStrings() {
        assertTrue(UpdateVersions.newer("v0.41.10", "0.41.8"))
        assertFalse(UpdateVersions.newer("0.41.8", "0.41.8"))
        assertFalse(UpdateVersions.newer("0.40.99", "0.41.8"))
        assertFalse(UpdateVersions.newer("1.0.0", "1.0"))
        assertTrue(UpdateVersions.newer("1.1", "1.0.99"))
        assertFalse(UpdateVersions.newer("v0.42.0-beta.1", "0.41.8"))
    }
    @Test fun invalidReleaseOrResponseIsFailureNotLatest() {
        assertEquals(UpdateResult.Failed, UpdateVersions.parse("not json", "0.41.8"))
        assertEquals(UpdateResult.Failed, UpdateVersions.parse("{\"message\":\"Not Found\"}", "0.41.8"))
        assertEquals(UpdateResult.Failed, UpdateVersions.parse("{\"tag_name\":\"v0.42.0-beta\",\"draft\":false,\"prerelease\":true}", "0.41.8"))
    }
    @Test fun stableReleaseReturnsNotesAndCorrectStatus() {
        val raw = "{\"tag_name\":\"v0.42.0\",\"body\":\"更新说明\",\"draft\":false,\"prerelease\":false}"
        assertEquals(UpdateResult.Available(UpdateInfo("0.42.0", "更新说明")), UpdateVersions.parse(raw, "0.41.8"))
        assertEquals(UpdateResult.Current, UpdateVersions.parse(raw, "0.42.0"))
    }
}
