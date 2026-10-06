package com.cleo.cleos.ai

import org.junit.Assert.*
import org.junit.Test

class ReplyWaitRulesTest {
    @Test fun eachNewMessageRestartsTheQuietWindow() {
        assertFalse(ReplyWaitRules.ready(2000, 2000, 3, false, false))
        assertTrue(ReplyWaitRules.ready(3000, 3000, 3, false, false))
        assertFalse(ReplyWaitRules.ready(1000, 1000, 3, false, false))
    }
    @Test fun composingHoldsButAnUnsentDraftCannotHoldForever() {
        assertFalse(ReplyWaitRules.ready(10000, 10000, 3, true, false))
        assertTrue(ReplyWaitRules.ready(30000, 30000, 3, true, false))
        assertFalse(ReplyWaitRules.ready(10000, 500, 3, false, false))
        assertTrue(ReplyWaitRules.ready(13000, 3500, 3, false, false))
    }
    @Test fun aPendingTranscriptionIsNotReadAsAnEmptyMessage() {
        assertFalse(ReplyWaitRules.ready(60000, 60000, 3, false, true))
        assertTrue(ReplyWaitRules.ready(60000, 60000, 3, false, false))
    }
    @Test fun settingsHaveSafeDefaultsAndSeparateWaits() {
        assertEquals(3, ReplyWaitRules.seconds(-1))
        assertFalse(ReplyWaitRules.ready(5000, 5000, 10, false, false))
        assertTrue(ReplyWaitRules.ready(5000, 5000, 1, false, false))
    }
}
