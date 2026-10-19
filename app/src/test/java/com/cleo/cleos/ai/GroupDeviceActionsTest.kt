package com.cleo.cleos.ai

import org.junit.Assert.*
import org.junit.Test

class GroupDeviceActionsTest {
    private val alarm = ToolCall("new", "set_alarm", """{"time":"07:30","repeat":"工作日","label":"起床"}""")
    private val done = GroupToolRecord("old", "set_alarm", """{"time":"7点半","repeat":"一二三四五","label":"上课"}""",
        "returned", "synthetic clock result", "定了闹钟：工作日 07:30")

    @Test fun anotherMemberCannotCreateTheSameAlarmByChangingItsLabelOrArgumentOrder() {
        val result = GroupDeviceActions.previous(alarm, listOf(done))!!
        assertTrue(result.result.contains("synthetic clock result"))
        assertTrue(result.result.contains("没有重复创建"))
        assertNull(GroupDeviceActions.previous(alarm.copy(arguments = """{"time":"08:30","repeat":"工作日"}"""), listOf(done)))
        assertNull(GroupDeviceActions.previous(alarm.copy(arguments = """{"time":"07:30"}"""), listOf(done)))
    }

    @Test fun uncertainAttemptIsNotRetriedAndDoesNotClaimSuccess() {
        for (state in listOf("running", "failed")) {
            val result = GroupDeviceActions.previous(alarm, listOf(done.copy(state = state, result = "", note = "")))!!
            assertTrue(result.result.contains("状态未确认"))
            assertTrue(result.result.contains("不能声称已经成功"))
        }
        assertNull(GroupDeviceActions.previous(alarm, listOf(done.copy(note = "定闹钟没成：未授权"))))
    }

    @Test fun timerUsesActualSecondsAndUnrelatedOrMalformedCallsAreNotDeduplicated() {
        val timer = ToolCall("timer", "set_timer", """{"minutes":0.5}""")
        val prior = GroupToolRecord("old-timer", "set_timer", """{"minutes":"0.50","label":"different"}""",
            "returned", "30 seconds fixture", "开了计时器：30 秒")
        assertNotNull(GroupDeviceActions.previous(timer, listOf(prior)))
        assertNull(GroupDeviceActions.previous(timer.copy(arguments = """{"minutes":1}"""), listOf(prior)))
        assertNull(GroupDeviceActions.previous(alarm.copy(arguments = "{}"), listOf(done)))
        assertNull(GroupDeviceActions.previous(alarm.copy(name = "read_calendar"), listOf(done)))
    }

    @Test fun guardReceiptRetainsSuccessOrUncertaintyWhenTheOriginalReplyTraceIsReplacedOnRetry() {
        for (old in listOf(done, done.copy(state = "running", result = "", note = ""))) {
            val first = GroupDeviceActions.previous(alarm, listOf(old))!!
            val receipt = GroupToolRecord("retry", alarm.name, alarm.arguments, "returned", first.result, first.note)
            val again = GroupDeviceActions.previous(alarm, listOf(receipt))!!
            assertEquals(first.note, again.note)
            assertTrue(again.result.contains("没有重复创建"))
        }
    }
}
