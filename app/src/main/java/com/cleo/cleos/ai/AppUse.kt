package com.cleo.cleos.ai

/** Transient system lifecycle metadata; never written to storage or returned to the model. */
data class AppUseEvent(val at: Long, val kind: Kind, val packageName: String = "", val activity: String = "") {
    enum class Kind { Resume, Pause, Stop, On, Off, Unlock, Lock, Shutdown, Startup }
}
data class AppUseEstimate(val durations: Map<String, Long>, val incomplete: Boolean)

object AppUse {
    /** Per-package union of resumed activities, intersected with interactive/unlocked time. */
    fun estimate(events: List<AppUseEvent>, begin: Long, end: Long): AppUseEstimate? {
        require(end >= begin)
        val ordered = events.filter { it.at <= end }.sortedBy { it.at }
        if (ordered.isEmpty()) return null
        val active = mutableSetOf<Pair<String, String>>()
        val totals = mutableMapOf<String, Long>()
        var screen: Boolean? = null
        var unlocked: Boolean? = null
        var cursor = begin
        var partial = false
        var appSeen = false
        fun add(at: Long) {
            val stop = at.coerceIn(begin, end)
            if (stop <= cursor) return
            if (screen == true && unlocked == true) {
                active.map { it.first }.distinct().forEach { pkg -> totals[pkg] = (totals[pkg] ?: 0) + stop - cursor }
                if (active.isEmpty()) partial = true
            } else if (screen != false && unlocked != false) partial = true
            cursor = stop
        }
        for (e in ordered) {
            add(e.at)
            val key = e.packageName to e.activity
            when (e.kind) {
                AppUseEvent.Kind.Resume -> if (e.packageName.isNotBlank()) { active.add(key); appSeen = true }
                AppUseEvent.Kind.Pause, AppUseEvent.Kind.Stop -> if (e.packageName.isNotBlank()) {
                    appSeen = true
                    if (!active.remove(key) && e.kind == AppUseEvent.Kind.Pause && e.at >= begin) partial = true
                }
                AppUseEvent.Kind.On -> screen = true
                AppUseEvent.Kind.Off -> screen = false
                AppUseEvent.Kind.Unlock -> unlocked = true
                AppUseEvent.Kind.Lock -> unlocked = false
                AppUseEvent.Kind.Shutdown -> { screen = false; unlocked = false; active.clear() }
                AppUseEvent.Kind.Startup -> { screen = null; unlocked = null; active.clear() }
            }
        }
        add(end)
        return if (!appSeen) null else AppUseEstimate(totals.filterValues { it > 0 }, partial)
    }
}
