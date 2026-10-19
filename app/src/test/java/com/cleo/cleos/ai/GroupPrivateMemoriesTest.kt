package com.cleo.cleos.ai

import com.cleo.cleos.data.MemoryDetails
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class GroupPrivateMemoriesTest {
    private val group = ChatGroupEntity(1, "合成测试群", 1, 1)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.parse("2026-10-19T18:00:00+08:00")
    private fun memory(id: Long = 1, owner: Long = 10, text: String = "SELECTED_OWNER_A") =
        MemoryEntity(id, owner, "rapport", "共同经历$id", text, MemoryDetails.encode(listOf("DETAIL_$text")), createdAt = 1, updatedAt = 2)
    private fun member(owner: Long = 10, memories: List<MemoryEntity> = listOf(memory())) =
        GroupMemberEntity(1, owner, 0, "同名", sharePrivateMemories = true,
            privateMemoryGrants = GroupPrivateMemories.encode(memories.map(GroupPrivateMemories::grant)))
    private fun context(member: GroupMemberEntity = member(), records: List<MemoryEntity> = listOf(memory()), groupId: Long = 1, owner: Long = 10) =
        GroupPrivateMemories.context(groupId, owner, listOf(member), records, "共同经历", zone)

    @Test fun newAndExistingMembersDefaultToNoDisclosureEvenWhenRecordsAreAvailable() {
        assertEquals("", context(GroupMemberEntity(1, 10, 0, "同名")))
        assertEquals("", context(member().copy(sharePrivateMemories = false)))
        assertEquals(emptyList<GroupMemoryGrant>(), GroupPrivateMemories.grantsFor(1, 10, listOf(member().copy(sharePrivateMemories = false))))
    }

    @Test fun onlyTheSelectedOwnersApprovedRevisionIsIncluded() {
        val chosen = memory(); val hidden = memory(2, text = "UNSELECTED_PRIVATE_CANARY")
        val other = memory(3, owner = 20, text = "OTHER_TA_CANARY")
        val words = context(records = listOf(chosen, hidden, other))
        assertTrue(words.contains("SELECTED_OWNER_A")); assertTrue(words.contains("DETAIL_SELECTED_OWNER_A"))
        assertFalse(words.contains("UNSELECTED_PRIVATE_CANARY")); assertFalse(words.contains("OTHER_TA_CANARY"))
        assertEquals("", context(member(memories = listOf(other)), records = listOf(chosen, other)))
    }

    @Test fun disabledMissingInactiveOrMalformedGrantsNeverQueryPrivateMemoryStorage() = runBlocking {
        assertTrue(GroupPrivateMemories.load(1, 10, listOf(member()), enabled = false) { _, _ -> error("memory capability disabled") }.isEmpty())
        for (roster in listOf(emptyList(), listOf(GroupMemberEntity(1, 10, 0, "同名")),
            listOf(member().copy(sharePrivateMemories = false)), listOf(member().copy(active = false)),
            listOf(member().copy(groupId = 2)), listOf(member().copy(privateMemoryGrants = "broken")))) {
            assertTrue(GroupPrivateMemories.load(1, 10, roster) { _, _ -> error("private memory read without permission") }.isEmpty())
        }
    }

    @Test fun storageLookupUsesOnlyCurrentOwnersSelectedIdsAndFiltersUnexpectedRows() = runBlocking {
        var reads = 0
        val rows = GroupPrivateMemories.load(1, 10, listOf(member())) { owner, ids ->
            reads++; assertEquals(10L, owner); assertEquals(listOf(1L), ids)
            listOf(memory(), memory(2, text = "UNSELECTED"), memory(3, 20, "OTHER_OWNER"))
        }
        assertEquals(1, reads); assertEquals(listOf(memory()), rows)
        assertTrue(GroupPrivateMemories.load(1, 10, listOf(member())) { _, _ -> listOf(memory().copy(summary = "EDITED")) }.isEmpty())
    }

    @Test fun grantsNeverCrossGroupsMembersOrInactiveMemberships() {
        for (invalid in listOf(member().copy(groupId = 2), member().copy(companionId = 20), member().copy(active = false)))
            assertEquals("", context(invalid))
        assertEquals("", context(groupId = 2)); assertEquals("", context(owner = 20))
        assertEquals("", GroupPrivateMemories.context(1, 10, listOf(member(), member()), listOf(memory()), "", zone))
        assertEquals("", context(records = listOf(memory(), memory())))
    }

    @Test fun editingDeletingOrReusingAnIdNeverSharesNewContentAutomatically() {
        val original = memory()
        for (edited in listOf(original.copy(summary = "NEW_PRIVATE_CANARY"), original.copy(details = "[\"NEW_PRIVATE_DETAIL\"]"),
            original.copy(name = "新主题"), original.copy(updatedAt = 3), original.copy(createdAt = 9),
            original.copy(kind = "recent"), original.copy(pinned = true), original.copy(source = "me"))) {
            assertEquals("", context(records = listOf(edited)))
        }
        assertEquals("", context(records = emptyList()))
        val new = memory(2, text = "NEW_TOPIC_CANARY")
        assertFalse(context(records = listOf(original, new)).contains("NEW_TOPIC_CANARY"))
        assertTrue(context(member(memories = listOf(new)), records = listOf(new)).contains("NEW_TOPIC_CANARY"))
    }

    @Test fun savingRejectsStaleMixedOwnerDuplicateAndMissingSelections() {
        val grant = GroupPrivateMemories.grant(memory())
        for (records in listOf(emptyList(), listOf(memory().copy(summary = "CHANGED")), listOf(memory(owner = 20)), listOf(memory(), memory()))) {
            assertThrows(IllegalArgumentException::class.java) { GroupPrivateMemories.checked(10, listOf(grant), records) }
        }
        assertThrows(IllegalArgumentException::class.java) { GroupPrivateMemories.checked(10, listOf(grant, grant), listOf(memory())) }
        assertEquals(member().privateMemoryGrants, GroupPrivateMemories.checked(10, listOf(grant), listOf(memory())))
    }

    @Test fun malformedPermissionsFailClosedAndCannotActAsSelectAll() {
        val grant = GroupPrivateMemories.grant(memory())
        val raw = member().privateMemoryGrants
        for (invalid in listOf("broken", "{}", "null", "[1]", "[" + raw.removeSurrounding("[", "]") + "," + raw.removeSurrounding("[", "]") + "]",
            raw.replace(grant.fingerprint, "a".repeat(63)), raw.replace("\"id\":1", "\"id\":0"),
            raw.replace("\"id\":1", "\"id\":1,\"extra\":true"), " ".repeat(4001))) {
            assertTrue(GroupPrivateMemories.decode(invalid).isEmpty())
            assertEquals("", context(member().copy(privateMemoryGrants = invalid)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupPrivateMemories.encode((1L..21L).map { GroupMemoryGrant(it, "a".repeat(64)) })
        }
    }

    @Test fun invalidOrOversizedMemoryCannotBeApproved() {
        for (bad in listOf(memory().copy(details = "broken"), memory().copy(details = "[123]"), memory().copy(name = "n".repeat(31)),
            memory().copy(summary = "s".repeat(121)), memory().copy(details = MemoryDetails.encode(listOf("d".repeat(301)))),
            memory().copy(details = MemoryDetails.encode(List(31) { "detail" })), memory().copy(updatedAt = -1))) {
            assertFalse(GroupPrivateMemories.shareable(bad))
            assertEquals("", context(member(memories = listOf(bad)), listOf(bad)))
        }
    }

    @Test fun boundedRetrievalKeepsWholeTopicsAndUsesRelevantApprovedContent() {
        val memories = (1L..20L).map { id -> memory(id, text = if (id == 20L) "橙子课表" else "旧话题").copy(
            name = if (id == 20L) "橙子课表" else "话题$id",
            details = MemoryDetails.encode(List(30) { "第${it}条" + "字".repeat(285) + "完整结束" })) }
        val words = GroupPrivateMemories.context(1, 10, listOf(member(memories = memories)), memories, "橙子课表", zone)
        assertTrue(words.length <= GroupPrivateMemories.MAX_CONTEXT)
        val entries = Json.parseToJsonElement(words.substringAfter('\n')).jsonArray
        assertEquals(1, entries.size)
        assertEquals("橙子课表", entries.single().jsonObject.getValue("主题").jsonPrimitive.content)
        val details = entries.single().jsonObject.getValue("细节").jsonArray
        assertEquals(30, details.size); assertTrue(details.last().jsonPrimitive.content.endsWith("完整结束"))
    }

    @Test fun leavingAndRejoiningClearsOwnGrantWhileUnchangedMembersKeepTheirs() {
        val a = member(); val b = member(20, listOf(memory(2, 20, "SELECTED_OWNER_B"))).copy(position = 1)
        val c = GroupMemberEntity(1, 30, 0, "新伙伴")
        val left = GroupMembershipRules.roster(listOf(a, b), listOf(20, 30), listOf(c))
        assertFalse(left.first().sharePrivateMemories); assertEquals("[]", left.first().privateMemoryGrants)
        assertEquals(b, left[1])
        val joined = GroupMembershipRules.roster(left, listOf(10, 20), emptyList()).first()
        assertFalse(joined.sharePrivateMemories); assertEquals("[]", joined.privateMemoryGrants)
    }

    @Test fun freshMembersCannotInheritPermissionsFromAnAddedSnapshot() {
        val a = member(); val b = member(20, listOf(memory(2, 20))).copy(position = 1)
        val c = member(30, listOf(memory(3, 30)))
        val added = GroupMembershipRules.roster(listOf(a, b), listOf(10, 20, 30), listOf(c)).last()
        assertFalse(added.sharePrivateMemories); assertEquals("[]", added.privateMemoryGrants)
    }

    @Test fun permissionSerializationNeverBacksUpOrRestoresTheGrant() {
        val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val raw = codec.encodeToString(GroupMemberEntity.serializer(), member())
        assertFalse(raw.contains("privateMemoryGrants")); assertFalse(raw.contains("sharePrivateMemories"))
        assertFalse(raw.contains("SELECTED_OWNER_A")); assertFalse(member().privateMemoryGrants.contains("SELECTED_OWNER_A"))
        val forged = raw.dropLast(1) + ",\"sharePrivateMemories\":true,\"privateMemoryGrants\":\"forged\"}"
        for (input in listOf(raw, forged)) {
            val restored = codec.decodeFromString(GroupMemberEntity.serializer(), input)
            assertFalse(restored.sharePrivateMemories); assertEquals("[]", restored.privateMemoryGrants)
        }
    }

    private suspend fun wire(owner: Long, call: Boolean, sharing: Boolean = true): JsonObject {
        val a = memory(); val b = memory(2, 20, "SELECTED_OWNER_B")
        val records = listOf(a, b, memory(3, text = "HIDDEN_OWNER_A"), memory(4, 20, "HIDDEN_OWNER_B"))
        val roster = listOf(member().copy(sharePrivateMemories = sharing), member(20, listOf(b)).copy(position = 1, sharePrivateMemories = sharing))
        val ta = CompanionEntity(id = owner, name = "同名", persona = "合成角色", createdAt = 1,
            apiBaseUrl = "https://synthetic.invalid/v1", apiModel = "fixture-model")
        val history = listOf(GroupMessageEntity(1, 1, "user", "聊聊共同经历", 1))
        val request = if (call) GroupCallRules.request(group, roster, listOf(10, 20), ta, history, "测试用户", "SHARED_GROUP_NOTE", now,
            privateMemories = records) else GroupChatRules.request(group, roster, ta, history, "测试用户", emptyList(), now,
            memoryContext = "SHARED_GROUP_NOTE", privateMemories = records)
        assertFalse(request.zipWithNext().any { it.first.role == "user" && it.second.role == "user" })
        var captured: JsonObject? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer(); requireNotNull(chain.request().body).writeTo(buffer)
            captured = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        try {
            ChatClient(http, diagnostic = {}).stream(ApiEndpoint("https://synthetic.invalid/v1", "fixture-key", "fixture-model"), request).toList()
            return requireNotNull(captured)
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }

    @Test fun chatAndCallWireRequestsContainOnlyThatSpeakersSelectedMemory() = runBlocking {
        for (call in listOf(false, true)) for (owner in listOf(10L, 20L)) {
            val body = wire(owner, call).toString()
            assertTrue(body.contains(if (owner == 10L) "SELECTED_OWNER_A" else "SELECTED_OWNER_B"))
            assertFalse(body.contains(if (owner == 10L) "SELECTED_OWNER_B" else "SELECTED_OWNER_A"))
            assertFalse(body.contains("HIDDEN_OWNER_A")); assertFalse(body.contains("HIDDEN_OWNER_B"))
            assertTrue(body.contains("SHARED_GROUP_NOTE"))
            val closed = wire(owner, call, sharing = false).toString()
            assertFalse(closed.contains("SELECTED_OWNER_A")); assertFalse(closed.contains("SELECTED_OWNER_B"))
        }
    }

    @Test fun revocationStopsRetrievalWithoutPretendingToRecallPublishedWords() {
        val history = listOf(GroupMessageEntity(1, 1, "assistant", "ALREADY_PUBLISHED", 1, speakerId = 10, speakerName = "同名"))
        val ta = CompanionEntity(id = 10, name = "同名", createdAt = 1, apiBaseUrl = "https://synthetic.invalid/v1", apiModel = "fixture-model")
        val request = GroupChatRules.request(group, listOf(member().copy(sharePrivateMemories = false)), ta, history,
            "用户", emptyList(), now, privateMemories = listOf(memory()))
        assertTrue(request.any { it.content.contains("ALREADY_PUBLISHED") })
        assertFalse(request.any { it.content.contains("SELECTED_OWNER_A") })
    }
}
