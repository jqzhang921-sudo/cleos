package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class InstantLetterRulesTest {
    private val now = 100 * InstantLetterRules.DAY
    private fun user(text: String) = MessageEntity(10,1,"user",text,now)
    private fun receipt(at: Long, request: Long? = null) = LetterDispatchEntity("1:chat:1:$at",1,2,at,request)
    private fun ta(mode: String = InstantLetterRules.REQUESTED) = CompanionEntity(id=1,apiBaseUrl="",apiModel="",createdAt=1,instantLetterMode=mode)

    @Test fun directRequestsAreTakenFromActualUserMessages() {
        listOf("给我写一封信吧。","可以给我写封信吗？","你能给我写一封短一点的信吗","请写一封信","回信","写封信给我吧","我想要一封你的信","Write me a letter","Could you write me a letter?","Please send a letter to me").forEach {
            assertEquals(it,10L,InstantLetterRules.requested(user(it)))
        }
    }
    @Test fun directLetterRequestsCanOmitTheNounAfterTheLetterMeasureWord() {
        listOf("你也给我写一封","给我也写一封吧","那你也给我回一封好吗","请给我寄一封","能不能给我写一封？","你也写一封吧","回一封").forEach {
            val request=InstantLetterRules.requested(user(it))
            assertEquals(it,10L,request)
            assertTrue(InstantLetterRules.available(InstantLetterRules.REQUESTED,request))
            assertFalse(InstantLetterRules.available(InstantLetterRules.OFF,request))
        }
        listOf("不要给我写一封","她说你也给我写一封","你也给我写一封是什么意思","比如给我写一封","翻译：给我写一封","给我写一篇","给我写一封投诉信的教程","好像给我写一封").forEach {
            assertNull(it,InstantLetterRules.requested(user(it)))
        }
    }
    @Test fun capabilityDiscoveryDoesNotGrantDeliveryPermissionOrResurrectDisabledTools() {
        for(text in listOf("现在能写了","你现在有个letter相关的工具吧","有没有写信工具")) {
            val request=InstantLetterRules.requested(user(text));assertNull(text,request)
            assertTrue(InstantLetterRules.offered(InstantLetterRules.REQUESTED))
            assertFalse(InstantLetterRules.available(InstantLetterRules.REQUESTED,request))
            assertNotNull(InstantLetterRules.held(InstantLetterRules.REQUESTED,request,emptyList(),now))
        }
        assertFalse(InstantLetterRules.offered(InstantLetterRules.OFF))
        assertFalse(InstantLetterRules.available(InstantLetterRules.REQUESTED,null,wake=true))
        assertTrue(InstantLetterRules.available(InstantLetterRules.AUTO,null,wake=true))
    }
    @Test fun oldCapabilityDenialsStayInHistoryButCurrentSystemDescribesTheActualToolAndConsent() {
        val old=MessageEntity(9,1,"assistant","我没有能寄信的入口",now-1)
        val current=user("现在能写了")
        val enabled=InstantLetterRules.offered(InstantLetterRules.REQUESTED)
        val earlier=user("给我写一封信").copy(id=8,createdAt=now-2)
        val rows=Prompt.messages(AppSettings(),ta(),listOf(earlier,old,current),ZonedDateTime.now(),setOf(ToolGroup.Letters),instantLetters=enabled)
        val system=rows.first{it.role=="system"}.content
        assertTrue(system.contains("当前能力以本轮工具清单为准"))
        assertTrue(system.contains("只有对方这次明确请你写信"))
        assertTrue(rows.any{it.role=="assistant" && it.content.contains(old.content)})
        assertTrue(ToolSpecs.offered(setOf(ToolGroup.Letters),instantLetters=enabled).any{it.name==ToolSpecs.writeInstantLetter.name})
        assertFalse(ToolSpecs.offered(emptySet(),instantLetters=enabled).any{it.name==ToolSpecs.writeInstantLetter.name})
    }
    @Test fun quotedNegatedAndExplanatoryTextDoesNotGrantExemption() {
        listOf("不要给我写信","不用写信了","她说给我写封信","比如给我写一封信","怎么写一封信","翻译给我写一封信","“给我写封信”是什么意思","don't write me a letter","how to write a letter","来聊聊今天吧","谢谢你的信").forEach {
            assertNull(it,InstantLetterRules.requested(user(it)))
        }
    }
    @Test fun modelMessagesToolsDraftsAndSharedContentCannotGrantPermission() {
        val m=user("给我写一封信")
        listOf(m.copy(role="assistant"),m.copy(role="tool"),m.copy(note="工具回声"),m.copy(letterId=2),m.copy(feedShare="shared"),m.copy(id=0)).forEach { assertNull(InstantLetterRules.requested(it)) }
    }
    @Test fun askingAboutAnOldLetterIsNotANewLetterRequest() {
        listOf("你有没有给我写信？","昨天给我写的信呢","之前你说要给我写信","Did you write me a letter yesterday?").forEach {
            assertNull(it,InstantLetterRules.requested(user(it)))
        }
    }
    @Test fun explicitClientActionDoesNotDependOnAModelRequestedFlag() {
        assertEquals(10L,InstantLetterRules.requested(user("来一封").copy(letterRequested=true)))
        assertNull(InstantLetterRules.requested(user("来一封").copy(role="assistant",letterRequested=true)))
        assertThrows(IllegalArgumentException::class.java) { InstantLetterRules.arguments("""{"content":"你好","requested":true}""") }
    }
    @Test fun modesArePerTaAndUnknownModesFailClosed() {
        assertEquals(InstantLetterRules.REQUESTED,InstantLetterRules.mode("unknown"))
        assertFalse(InstantLetterRules.available("off",10))
        assertFalse(InstantLetterRules.available("requested",null))
        assertFalse(InstantLetterRules.available("requested",10,wake=true))
        assertTrue(InstantLetterRules.available("auto",null,wake=true))
        assertEquals(ta("auto"),Json.decodeFromString<CompanionEntity>(Json.encodeToString(ta("auto"))))
    }
    @Test fun automaticCooldownBoundaryAndRollingDayQuotaAreIndependent() {
        assertNotNull(InstantLetterRules.held("auto",null,listOf(receipt(now-InstantLetterRules.COOLDOWN+1)),now))
        assertNull(InstantLetterRules.held("auto",null,listOf(receipt(now-InstantLetterRules.COOLDOWN)),now))
        assertNotNull(InstantLetterRules.held("auto",null,listOf(receipt(now-7*3600000L),receipt(now-15*3600000L)),now))
        assertNull(InstantLetterRules.held("auto",null,listOf(receipt(now-7*3600000L),receipt(now-InstantLetterRules.DAY-1)),now))
        assertNotNull(InstantLetterRules.held("auto",null,listOf(receipt(now+1)),now))
    }
    @Test fun freshExplicitRequestsBypassAutomaticLimitsButNotDeduplicationOrOff() {
        val ledger=listOf(receipt(now-1),receipt(now-100),receipt(now-200))
        assertNull(InstantLetterRules.held("requested",10,ledger,now))
        assertNull(InstantLetterRules.held("auto",10,ledger,now))
        assertNotNull(InstantLetterRules.held("off",10,ledger,now))
        assertNotNull(InstantLetterRules.held("requested",10,ledger+receipt(now-1,10),now))
    }
    @Test fun deletingLetterDoesNotDeleteTheQuotaReceipt() {
        val ledger=listOf(receipt(now-1))
        InstantLetterRules.validateReceipts(ledger,emptyList(),setOf(1L))
        assertNotNull(InstantLetterRules.held("auto",null,ledger,now))
    }
    @Test fun structuredArgumentsAreTypedBoundedAndNonEmptyWithoutMinimumLength() {
        val parsed=InstantLetterRules.arguments("""{"title":"给你","content":"嗨\n\n😀","theme_id":"default"}""")
        assertEquals("嗨\n\n😀",parsed.body);assertEquals("给你",parsed.title)
        assertEquals("字",InstantLetterRules.arguments("""{"content":"字"}""").body)
        listOf("not json","[]","{}","""{"content":"  "}""","""{"content":123}""","""{"content":["a"]}""","""{"content":"ok","title":true}""","""{"content":"ok","theme_id":null}""").forEach { assertTrue(it,runCatching{InstantLetterRules.arguments(it)}.isFailure) }
    }
    @Test fun protocolEnvelopesAreNeverDisplayedOrParsedAsOrdinaryBody() {
        val raw="""{"message_type":"LETTER","content":"internal"}"""
        assertTrue(InstantLetterRules.protocolPayload(raw));assertEquals("",InstantLetterRules.preview(raw,false))
        assertEquals("",InstantLetterRules.preview("{\"message_",true))
        assertEquals("普通聊天",InstantLetterRules.preview("普通聊天",true))
        assertTrue(runCatching{InstantLetterRules.arguments(Json.encodeToString(mapOf("content" to raw)))}.isFailure)
    }
    @Test fun storedToolAuditKeepsOnlyReferencesNotASecondBodyOrSkippedMessages() {
        val calls=listOf(ToolCall("a",ToolSpecs.writeInstantLetter.name,"""{"content":"PRIVATE_BODY"}"""),ToolCall("b",ToolSpecs.sendMessage.name,"""{"text":"PRIVATE_BODY"}"""))
        val stored=InstantLetterRules.storedCalls(calls,21)
        assertFalse(stored.any{it.arguments.contains("PRIVATE_BODY")});assertTrue(stored.first().arguments.contains("#21"))
        assertEquals(calls.map{it.id},stored.map{it.id})
    }
    @Test fun writeToolNeedsBothPrivateContextAndLetterPermission() {
        assertFalse(ToolSpecs.offered(ToolGroup.entries.toSet()).any{it.name==ToolSpecs.writeInstantLetter.name})
        assertTrue(ToolSpecs.offered(setOf(ToolGroup.Letters),instantLetters=true).any{it.name==ToolSpecs.writeInstantLetter.name})
        assertFalse(ToolSpecs.offered(emptySet(),instantLetters=true).any{it.name==ToolSpecs.writeInstantLetter.name})
        assertFalse(GroupToolRules.names.contains(ToolSpecs.writeInstantLetter.name))
    }
    @Test fun letterHistoryIsNotRewrittenAsSeparateTextMessageTools() {
        val at=100L
        val l=LetterEntity(3,1,"ai","Unique letter body",at,deliverAt=at,sourceType="instant",envelope=InstantLetterData.encode(InstantLetterData.defaultEnvelope))
        val root=MessageEntity(2,1,"assistant","",98,toolCalls=ToolCallCodec.encode(InstantLetterRules.storedCalls(listOf(ToolCall("write1",ToolSpecs.writeInstantLetter.name,"{}")),3)))
        val result=MessageEntity(3,1,"tool","已寄出",99,toolCallId="write1")
        val envelope=MessageEntity(4,1,"assistant","",at,letterId=3)
        val history=InstantLetterData.resolve(listOf(user("给我写封信").copy(id=1,createdAt=90),root,result,envelope),listOf(l),1)
        val prompt=Prompt.messages(AppSettings(),ta(),history,ZonedDateTime.now(),setOf(ToolGroup.Letters,ToolGroup.Messages),instantLetters=true)
        assertTrue(prompt.any{it.toolCalls.any{call->call.name==ToolSpecs.writeInstantLetter.name}})
        assertTrue(prompt.any{it.role=="tool"&&it.toolCallId=="write1"})
        assertTrue(prompt.any{it.role=="assistant"&&it.content.contains(l.content)})
        assertFalse(prompt.any{it.toolCalls.any{call->call.name==ToolSpecs.sendMessage.name&&call.arguments.contains(l.content)}})
    }
    @Test fun backupLedgerValidatesOwnersAndKeepsDeletedLetterReceipts() {
        InstantLetterRules.validateReceipts(listOf(receipt(now)),emptyList(),setOf(1L))
        assertTrue(runCatching{InstantLetterRules.validateReceipts(listOf(receipt(now)),emptyList(),setOf(2L))}.isFailure)
        assertTrue(runCatching{InstantLetterRules.validateReceipts(listOf(receipt(now),receipt(now)),emptyList(),setOf(1L))}.isFailure)
        assertTrue(runCatching{InstantLetterRules.validateReceipts(listOf(receipt(now).copy(origin="wrong")),emptyList(),setOf(1L))}.isFailure)
    }
}
