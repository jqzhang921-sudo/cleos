package com.cleo.cleos.ai

internal object ReplyWaitRules {
    val OPTIONS = listOf(1, 3, 5, 10)
    const val MAX_HOLD = 30_000L
    fun seconds(value: Int): Int = value.takeIf { it in OPTIONS } ?: 3
    fun ready(sinceSend: Long, sinceQuiet: Long, seconds: Int, composing: Boolean, transcribing: Boolean): Boolean {
        if (transcribing) return false
        if (sinceSend >= MAX_HOLD) return true
        return !composing && sinceQuiet >= seconds(seconds) * 1000L
    }
}
