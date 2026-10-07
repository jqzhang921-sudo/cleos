package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleAnchorTest {
    @Test fun draggingRightAnchorToLeftStoresLeftEdge() {
        assertEquals("tl" to 0, moveBubbleAnchor("tr", 0, 84f, 24, -60f))
        assertEquals("tl" to -4, moveBubbleAnchor("tr", -64, 84f, 24, -4f))
        // The saved left anchor stays at x=0 on a wider message.
        assertEquals("tl" to 0, moveBubbleAnchor("tl", 0, 280f, 24, 0f))
    }
    @Test fun draggingLeftAnchorToRightPreservesBottomAndVisiblePosition() {
        assertEquals("br" to 0, moveBubbleAnchor("bl", 0, 84f, 24, 60f))
        assertEquals("br" to 0, moveBubbleAnchor("br", 0, 280f, 24, 0f))
        assertEquals("tl" to -24, moveBubbleAnchor("tl", -64, 84f, 24, 0f))
        assertEquals("br" to 24, moveBubbleAnchor("br", 64, 84f, 24, 0f))
    }
}
