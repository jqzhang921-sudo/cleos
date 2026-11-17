package com.cleo.cleos.data

import com.cleo.cleos.ai.MemoryOrganizer
import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MemoryRulesTest {
    private fun row(id:Long=1,name:String="口味",summary:String="饮食偏好",details:List<String> = listOf("喜欢绿茶"))=
        MemoryEntity(id=id,companionId=1,kind="interest",name=name,summary=summary,details=MemoryDetails.encode(details),createdAt=1,updatedAt=2)
    @Test fun normalizationDeduplicatesPresentationWithoutCollapsingNegationOrNumbers() {
        assertEquals(listOf("Coffee", "不喝咖啡", "1.0", "10"),MemoryRules.unique(listOf("Coffee"," Ｃｏｆｆｅｅ ","coffee","不喝咖啡","1.0","10")))
        assertNotEquals(MemoryRules.normalize("喜欢咖啡"),MemoryRules.normalize("不喜欢咖啡"))
        assertEquals(MemoryRules.hashes(row(details=listOf(" Coffee  daily "))),MemoryRules.hashes(row(details=listOf("coffee daily"))))
    }
    @Test fun appendedFactsAreSafeButRewritesAndTimeChangesAreDestructive() {
        val m=row()
        assertFalse(MemoryRules.destructive(m,m.copy(details=MemoryDetails.encode(listOf("喜欢绿茶","不加糖")))))
        assertTrue(MemoryRules.destructive(m,m.copy(details=MemoryDetails.encode(listOf("不喜欢绿茶")))))
        assertTrue(MemoryRules.destructive(m,m.copy(summary="喝咖啡")))
        assertTrue(MemoryRules.destructive(m,m.copy(temporalState="past")))
        assertNotEquals(MemoryRules.revision(m),MemoryRules.revision(m.copy(updatedAt=3)))
    }
    @Test fun mergeRetainsAllDistinctFactsAndAttributionIncludingMovedSummary() {
        val message=MessageEntity(id=5,conversationId=6,role="user",content="喜欢绿茶和无糖咖啡",createdAt=1)
        val a=MemoryContext.revised(null,row(),MemoryContext.trace(message))
        val b=MemoryContext.revised(null,row(2,summary="无糖咖啡",details=listOf("喜欢绿茶","咖啡不加糖")),MemoryContext.trace(message))
        val merged=MemoryRules.merge(listOf(a,b),9)
        assertEquals(listOf("喜欢绿茶","咖啡不加糖","无糖咖啡"),MemoryDetails.decode(merged.details))
        assertEquals(4,MemoryContext.traces(merged).size)
        assertTrue(MemoryContext.traces(merged).any { it.target=="detail:"+MemoryContext.hash("无糖咖啡") })
        assertEquals(a.summary,merged.summary)
    }
    @Test fun mergeRejectsOwnershipPinsTimeDifferencesAndOverflowWithoutDroppingText() {
        val a=row();val b=row(2)
        for(other in listOf(b.copy(companionId=2),b.copy(kind="self"),b.copy(pinned=true),b.copy(temporalState="past"),b.copy(validUntilDay=1)))
            assertTrue(runCatching{MemoryRules.merge(listOf(a,other),3)}.isFailure)
        val full=row(details=(1..30).map{"事 $it"})
        assertTrue(runCatching{MemoryRules.merge(listOf(full,b.copy(details=MemoryDetails.encode(listOf("额外内容")))),3)}.isFailure)
        assertEquals(30,MemoryDetails.decode(full.details).size)
    }
    @Test fun localOrganizerSuggestsMergingExactTopicsAndReviewingDifferentStatements() {
        val a=row();val b=row(2)
        assertEquals("merge",MemoryOrganizer.local(listOf(a,b)).single().action)
        assertEquals("review",MemoryOrganizer.local(listOf(a,b.copy(summary="不喜欢绿茶"))).single().action)
        assertEquals("review",MemoryOrganizer.local(listOf(a,b.copy(pinned=true))).single().action)
        assertTrue(MemoryOrganizer.local(listOf(a,b.copy(companionId=2,name="天气"))).isEmpty())
    }
    @Test fun modelOutputCannotInventIdsRewriteTextOrMergePinnedMemories() {
        val rows=listOf(row(),row(2))
        assertEquals(1,MemoryOrganizer.parse("""```json
            {"suggestions":[{"action":"merge","ids":[1,2],"reason":"同一话题"}]}
            ```""".trimIndent(),rows).size)
        for(raw in listOf("""{"suggestions":[{"action":"merge","ids":[1,99],"reason":"x"}]}""",
            """{"suggestions":[{"action":"forget","ids":[1,2],"reason":"x"}]}""",
            """{"suggestions":[{"action":"merge","ids":[1,1],"reason":"x"}]}""","不是JSON"))
            assertTrue(runCatching{MemoryOrganizer.parse(raw,rows)}.isFailure)
        assertTrue(runCatching{MemoryOrganizer.parse("""{"suggestions":[{"action":"merge","ids":[1,2],"reason":"x"}]}""",rows.map{it.copy(pinned=true)})}.isFailure)
    }
    @Test fun organizerInputIsBoundedAndKeepsCompleteDetailsRatherThanCuttingFacts() {
        val rows=(1L..50).map { row(it,details=(1..30).map{"这是完整事实".repeat(30)+it}) }
        val input=MemoryOrganizer.request(rows).last().content
        assertTrue(input.length<=40000)
        assertTrue(input.contains("details_omitted"))
        assertFalse(input.contains("conversationId"))
    }
    @Test fun changeAndForgettingRulesRoundTripAndRejectInvalidBackupOwners() {
        val m=row()
        val change=MemoryChangeEntity(id=1,companionId=1,action="update",actor="ai",state="pending",beforeRows=MemoryRules.encode(listOf(m)),afterRows=MemoryRules.encode(listOf(m.copy(summary="新摘要"))),createdAt=10)
        val block=MemoryBlockEntity(1,MemoryRules.hashes(m).first(),10)
        val j=Json { encodeDefaults=true }
        assertEquals(change,j.decodeFromString(MemoryChangeEntity.serializer(),j.encodeToString(MemoryChangeEntity.serializer(),change)))
        MemoryRules.validateBackup(listOf(change),listOf(block),setOf(1))
        assertTrue(runCatching{MemoryRules.validateBackup(listOf(change),listOf(block),setOf(2))}.isFailure)
        assertTrue(runCatching{MemoryRules.validateBackup(listOf(change.copy(state="unrecognized")),emptyList(),setOf(1))}.isFailure)
        assertTrue(runCatching{MemoryRules.validateBackup(emptyList(),listOf(block.copy(factHash="clear text")),setOf(1))}.isFailure)
    }
}
