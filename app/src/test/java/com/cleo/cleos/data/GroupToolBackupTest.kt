package com.cleo.cleos.data

import com.cleo.cleos.ai.*
import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GroupToolBackupTest {
    private val groups = listOf(ChatGroupEntity(1, "group", 0, 0))
    private val members = listOf(GroupMemberEntity(1, 10, 0, "A"), GroupMemberEntity(1, 20, 1, "B"))
    private val user = GroupMessageEntity(1, 1, "user", "weather", 0)
    private val turn = GroupTurnEntity(1, 1, 1, "[10]", 0, "completed")
    private val record = GroupToolRecord("c", "get_weather", "{}", "returned", "sunny")
    private val reply = GroupMessageEntity(2, 1, "assistant", "sunny", 0, speakerId = 10, turnId = 1,
        toolTrace = GroupToolRules.encode(GroupToolTrace(listOf(record))))
    private fun validate(rows: List<GroupMessageEntity>) = GroupBackupRules.validate(groups, members, listOf(turn), rows)
    @Test fun oldMessageDefaultsEmptyAndTraceRoundtripRetainsOwnership() {
        val old = JsonObject(Json.parseToJsonElement(Json.encodeToString(reply)).jsonObject - "toolTrace")
        assertEquals("{}", Json.decodeFromString<GroupMessageEntity>(old.toString()).toolTrace)
        val restored = Json.decodeFromString<GroupMessageEntity>(Json.encodeToString(reply))
        validate(listOf(user, restored)); assertEquals(reply, restored)
        assertTrue(BackupFile.VERSION > 4)
    }
    @Test fun corruptedTraceAndToolFieldsOnUserMessagesAreRejectedBeforeReplacingData() {
        val bad = listOf("broken", GroupToolRules.encode(GroupToolTrace(listOf(record.copy(state = "made_up")))),
            GroupToolRules.encode(GroupToolTrace(listOf(record, record))),
            GroupToolRules.encode(GroupToolTrace(listOf(record.copy(name = "write_diary")))),
            GroupToolRules.encode(GroupToolTrace(listOf(record.copy(result = "x".repeat(6001))))))
        for (raw in bad) {
            try { validate(listOf(user, reply.copy(toolTrace = raw))); fail() } catch (_: IllegalArgumentException) { }
        }
        try { validate(listOf(user.copy(toolTrace = reply.toolTrace), reply)); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun unfinishedRestoredQueryKeepsRecordButNeverRunsAutomaticallyOrClaimsResult() {
        val running = reply.copy(status = "running", toolTrace = GroupToolRules.encode(GroupToolTrace(listOf(record.copy(state = "running", result = "")))))
        val restored = GroupBackupRules.restored(running)
        assertEquals("interrupted", restored.status); assertEquals(running.toolTrace, restored.toolTrace)
        assertEquals("", GroupToolRules.context(restored.toolTrace)); validate(listOf(user, restored))
    }
}
