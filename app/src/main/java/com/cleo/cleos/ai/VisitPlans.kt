package com.cleo.cleos.ai

/** Transient decisions during a reply. Only the final nextAt is persisted by FreeTopics. */
internal class VisitPlans {
    private val revisions = mutableMapOf<Long, Long>()
    private val minutes = mutableMapOf<Long, Int>()
    @Synchronized fun invalidate(id: Long): Long {
        minutes.remove(id)
        return ((revisions[id] ?: 0) + 1).also { revisions[id] = it }
    }
    @Synchronized fun revision(id: Long) = revisions[id] ?: 0
    @Synchronized fun propose(id: Long, value: Int, expected: Long = revision(id)): Boolean {
        if (!current(id, expected)) return false
        minutes[id] = value
        return true
    }
    @Synchronized fun finish(id: Long): Pair<Long, Int?> {
        val choice = minutes.remove(id)
        return invalidate(id) to choice
    }
    @Synchronized fun current(id: Long, revision: Long) = (revisions[id] ?: 0) == revision
}
