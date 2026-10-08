package com.cleo.cleos.ai

import com.cleo.cleos.data.db.WakeActivityEntity as W

/** Truthful labels: an application rule skipping is different from a model choosing silence. */
object WakeActivityText {
    fun source(value: String) = when (value) {
        W.FREE -> "想找你聊"
        W.FEED -> "主动逛朋友圈"
        W.FOLLOW_UP -> "还有句话"
        W.MORNING -> "早安招呼"
        W.NIGHT -> "睡前招呼"
        else -> "记下的事到时间了"
    }
    fun status(w: W) = when (w.status) {
        W.RUNNING -> w.phase
        W.ACTED -> "已更新朋友圈"
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
