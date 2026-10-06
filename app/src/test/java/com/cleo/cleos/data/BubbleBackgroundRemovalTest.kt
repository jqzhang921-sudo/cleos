package com.cleo.cleos.data

import org.junit.Assert.*
import org.junit.Test

class BubbleBackgroundRemovalTest {
    @Test fun preservesEnclosedWhiteDetailAndOriginalPixels() {
        val white = -1
        val black = 0xff000000.toInt()
        val pixels = IntArray(25) { white }
        for (y in 1..3) for (x in 1..3) pixels[y * 5 + x] = black
        pixels[12] = white
        val out = BubbleBackgroundRemoval.remove(pixels, 5, 5, 0, 10)
        assertEquals(0, out[0] ushr 24)
        assertEquals(white, out[12])
        assertEquals(black, out[6])
        assertEquals(white, pixels[0])
    }
    @Test fun toleranceExpandsOnlyConnectedMatchingRegion() {
        val pixels = intArrayOf(0xffeeeeee.toInt(), 0xffdddddd.toInt(), 0xff000000.toInt(), 0xffeeeeee.toInt())
        assertEquals(255, BubbleBackgroundRemoval.remove(pixels, 4, 1, 0, 10)[1] ushr 24)
        val out = BubbleBackgroundRemoval.remove(pixels, 4, 1, 0, 20)
        assertEquals(0, out[1] ushr 24)
        assertEquals(255, out[3] ushr 24)
    }
    @Test fun transparentSeedKeepsImageUnchanged() {
        val pixels = intArrayOf(0, -1)
        assertArrayEquals(pixels, BubbleBackgroundRemoval.remove(pixels, 2, 1, 0, 20))
    }
}
