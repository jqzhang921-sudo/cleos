package com.cleo.cleos.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppUseTest {
    private fun e(at: Long, kind: AppUseEvent.Kind, pkg: String = "", activity: String = "Main") = AppUseEvent(at, kind, pkg, activity)
    private val on = AppUseEvent.Kind.On
    private val unlock = AppUseEvent.Kind.Unlock
    private val resume = AppUseEvent.Kind.Resume
    private val pause = AppUseEvent.Kind.Pause
    private fun ready() = listOf(e(-3, on), e(-2, unlock))
    @Test fun switchingAppsCountsEachAppAndExcludesBackgroundTime() {
        val result = AppUse.estimate(ready() + listOf(e(0,resume,"a"),e(30,pause,"a"),e(30,resume,"b"),e(60,pause,"b")),0,60)!!
        assertEquals(mapOf("a" to 30L,"b" to 30L),result.durations)
        assertFalse(result.incomplete)
    }
    @Test fun sameAppActivitiesAreMergedAndLatePauseDoesNotCloseNewActivity() {
        val result = AppUse.estimate(ready() + listOf(e(0,resume,"a","one"),e(10,resume,"a","two"),e(20,pause,"a","one"),e(30,pause,"a","two")),0,30)!!
        assertEquals(mapOf("a" to 30L),result.durations)
    }
    @Test fun splitScreenMayOverlapAcrossAppsButNeverWithinOneApp() {
        val result = AppUse.estimate(ready() + listOf(e(0,resume,"a"),e(10,resume,"b"),e(20,resume,"a")),0,40)!!
        assertEquals(mapOf("a" to 40L,"b" to 30L),result.durations)
    }
    @Test fun midnightClipsPriorDayAndIgnoresFutureEvents() {
        val result = AppUse.estimate(ready() + listOf(e(-1,resume,"a"),e(15,pause,"a"),e(90,resume,"b")),0,30)!!
        assertEquals(mapOf("a" to 15L),result.durations)
    }
    @Test fun lockedAndScreenOffDoNotCount() {
        val result = AppUse.estimate(ready() + listOf(e(0,resume,"a"),e(10,AppUseEvent.Kind.Lock),e(20,unlock),e(30,AppUseEvent.Kind.Off),e(40,on)),0,50)!!
        assertEquals(mapOf("a" to 30L),result.durations)
    }
    @Test fun restartCannotBridgeOfflineOrUnknownIntervals() {
        val result = AppUse.estimate(ready() + listOf(e(0,resume,"a"),e(10,AppUseEvent.Kind.Shutdown),e(30,AppUseEvent.Kind.Startup),e(40,on),e(45,unlock),e(50,resume,"b")),0,60)!!
        assertEquals(mapOf("a" to 10L,"b" to 10L),result.durations)
        assertTrue(result.incomplete)
    }
    @Test fun absentAppRecordsAreUnavailableNotZero() {
        assertNull(AppUse.estimate(emptyList(),0,30))
        assertNull(AppUse.estimate(ready(),0,30))
    }
    @Test fun unknownScreenOrMissingResumeDoesNotInventUsage() {
        val result = AppUse.estimate(listOf(e(0,resume,"a"),e(10,on),e(20,unlock),e(25,pause,"a"),e(28,pause,"b")),0,30)!!
        assertEquals(mapOf("a" to 5L),result.durations); assertTrue(result.incomplete)
    }
    @Test fun duplicateResumesAndStoppedAfterPauseCannotDoubleCount() {
        val result = AppUse.estimate((ready() + listOf(e(0,resume,"a"),e(0,resume,"a"),e(20,pause,"a"),e(20,AppUseEvent.Kind.Stop,"a"))).reversed(),0,20)!!
        assertEquals(mapOf("a" to 20L),result.durations)
    }
    @Test fun appSharingConsentIsIndependentAndRevokedResultsAreDiscarded() = runBlocking {
        var calls = 0
        var groups = setOf(ToolGroup.PhoneUsage)
        val source = object : PhoneStatusSource {
            override suspend fun battery() = "battery"
            override suspend fun usage() = "total"
            override suspend fun appUsage(): String { calls++; groups=emptySet(); return "PRIVATE_APP_CANARY" }
        }
        val guard=GuardedPhoneStatus(source){ groups }
        try { guard.appUsage(); fail() } catch (_: ToolFailure) { }
        assertEquals(0,calls);assertEquals("total",guard.usage())
        groups=setOf(ToolGroup.PhoneAppUsage)
        try { guard.appUsage(); fail() } catch (e: ToolFailure) { assertFalse(e.result.contains("PRIVATE_APP_CANARY")) }
        assertEquals(1,calls)
    }
}
