package com.cleo.cleos.ui.todo

/** Deduplicates explicit taps and scroll completion; programmatic alignment never selects. */
internal class DateRailSelection(initial: Long?) {
    private var selected = initial
    private var moving = false
    fun external(day: Long?) { selected = day }
    fun choose(day: Long): Boolean {
        if (selected == day) return false
        selected = day
        return true
    }
    fun frame(day: Long, scrolling: Boolean, synchronizing: Boolean): Long? {
        if (synchronizing) { moving = false; return null }
        if (scrolling) { moving = true; return null }
        if (!moving) return null
        moving = false
        return day.takeIf { choose(it) }
    }
}
