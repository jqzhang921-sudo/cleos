package com.cleo.cleos.ui.world

import org.junit.Assert.*
import org.junit.Test

class WorldDepthTest {
    @Test fun centeredCardRemainsFlatAndFullyReadable() {
        val center = WorldDepth.at(0f, true)
        assertEquals(1f, center.scale, 0f)
        assertEquals(0f, center.rotationY, 0f)
        assertEquals(1f, center.alpha, 0f)
        assertEquals(0f, center.inward, 0f)
    }

    @Test fun neighboringCardsHaveSymmetricPerspective() {
        for (offset in listOf(.1f, .5f, 1f)) {
            val left = WorldDepth.at(-offset, true)
            val right = WorldDepth.at(offset, true)
            assertEquals(left.scale, right.scale, 0f)
            assertEquals(left.alpha, right.alpha, 0f)
            assertEquals(-left.rotationY, right.rotationY, 0f)
            assertEquals(-left.inward, right.inward, 0f)
        }
    }

    @Test fun distantPagesNeverFlipOrCollapse() {
        for (offset in listOf(-Float.MAX_VALUE, -5f, -1f, .5f, 1f, 5f, Float.MAX_VALUE)) {
            val pose = WorldDepth.at(offset, true)
            assertTrue(pose.scale >= .88f && pose.scale <= 1f)
            assertTrue(pose.alpha >= .9f && pose.alpha <= 1f)
            assertTrue(kotlin.math.abs(pose.rotationY) <= 24f)
            assertTrue(kotlin.math.abs(pose.inward) <= 1f)
        }
    }

    @Test fun invalidOffsetsFallBackToFlatInsteadOfPoisoningTheLayer() {
        for (offset in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertEquals(WorldDepth.at(0f, true), WorldDepth.at(offset, true))
        }
    }

    @Test fun reducedMotionMakesEveryCardFlat() {
        for (offset in listOf(-5f, -.5f, 0f, .5f, 5f, Float.NaN)) {
            assertEquals(WorldDepth(1f, 0f, 1f, 0f), WorldDepth.at(offset, false))
        }
    }
}
