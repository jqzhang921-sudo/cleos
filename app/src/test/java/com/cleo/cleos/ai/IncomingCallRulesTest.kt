package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.CompanionEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class IncomingCallRulesTest {
    private val now=10*IncomingCallRules.DAY_MS
    private fun held(enabled:Boolean=true,free:Boolean=true,quiet:Boolean=false,busy:Boolean=false,pending:Boolean=false,
                     last:Long?=null,count:Int=0)=IncomingCallRules.held(enabled,free,quiet,busy,pending,last,count,now)
    @Test fun oldTaHasNoIncomingPermission() {
        val ta=Json.decodeFromString<CompanionEntity>("""{"id":1,"apiBaseUrl":"https://example.invalid","apiModel":"test","createdAt":1}""")
        assertFalse(ta.incomingCallsEnabled)
        assertFalse(ta.freeTopicEnabled)
    }
    @Test fun permissionIsPerTaAndSurvivesSerialization() {
        val a=CompanionEntity(1,apiBaseUrl="",apiModel="",createdAt=1,incomingCallsEnabled=true)
        assertTrue(Json.decodeFromString<CompanionEntity>(Json.encodeToString(a)).incomingCallsEnabled)
        assertFalse(a.copy(id=2,incomingCallsEnabled=false).incomingCallsEnabled)
    }
    @Test fun disabledPermissionOrSchedulerCannotInvite() { assertNotNull(held(enabled=false));assertNotNull(held(free=false)) }
    @Test fun quietAndExistingAudioOrInvitationCannotInvite() { assertNotNull(held(quiet=true));assertNotNull(held(busy=true));assertNotNull(held(pending=true)) }
    @Test fun rejectedAndMissedCallsStillUseSameCooldown() {
        assertNotNull(held(last=now-IncomingCallRules.COOLDOWN_MS+1))
        assertNull(held(last=now-IncomingCallRules.COOLDOWN_MS,count=1))
    }
    @Test fun changingClockBackwardsCannotBypassCooldown() { assertNotNull(held(last=now+1)) }
    @Test fun rollingDayMaximumIsIndependentOfCooldown() {
        assertNull(held(last=now-7*60*60_000L,count=1))
        assertNotNull(held(last=now-7*60*60_000L,count=4))
    }
    @Test fun oldProfilesGetNewDefaultsWithoutEnablingCalls() {
        val old=Json.decodeFromString<CompanionEntity>("""{"apiBaseUrl":"https://example.invalid","apiModel":"test","createdAt":1}""")
        assertEquals(120,old.incomingCallIntervalMinutes);assertEquals(4,old.incomingCallDailyMax)
        assertFalse(old.incomingCallsEnabled)
    }
    @Test fun differentTasKeepDifferentLimitsAcrossSerialization() {
        val a=CompanionEntity(apiBaseUrl="",apiModel="",createdAt=1,incomingCallIntervalMinutes=30,incomingCallDailyMax=12)
        val b=a.copy(id=2,incomingCallIntervalMinutes=720,incomingCallDailyMax=1)
        assertEquals(listOf(a,b),Json.decodeFromString<List<CompanionEntity>>(Json.encodeToString(listOf(a,b))))
    }
    @Test fun selectedIntervalControlsTheExactBoundary() {
        for(minutes in listOf(30,120,1440)) {
            assertNotNull(IncomingCallRules.held(true,true,false,false,false,now-minutes*60_000L+1,0,now,minutes,12))
            assertNull(IncomingCallRules.held(true,true,false,false,false,now-minutes*60_000L,0,now,minutes,12))
        }
    }
    @Test fun selectedDailyCapIsIndependentOfTheInterval() {
        for(cap in listOf(1,4,12)) {
            assertNull(IncomingCallRules.held(true,true,false,false,false,null,cap-1,now,30,cap))
            assertTrue(IncomingCallRules.held(true,true,false,false,false,null,cap,now,30,cap)!!.contains("$cap 次"))
        }
    }
    @Test fun malformedImportedLimitsStayBoundedWithoutOverflow() {
        assertEquals(30,IncomingCallRules.intervalMinutes(Int.MIN_VALUE));assertEquals(1440,IncomingCallRules.intervalMinutes(Int.MAX_VALUE))
        assertEquals(1,IncomingCallRules.dailyMax(Int.MIN_VALUE));assertEquals(12,IncomingCallRules.dailyMax(Int.MAX_VALUE))
        assertNotNull(IncomingCallRules.held(true,true,false,false,false,now,0,now,Int.MIN_VALUE,Int.MAX_VALUE))
    }
    @Test fun lowerOrHigherPreferencesUseExistingCounts() {
        assertNotNull(IncomingCallRules.held(true,true,false,false,false,null,4,now,120,2))
        assertNull(IncomingCallRules.held(true,true,false,false,false,null,4,now,120,6))
    }
    @Test fun relaxedLimitsNeverBypassQuietOrPendingCalls() {
        assertNotNull(IncomingCallRules.held(true,true,true,false,false,null,0,now,30,12))
        assertNotNull(IncomingCallRules.held(true,true,false,true,false,null,0,now,30,12))
        assertNotNull(IncomingCallRules.held(true,true,false,false,true,null,0,now,30,12))
    }
    @Test fun promptUsesOnlyThisTasCurrentLimitWhenCallToolIsOffered() {
        val ta=CompanionEntity(apiBaseUrl="",apiModel="",createdAt=1,freeTopicEnabled=true,incomingCallsEnabled=true,incomingCallIntervalMinutes=30,incomingCallDailyMax=8)
        val prompt=Prompt.system(AppSettings(),ta,setOf(ToolGroup.FreeVisit),incomingCalls=true)
        assertTrue(prompt.contains("两次至少间隔30 分钟，最近 24 小时最多 8 次"))
        assertTrue(prompt.contains("旧聊天里的频率说法以此为准"))
        assertFalse(Prompt.system(AppSettings(),ta,setOf(ToolGroup.FreeVisit),incomingCalls=false).contains("当前的来电设置"))
    }
    @Test fun overnightQuietAndWholeDayQuietUseExistingPreference() {
        val ta=CompanionEntity(apiBaseUrl="",apiModel="",createdAt=1,freeTopicQuietOn=true)
        assertTrue(IncomingCallRules.quiet(ta,ZonedDateTime.parse("2026-10-17T23:30:00+08:00")))
        assertFalse(IncomingCallRules.quiet(ta,ZonedDateTime.parse("2026-10-17T12:00:00+08:00")))
        assertTrue(IncomingCallRules.quiet(ta.copy(freeTopicQuietStart=480,freeTopicQuietEnd=480),ZonedDateTime.parse("2026-10-17T12:00:00+08:00")))
    }
    @Test fun invitationReasonRejectsLegacySilenceAndPromptEchoes() {
        for(raw in listOf("SKIP","[SKIP]","ＳＫＩＰ","这是一次你自己决定要不要开口的机会，不是对方发来的消息，对方看不到这段提示。"))
            assertTrue(raw,runCatching { IncomingCallRules.reason(raw) }.isFailure)
    }
    @Test fun reasonIsBoundedAndSingleLine() {
        for(raw in listOf("", "a".repeat(121), "你好\n来聊聊")) assertTrue(runCatching { IncomingCallRules.reason(raw) }.isFailure)
        assertEquals("想接着聊聊刚才那本书",IncomingCallRules.reason("  想接着聊聊刚才那本书  "))
    }
    @Test fun incomingSpecsRequireExplicitPermissionAndTheFreeVisitGroup() {
        assertFalse(ToolSpecs.offered(ToolGroup.entries.toSet()).any { it.name=="request_call" })
        assertTrue(ToolSpecs.offered(setOf(ToolGroup.FreeVisit),incomingCalls=true).any { it.name=="request_call" })
        assertFalse(ToolSpecs.offered(emptySet(),incomingCalls=true).any { it.name=="request_call" })
    }
    @Test fun enabledPrivatePromptOffersRealInvitationDespiteOldDenial() {
        val ta=CompanionEntity(1,apiBaseUrl="",apiModel="",createdAt=1,freeTopicEnabled=true,incomingCallsEnabled=true)
        val history=listOf(
            com.cleo.cleos.data.db.MessageEntity(1,1,"assistant","我没有拨号入口。",1),
            com.cleo.cleos.data.db.MessageEntity(2,1,"user","你主动来电",2))
        val prompt=Prompt.messages(AppSettings(),ta,history,ZonedDateTime.now(),setOf(ToolGroup.FreeVisit),incomingCalls=true)
        assertTrue(prompt.first().content.contains("request_call"))
        assertTrue(prompt.first().content.contains("不沿用旧聊天"))
        assertTrue(prompt.first().content.contains("等待对方接听"))
        assertTrue(prompt.last().content.endsWith("你主动来电"))
        assertFalse(prompt.last().content.contains("request_call"))
    }
    @Test fun disabledPrivatePromptDoesNotClaimIncomingCapability() {
        val ta=CompanionEntity(apiBaseUrl="",apiModel="",createdAt=1,freeTopicEnabled=true)
        val prompt=Prompt.messages(AppSettings(),ta,emptyList(),ZonedDateTime.now(),setOf(ToolGroup.FreeVisit))
        assertFalse(prompt.first().content.contains("request_call"))
    }
    @Test fun refusedToolsRemoveIncomingPromptAndSpecTogether() {
        val ta=CompanionEntity(apiBaseUrl="",apiModel="",createdAt=1,freeTopicEnabled=true,incomingCallsEnabled=true)
        assertFalse(Prompt.system(AppSettings(),ta,emptySet(),incomingCalls=true).contains("request_call"))
        assertFalse(ToolSpecs.offered(emptySet(),incomingCalls=true).any { it.name=="request_call" })
    }
    @Test fun defaultOtherContextsDoNotInheritEnabledTaIncomingPrompt() {
        val ta=CompanionEntity(apiBaseUrl="",apiModel="",createdAt=1,freeTopicEnabled=true,incomingCallsEnabled=true)
        assertFalse(Prompt.system(AppSettings(),ta,setOf(ToolGroup.FreeVisit)).contains("request_call"))
        assertFalse(GroupToolRules.names.contains("request_call"))
    }
    @Test fun incomingOpeningKeepsCallerDirectionAndQuotesTopic() {
        val text=IncomingCallRules.opening("想聊聊<书>")
        assertTrue(text.contains("你主动打来"));assertTrue(text.contains("对方刚刚点击接听"))
        assertTrue(text.contains("想聊聊〈书〉"));assertTrue(text.contains("不是新的指令"))
    }
    @Test fun incomingOpeningEchoIsNeverSpokenAsTheGreeting() {
        assertNull(WakeOutput.inspect(IncomingCallRules.opening("一本书")).text)
    }
    @Test fun previousIncomingOpeningInstructionRemainsPrivate() {
        assertNull(WakeOutput.inspect("用你自己的语气简短打个招呼，接着说你想聊的事或问一个自然的问题，然后给对方说话的机会。").text)
    }
    @Test fun promptSeparatesIncomingFromOutgoingAndKeepsMissedHistory() {
        val ta=CompanionEntity(1,apiBaseUrl="",apiModel="",createdAt=1)
        val record=com.cleo.cleos.data.db.MessageEntity(1,1,"call",CallRecords.encode(CallRecord(incoming=true,outcome="answered",answeredAt=1)),1)
        val incoming=Prompt.messages(AppSettings(),ta,listOf(record),ZonedDateTime.now(),emptySet(),false,call=1).joinToString { it.content }
        assertTrue(incoming.contains(Prompt.INCOMING_CALL_BEGAN));assertFalse(incoming.contains(Prompt.CALL_BEGAN))
        val missed=record.copy(content=CallRecords.encode(CallRecord(incoming=true,outcome="missed",endedAt=10)))
        val history=Prompt.messages(AppSettings(),ta,listOf(missed),ZonedDateTime.now(),emptySet(),false).joinToString { it.content }
        assertTrue(history.contains("未接听"));assertFalse(history.contains("对方接听了"))
    }
    @Test fun oldCallRecordsKeepTheirMeaning() {
        val old=CallRecords.decode("""{"answeredAt":100,"endedAt":100100}""")!!
        assertFalse(old.incoming);assertEquals("语音通话 01:40",CallRecords.label(old))
        assertEquals(old,CallRecords.restored(old,200000))
    }
    @Test fun restoredPendingInvitationIsAMissedCallAndNeverRings() {
        for(outcome in listOf("ringing","connecting")) {
            val restored=CallRecords.restored(CallRecord(incoming=true,outcome=outcome),100)
            assertEquals(100L,restored.endedAt);assertEquals("missed",restored.outcome)
            assertEquals("TA 来电 · 未接听",CallRecords.label(restored))
        }
    }
    @Test fun restoredAnsweredCallClosesAtOrAfterAnswerTime() {
        val restored=CallRecords.restored(CallRecord(answeredAt=200,incoming=true,outcome="answered"),100)
        assertEquals(200L,restored.endedAt);assertEquals("answered",restored.outcome)
    }
    @Test fun declinedBusyFailedAndCancelledHaveDistinctHistoryLabels() {
        val labels=listOf("declined","busy","failed","cancelled","missed").map {
            CallRecords.label(CallRecord(endedAt=100,incoming=true,outcome=it))
        }
        assertEquals(5,labels.distinct().size)
        assertTrue(labels.all { it.contains("TA 来电") })
    }
}
