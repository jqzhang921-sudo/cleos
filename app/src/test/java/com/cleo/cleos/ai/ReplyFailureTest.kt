package com.cleo.cleos.ai

import org.junit.Assert.*
import org.junit.Test

class ReplyFailureTest {
    @Test fun thoughtOnlyNetworkFailureExplainsThatNoAnswerArrivedAndKeepsTheOriginalError() {
        val raw = "网络出错：Software caused connection abort"
        val shown = ReplyFailure.message(raw, "", "private fixture thought", network = true)!!
        assertEquals("思考时连接中断，尚未生成回复。\n$raw", shown)
        assertFalse(shown.contains("private fixture thought"))
    }
    @Test fun aPartialAnswerIsNotDescribedAsNoAnswer() {
        assertEquals("fixture error", ReplyFailure.message("fixture error", "public fixture answer", "fixture thought", network = true))
    }
    @Test fun connectionFailureBeforeAnyReasoningKeepsTheOriginalError() {
        assertEquals("fixture error", ReplyFailure.message("fixture error", "", "", network = true))
    }
    @Test fun successAndHttpRefusalsAreNotRelabelledAsNetworkInterruptions() {
        assertNull(ReplyFailure.message(null, "", "fixture thought", network = true))
        assertEquals("请求失败（401）", ReplyFailure.message("请求失败（401）", "", "fixture thought", network = false))
    }
}
