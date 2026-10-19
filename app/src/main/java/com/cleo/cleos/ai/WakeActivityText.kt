package com.cleo.cleos.ai

import com.cleo.cleos.data.db.WakeActivityEntity as W

/** Truthful labels: an application rule skipping is different from a model choosing silence. */
object WakeActivityText {
    const val FEED_BUSY = "朋友圈里已有创作或互动正在进行，本次先让路；未请求模型，也没有更新朋友圈。"
    private const val LEGACY_CANCELLED = "执行被取消，可能已发送部分内容；可在聊天里查看"
    private fun noWork(w: W) = w.requests == 0 && w.toolCalls == 0 && w.sent == 0
    private fun feedContention(w: W) = w.source == W.FEED && noWork(w) &&
        ((w.status == W.FAILED && w.detail == FeedWritingBusy.MESSAGE) ||
            (w.status == W.HELD && w.detail == FEED_BUSY))

    /** Known legacy local contention may be labelled truthfully without rewriting old records. */
    fun failed(w: W) = w.status == W.FAILED && !feedContention(w)

    fun cancelledDetail(w: W) = when {
        noWork(w) && w.source == W.FEED -> "本次逛逛在模型请求前已取消，没有更新朋友圈。"
        noWork(w) -> "执行在模型请求前已取消，没有发送内容。"
        w.source == W.FEED -> "本次逛逛已取消，可能已有部分更新；可在朋友圈查看。"
        else -> LEGACY_CANCELLED
    }

    fun detail(w: W) = when {
        feedContention(w) -> FEED_BUSY
        w.status == W.CANCELLED && w.detail == LEGACY_CANCELLED -> cancelledDetail(w)
        else -> w.detail
    }

    fun source(value: String) = when (value) {
        W.FREE -> "想找你聊"
        W.FEED -> "主动逛朋友圈"
        W.FOLLOW_UP -> "还有句话"
        W.MORNING -> "早安招呼"
        W.NIGHT -> "睡前招呼"
        else -> "记下的事到时间了"
    }
    fun status(w: W): String = if (feedContention(w)) "已有任务，本次让路" else when (w.status) {
        W.RUNNING -> w.phase
        W.ACTED -> "已更新朋友圈"
        "call_invited" -> "已邀请语音通话"
        W.SENT -> "已发送 ${w.sent} 条消息"
        W.QUIET -> "选择保持安静"
        W.HELD -> "规则跳过"
        W.CANCELLED -> "已取消"
        W.EXPIRED -> "机会已过时"
        W.INTERRUPTED -> "执行中断"
        else -> "执行失败"
    }
    fun requests(w: W) = if (w.requests == 0) "未请求模型" else "模型请求尝试 ${w.requests} 轮" +
        if (w.toolCalls > 0) " · 工具尝试 ${w.toolCalls} 项" else ""
}
