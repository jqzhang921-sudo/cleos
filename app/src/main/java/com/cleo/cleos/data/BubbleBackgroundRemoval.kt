package com.cleo.cleos.data

import kotlin.math.abs

/** Removes only the region connected to a user-selected background pixel. */
object BubbleBackgroundRemoval {
    fun remove(pixels: IntArray, width: Int, height: Int, seed: Int, tolerance: Int): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        require(seed in pixels.indices)
        val out = pixels.copyOf()
        val chosen = pixels[seed]
        if (chosen ushr 24 == 0) return out
        val limit = tolerance.coerceIn(0, 100)
        val seen = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0
        fun visit(index: Int) {
            if (seen[index]) return
            seen[index] = true
            val color = pixels[index]
            if (color ushr 24 != 0 && listOf(0, 8, 16).any { shift ->
                    abs((color ushr shift and 255) - (chosen ushr shift and 255)) > limit }) return
            queue[tail++] = index
        }
        visit(seed)
        while (head < tail) {
            val index = queue[head++]
            out[index] = out[index] and 0x00ffffff
            if (index % width > 0) visit(index - 1)
            if (index % width < width - 1) visit(index + 1)
            if (index >= width) visit(index - width)
            if (index < pixels.size - width) visit(index + width)
        }
        return out
    }
}
