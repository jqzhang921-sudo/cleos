package com.cleo.cleos.ai

import org.junit.Assert.*
import org.junit.Test

class FollowUpRulesTest {
    @Test fun onlyTheUnchangedReplyCanBeFollowedUp() {
        assertTrue(FollowUpRules.eligible(true, 10, 10, 1000, 1000, false))
        // A new user message or a proactive message invalidates the original reply.
        assertFalse(FollowUpRules.eligible(true, 10, 11, 1000, 1000, false))
        assertFalse(FollowUpRules.eligible(true, null, 10, 1000, 1000, false))
    }
    @Test fun disabledComposingAndBusyConversationsStayQuiet() {
        assertFalse(FollowUpRules.eligible(false, 10, 10, 1000, 1000, false))
        assertFalse(FollowUpRules.eligible(true, 10, 10, 1000, 1000, true))
    }
    @Test fun anOverdueWorkerCannotContinueAnOldConversation() {
        assertFalse(FollowUpRules.eligible(true, 10, 10, 1000, 999, false))
        assertTrue(FollowUpRules.eligible(true, 10, 10, 1000, 1000 + FollowUpRules.GRACE_MS, false))
        assertFalse(FollowUpRules.eligible(true, 10, 10, 1000, 1001 + FollowUpRules.GRACE_MS, false))
        assertFalse(FollowUpRules.eligible(true, 10, 10, null, 1000, false))
    }
    @Test fun settingsUseAValidDefault() {
        assertEquals(60, FollowUpRules.seconds(-1))
        assertEquals(30, FollowUpRules.seconds(30))
        assertEquals(180, FollowUpRules.seconds(180))
    }
}

