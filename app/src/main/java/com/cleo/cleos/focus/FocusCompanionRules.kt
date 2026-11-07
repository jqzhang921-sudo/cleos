package com.cleo.cleos.focus

/** Only the two explicit boundaries of one opted-in focus interval can ask for words. */
object FocusCompanionRules {
    val STATES = setOf("off", "pending", "running", "ready", "silent", "failed", "skipped")
    fun state(s: FocusSession, end: Boolean) = if (end) s.endReplyStatus else s.startReplyStatus
    fun text(s: FocusSession, end: Boolean) = if (end) s.endReply else s.startReply
    fun boundary(s: FocusSession, end: Boolean) = s.companionId != null && s.phase == "focus" &&
        if (end) s.status in setOf("completed", "ended") else s.active
    fun canRequest(s: FocusSession, end: Boolean, retry: Boolean) = boundary(s, end) &&
        (state(s, end) == "pending" || (retry && state(s, end) == "failed"))
}
