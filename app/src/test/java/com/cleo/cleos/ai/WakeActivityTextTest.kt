package com.cleo.cleos.ai

import com.cleo.cleos.data.db.WakeActivityEntity as W
import org.junit.Assert.*
import org.junit.Test

class WakeActivityTextTest {
    private val legacyBusy = W(companionId = 7, source = W.FEED, startedAt = 1,
        finishedAt = 2, status = W.FAILED, detail = FeedWritingBusy.MESSAGE)
    private val cancelled = W(companionId = 7, source = W.FREE, startedAt = 1, status = W.CANCELLED,
        detail = "执行被取消，可能已发送部分内容；可在聊天里查看")

    @Test fun knownLegacyAndNewPreRequestContentionIsDisplayedAsYielding() {
        for (row in listOf(legacyBusy, legacyBusy.copy(status = W.HELD, detail = WakeActivityText.FEED_BUSY))) {
            assertFalse(WakeActivityText.failed(row))
            assertEquals("已有任务，本次让路", WakeActivityText.status(row))
            assertEquals(WakeActivityText.FEED_BUSY, WakeActivityText.detail(row))
            assertEquals("未请求模型", WakeActivityText.requests(row))
        }
        assertEquals(W.FAILED, legacyBusy.status) // Presentation does not rewrite historical rows.
        assertEquals(FeedWritingBusy.MESSAGE, legacyBusy.detail)
    }

    @Test fun anyEvidenceOfWorkPreventsReclassifyingAnOldFailure() {
        for (row in listOf(legacyBusy.copy(requests = 1), legacyBusy.copy(toolCalls = 1), legacyBusy.copy(sent = 1))) {
            assertTrue(WakeActivityText.failed(row))
            assertEquals("执行失败", WakeActivityText.status(row))
            assertEquals(FeedWritingBusy.MESSAGE, WakeActivityText.detail(row))
        }
    }

    @Test fun otherSourcesAndRealFailuresKeepTheirOriginalStatusAndError() {
        for (row in listOf(legacyBusy.copy(source = W.NIGHT), legacyBusy.copy(detail = "synthetic network failure"),
            legacyBusy.copy(detail = "synthetic storage failure"), legacyBusy.copy(detail = ""))) {
            assertTrue(WakeActivityText.failed(row))
            assertEquals("执行失败", WakeActivityText.status(row))
            assertEquals(row.detail, WakeActivityText.detail(row))
        }
    }

    @Test fun preRequestCancellationDoesNotSuggestPartialMessagesWereSent() {
        val detail = WakeActivityText.cancelledDetail(cancelled)
        assertTrue(detail.contains("模型请求前"))
        assertTrue(detail.contains("没有发送"))
        assertFalse(detail.contains("可能"))
        assertEquals(detail, WakeActivityText.detail(cancelled))
        assertEquals("已取消", WakeActivityText.status(cancelled))
        assertEquals("未请求模型", WakeActivityText.requests(cancelled))
    }

    @Test fun onceWorkBeginsCancellationRetainsUncertaintyAboutSideEffects() {
        for (row in listOf(cancelled.copy(requests = 1), cancelled.copy(toolCalls = 1), cancelled.copy(sent = 1))) {
            assertTrue(WakeActivityText.cancelledDetail(row).contains("可能已发送部分"))
            assertFalse(WakeActivityText.cancelledDetail(row).contains("没有发送"))
        }
    }

    @Test fun feedCancellationPointsToTheFeedAndDoesNotInventAChatMessage() {
        val before = WakeActivityText.cancelledDetail(cancelled.copy(source = W.FEED))
        assertTrue(before.contains("模型请求前")); assertTrue(before.contains("没有更新朋友圈"))
        assertFalse(before.contains("聊天"))
        val during = WakeActivityText.cancelledDetail(cancelled.copy(source = W.FEED, requests = 1))
        assertTrue(during.contains("可能已有部分更新")); assertTrue(during.contains("朋友圈查看"))
        assertFalse(during.contains("没有更新"))
    }

    @Test fun genuineInterruptionsAndSpecificCancellationReasonsAreNotRewritten() {
        val interrupted = cancelled.copy(status = W.INTERRUPTED,
            detail = "上次应用退出或进程中断，这次执行未完成", requests = 1)
        assertEquals("执行中断", WakeActivityText.status(interrupted))
        assertEquals(interrupted.detail, WakeActivityText.detail(interrupted))
        val specific = cancelled.copy(detail = "你开始输入或发送了新消息，取消等待；未请求模型")
        assertEquals(specific.detail, WakeActivityText.detail(specific))
    }

    @Test fun requestAndToolCountersRemainVisibleRatherThanBeingResetByPresentation() {
        val row = cancelled.copy(requests = 3, toolCalls = 2, toolSummary = "记东西、发消息")
        assertEquals("模型请求尝试 3 轮 · 工具尝试 2 项", WakeActivityText.requests(row))
        assertEquals(3, row.requests); assertEquals(2, row.toolCalls)
        assertEquals("记东西、发消息", row.toolSummary)
    }
}
