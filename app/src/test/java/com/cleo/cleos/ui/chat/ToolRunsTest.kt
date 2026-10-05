package com.cleo.cleos.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether a tool call's line in the chat says it didn't go through (ChatScreen draws a warning for those). */
class ToolRunsTest {
    @Test
    fun aCallThatDidNotGoThroughSaysSoAtTheEndOfItsLine() {
        assertTrue(ToolRuns.failed("记待办没成：参数写错了"))
        assertTrue(ToolRuns.failed("拍一拍没成：设置里关着"))
        assertTrue(ToolRuns.failed("用某某的订机票没成：对方没有同意"))
        assertTrue(ToolRuns.failed("翻日记出错了"))
        assertFalse(ToolRuns.failed("记了待办：交作业"))
        assertFalse(ToolRuns.failed(""))
    }

    @Test
    fun whatThePersonWroteInTheLineIsNotAFailureOfItsOwn() {
        // Their words ride in the line; one of them saying 没成 doesn't make the call one.
        assertFalse(ToolRuns.failed("记了待办：今天没成功的事"))
        // And a tool they wouldn't let the TA use is their call, not a warning.
        assertFalse(ToolRuns.failed("没让糖糖用某某的订机票"))
    }
}
