package com.cleo.cleos.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class CallCaptionTest {
    @Test
    fun repeatedUtterancesKeepTheirOwnPlaceAndSpeaker() {
        val lines = emptyList<CallCaption>()
            .withCaption("好的", fromUser = true)
            .withCaption("好的", fromUser = false)
            .withCaption("  ", fromUser = true)
        assertEquals(listOf(CallCaption(1, true, "好的"), CallCaption(2, false, "好的")), lines)
    }

    @Test
    fun droppingOldCaptionsDoesNotReuseTheirIds() {
        val lines = (1..CALL_CAPTION_LIMIT + 2).fold(emptyList<CallCaption>()) { history, index ->
            history.withCaption("第${index}句", fromUser = index % 2 != 0)
        }
        assertEquals(CALL_CAPTION_LIMIT, lines.size)
        assertEquals(3L, lines.first().id)
        assertEquals(CALL_CAPTION_LIMIT + 2L, lines.last().id)
    }
}
