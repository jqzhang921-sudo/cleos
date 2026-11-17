package com.cleo.cleos.data

import com.cleo.cleos.ai.*
import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.security.MessageDigest

class MemoryContextTest {
    private val old = MemoryEntity(id=1, companionId=2, kind="recent", name="考试", summary="这周考试",
        details=MemoryDetails.encode(listOf("周四考英语", "周五考数学")), createdAt=1, updatedAt=2)
    private val message = MessageEntity(id=3, conversationId=4, role="user", content="周四考英语，周五考数学", createdAt=10)
    @Test fun provenanceIsPerFactAndDoesNotInventSourcesForOldDetails() {
        val anchored = MemoryContext.revised(null, old, MemoryContext.trace(message))
        assertEquals(3, MemoryContext.traces(anchored).size)
        assertFalse(anchored.provenance.contains("周四考英语"))
        val changed = MemoryContext.revised(anchored, anchored.copy(details=MemoryDetails.encode(listOf("周四考英语", "数学取消了"))))
        assertEquals(2, MemoryContext.traces(changed).size)
        val legacy = MemoryContext.revised(old, old.copy(details=MemoryDetails.encode(listOf("周四考英语", "周五考数学", "考完放假"))), MemoryContext.trace(message))
        assertEquals(1, MemoryContext.traces(legacy).size)
        assertTrue(MemoryContext.traces(legacy).single().target.startsWith("detail:"))
    }
    @Test fun quotesAreExactAndChangedDeletedOrFailedMessagesAreNotProof() {
        val t = MemoryContext.trace(message, "周五考数学")
        assertTrue(t.quoted); assertTrue(MemoryContext.matches(t, message))
        assertFalse(MemoryContext.matches(t, message.copy(content="数学取消")))
        assertFalse(MemoryContext.matches(t, message.copy(id=33)))
        assertFalse(MemoryContext.matches(t, message.copy(note="秘密")))
        assertFalse(MemoryContext.eligible(message.copy(role="tool")))
        assertFalse(MemoryContext.eligible(message.copy(error="失败")))
        assertFalse(MemoryContext.eligible(message.copy(letterId=5)))
        assertTrue(runCatching { MemoryContext.trace(message,"编造的原话") }.isFailure)
    }
    @Test fun malformedOrOversizedProvenanceFailsClosed() {
        assertTrue(MemoryContext.decode("[{}]").isEmpty())
        assertTrue(MemoryContext.decode("x".repeat(24001)).isEmpty())
        val linked=MemoryContext.revised(null,old,MemoryContext.trace(message))
        assertTrue(MemoryContext.decode(linked.provenance.replace("\"messageId\":3","\"messageId\":0")).isEmpty())
        assertTrue(MemoryContext.traces(linked.copy(summary="新摘要",details="[]")).isEmpty())
    }
    @Test fun expiryIsInclusiveAndNeverErasesFactsOrGuessesAnEndDate() {
        val day=LocalDate.parse("2026-11-17")
        val expires=old.copy(validUntilDay=day.toEpochDay())
        assertFalse(MemoryContext.status(expires,day).contains("已过"))
        assertTrue(MemoryContext.status(expires,day.plusDays(1)).contains("已过"))
        assertEquals(old.details,expires.details)
        assertTrue(MemoryContext.status(old,day.plusYears(2)).contains("需结合近况"))
        assertEquals("过去的经历",MemoryContext.status(old.copy(temporalState="past"),day))
        assertTrue(MemoryContext.status(old.copy(validFromDay=day.plusDays(1).toEpochDay()),day).contains("尚未"))
        assertFalse(MemoryContext.valid(expires.copy(validFromDay=day.plusDays(1).toEpochDay())))
    }
    @Test fun oldBackupsGainNoFabricatedSourceAndNewMetadataRoundTrips() {
        val json=Json { encodeDefaults=true }
        val raw=json.encodeToJsonElement(MemoryEntity.serializer(),old).jsonObject
        val legacy=JsonObject(raw.filterKeys { it !in setOf("provenance","temporalState","validFromDay","validUntilDay") })
        val loaded=json.decodeFromJsonElement(MemoryEntity.serializer(),legacy)
        assertEquals(old,loaded); assertTrue(MemoryContext.traces(loaded).isEmpty())
        val next=MemoryContext.revised(null,old.copy(temporalState="past",validUntilDay=20000),MemoryContext.trace(message))
        assertEquals(next,json.decodeFromString(MemoryEntity.serializer(),json.encodeToString(MemoryEntity.serializer(),next)))
        assertTrue(runCatching { MemoryContext.validateBackup(listOf(next),setOf(9)) }.isFailure)
        MemoryContext.validateBackup(listOf(next),setOf(2))
    }
    @Test fun oldGroupGrantsSurviveSchemaUpgradeButMetadataChangesRequireReapproval() {
        val json=Json { encodeDefaults=true }
        val legacy=JsonObject(json.encodeToJsonElement(MemoryEntity.serializer(),old).jsonObject.filterKeys {
            it !in setOf("provenance","temporalState","validFromDay","validUntilDay")
        }).toString()
        val hash=MessageDigest.getInstance("SHA-256").digest(legacy.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(hash,GroupPrivateMemories.fingerprint(old))
        assertNotEquals(hash,GroupPrivateMemories.fingerprint(old.copy(temporalState="past")))
    }
    @Test fun datedHistorySearchUsesLocalInclusiveDatesAndRejectsReversedRanges() {
        val zone=ZoneId.of("Asia/Shanghai")
        val args=buildJsonObject { put("from","2026-11-17");put("to","2026-11-17") }
        val (from,to)=MemoryHistory.window(args,zone)
        assertEquals(86400000,to-from)
        assertEquals(LocalDate.parse("2026-11-17").atStartOfDay(zone).toInstant().toEpochMilli(),from)
        assertTrue(runCatching { MemoryHistory.window(buildJsonObject { put("from","2026-11-18");put("to","2026-11-17") },zone) }.isFailure)
        assertEquals(listOf("考试","英语"),MemoryHistory.terms("考试  英语 考试"))
    }
}
