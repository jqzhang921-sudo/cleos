package com.cleo.cleos.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PhoneStatusTest {
    private fun event(at: Long, kind: ScreenUseEvent.Kind) = ScreenUseEvent(at, kind)
    private val on = ScreenUseEvent.Kind.On
    private val off = ScreenUseEvent.Kind.Off
    private val unlock = ScreenUseEvent.Kind.Unlock
    private val lock = ScreenUseEvent.Kind.Lock
    @Test fun batteryRejectsInvalidReadingsRatherThanInventingZero() {
        assertNull(BatteryReading.from(-1,100,2));assertNull(BatteryReading.from(101,100,2));assertNull(BatteryReading.from(50,0,2))
        assertEquals(BatteryReading(0,false),BatteryReading.from(0,100,3))
        assertEquals(BatteryReading(25,true),BatteryReading.from(50,200,2))
        assertEquals(BatteryReading(100,true),BatteryReading.from(100,100,5))
        assertEquals(BatteryReading(10,null),BatteryReading.from(10,100,-1))
    }
    @Test fun noSystemEventsAreUnavailableNotZero() { assertNull(ScreenUse.estimate(emptyList(),0,100)) }
    @Test fun screenOnBeforeUnlockAndLockedIntervalsDoNotCount() {
        val result=ScreenUse.estimate(listOf(event(0,off),event(1,lock),event(10,on),event(20,unlock),event(50,lock),event(80,off)),0,100)!!
        assertEquals(30L,result.millis);assertFalse(result.incomplete)
    }
    @Test fun reconstructsUseAcrossMidnightAndClipsBothEnds() {
        val result=ScreenUse.estimate(listOf(event(-100,on),event(-99,unlock),event(40,off),event(120,on)),0,100)!!
        assertEquals(40L,result.millis);assertFalse(result.incomplete)
    }
    @Test fun screenOffIsExcludedEvenIfUnlockPersistsForQuickWake() {
        assertEquals(60L,ScreenUse.estimate(listOf(event(0,on),event(0,unlock),event(20,off),event(60,on)),0,100)!!.millis)
    }
    @Test fun missingBeginningIsExplicitlyPartial() {
        val result=ScreenUse.estimate(listOf(event(30,on),event(40,unlock)),0,100)!!
        assertEquals(60L,result.millis);assertTrue(result.incomplete)
    }
    @Test fun repeatedEventsDoNotDoubleCountAndUnsortedInputIsSafe() {
        assertEquals(90L,ScreenUse.estimate(listOf(event(10,unlock),event(80,on),event(1,on),event(1,on),event(100,off)),0,110)!!.millis)
    }
    @Test fun shutdownAndRebootNeverBridgeTheOfflineGap() {
        val result=ScreenUse.estimate(listOf(event(-1,on),event(-1,unlock),event(20,ScreenUseEvent.Kind.Shutdown),event(60,ScreenUseEvent.Kind.Startup),event(70,on),event(80,unlock)),0,100)!!
        assertEquals(40L,result.millis);assertTrue(result.incomplete)
    }
    @Test fun localDaysCanBe23Or25Hours() {
        val zone=ZoneId.of("America/New_York")
        for ((date,hours) in listOf("2026-03-08" to 23,"2026-11-01" to 25)) {
            val day=LocalDate.parse(date);val begin=day.atStartOfDay(zone).toInstant().toEpochMilli();val end=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val result=ScreenUse.estimate(listOf(event(begin-2,on),event(begin-1,unlock)),begin,end)!!
            assertEquals(hours*3_600_000L,result.millis)
        }
    }
    @Test fun formattingIsBoundedAndDoesNotRoundUp() {
        assertEquals("不足 1 分钟",ScreenUse.duration(59_999));assertEquals("1 分钟",ScreenUse.duration(60_000));assertEquals("2 小时 1 分钟",ScreenUse.duration(7_260_000))
    }
    @Test fun revokedBeforeReadDoesNotTouchSource() = runBlocking {
        var calls=0
        val fake=object: PhoneStatusSource { override suspend fun battery():String { calls++;return "battery" };override suspend fun usage():String { calls++;return "usage" } }
        val guard=GuardedPhoneStatus(fake){emptySet()}
        for (read in listOf<suspend ()->String>({guard.battery()},{guard.usage()})) {
            try { read();fail("closed consent") } catch (_:ToolFailure) { }
        }
        assertEquals(0,calls)
    }
    @Test fun revocationDuringReadDiscardsResultAndSwitchesAreIndependent() = runBlocking {
        var groups=setOf(ToolGroup.PhoneBattery)
        val fake=object: PhoneStatusSource {
            override suspend fun battery():String { groups=emptySet();return "private result" }
            override suspend fun usage():String = error("usage is off")
        }
        val guard=GuardedPhoneStatus(fake){groups}
        try { guard.battery();fail("must discard") } catch (e:ToolFailure) { assertFalse(e.result.contains("private result")) }
        try { guard.usage();fail("usage closed") } catch (_:ToolFailure) { }
    }
}
