package com.cleo.cleos.ai

/** A single opportunity after a normal reply, never after another proactive message. */
object FollowUpRules {
    val OPTIONS = listOf(30, 60, 180)
    const val GRACE_MS = 5 * 60_000L
    fun seconds(value: Int) = value.takeIf { it in OPTIONS } ?: 60
    fun eligible(enabled: Boolean, anchor: Long?, latest: Long?, due: Long?, now: Long, occupied: Boolean): Boolean =
        enabled && anchor != null && anchor == latest && due != null &&
            now >= due && now - due <= GRACE_MS && !occupied

    val instruction = """
        【一次自然的补充机会，不是用户的新消息】
        你刚才已经回复过，对方暂时没有再发消息。你可以自行决定是否还有一句自然想补充的话，
        比如刚想到的细节、顺着刚才的话题分享一点想法，也可以就让对话停在这里。
        没有想说的，就只输出 [SKIP]，不要为了这个机会硬找话题。
        不要催回复、追问为什么不说话，不要把沉默当作拒绝或替对方编造反应。
        有话时简短自然地说，避免重复刚才的回答；不要提系统、定时器或本提示。
        这只是一次机会，不要安排下一次补充。对方说过忙、睡觉、再见或想安静时保持安静。
    """.trimIndent()
}
