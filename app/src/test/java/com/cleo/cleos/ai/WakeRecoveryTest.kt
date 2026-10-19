package com.cleo.cleos.ai

import android.app.ApplicationExitInfo
import org.junit.Assert.*
import org.junit.Test

class WakeRecoveryTest {
    @Test fun updateIsDistinguishedFromCrash() {
        val detail = WakeRecovery.detail(100, 400, listOf(WakeProcessExit(200, ApplicationExitInfo.REASON_PACKAGE_UPDATED)))
        assertTrue(detail.contains("安装了应用更新")); assertFalse(detail.contains("崩溃"))
        assertTrue(detail.contains("未自动重做"))
    }
    @Test fun lowMemoryAndOtherSystemKillsHaveSeparateLabels() {
        assertTrue(WakeRecovery.detail(100, 400, listOf(WakeProcessExit(200, ApplicationExitInfo.REASON_LOW_MEMORY))).contains("内存不足"))
        val detail = WakeRecovery.detail(100, 400, listOf(WakeProcessExit(200, ApplicationExitInfo.REASON_OTHER)))
        assertTrue(detail.contains("进程被结束")); assertTrue(detail.contains("未提供具体原因"))
        assertFalse(detail.contains("崩溃"))
    }
    @Test fun noMatchingExitDoesNotInventTheCause() {
        for (exits in listOf(emptyList(), listOf(WakeProcessExit(99, ApplicationExitInfo.REASON_CRASH)),
            listOf(WakeProcessExit(401, ApplicationExitInfo.REASON_CRASH)))) {
            assertTrue(WakeRecovery.detail(100, 400, exits).contains("没有查到可对应"))
        }
    }
    @Test fun firstExitAfterTheRunStartedIsUsedRegardlessOfHistoryOrder() {
        val detail = WakeRecovery.detail(100, 400, listOf(WakeProcessExit(300, ApplicationExitInfo.REASON_PACKAGE_UPDATED),
            WakeProcessExit(200, ApplicationExitInfo.REASON_CRASH)))
        assertTrue(detail.contains("应用崩溃")); assertFalse(detail.contains("更新"))
    }
    @Test fun prunedHistoryDoesNotAssociateAnUpdateHoursLaterWithAnOldRun() {
        val detail = WakeRecovery.detail(100, 10_000_000, listOf(WakeProcessExit(9_000_000, ApplicationExitInfo.REASON_PACKAGE_UPDATED)))
        assertTrue(detail.contains("没有查到可对应")); assertFalse(detail.contains("安装了应用更新"))
    }
}
