package com.cleo.cleos.ai

import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class GroupMembershipTest {
    private val a = GroupMemberEntity(1, 10, 0, "同名", "message-a.jpg")
    private val b = GroupMemberEntity(1, 20, 1, "同名", "message-b.jpg")
    private val c = GroupMemberEntity(1, 30, 0, "新伙伴", "message-c.jpg")

    @Test fun onlyTwoToFourDistinctExistingMembersCanBeSaved() {
        val available = setOf(10L, 20L, 30L, 40L, 50L)
        assertEquals(listOf(20L, 10L), GroupMembershipRules.selected(listOf(20, 10), available))
        for (bad in listOf(emptyList(), listOf(10L), listOf(10L, 10L), listOf(10L, 99L), listOf(0L, 10L), listOf(10L, 20L, 30L, 40L, 50L))) {
            try { GroupMembershipRules.selected(bad, available); fail("accepted $bad") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun removingAndAddingKeepsTheFormerMemberAndOwnedAvatar() {
        val roster = GroupMembershipRules.roster(listOf(a, b), listOf(20, 30), listOf(c))
        assertEquals(listOf(10L, 20L, 30L), roster.map { it.companionId })
        assertFalse(roster.first().active)
        assertEquals(a.copy(active = false), roster.first())
        assertEquals(listOf(0, 1, 2), roster.map { it.position })
        assertEquals(listOf(20L, 30L), roster.filter { it.active }.map { it.companionId })
    }

    @Test fun rejoiningReusesTheOriginalIdentityAndDoesNotDuplicateItsAvatar() {
        val before = GroupMembershipRules.roster(listOf(a, b), listOf(20, 30), listOf(c))
        val after = GroupMembershipRules.roster(before, listOf(10, 30), emptyList())
        assertEquals(3, after.size)
        assertEquals(a, after.first())
        assertFalse(after[1].active)
        assertEquals("message-b.jpg", after[1].avatar)
    }

    @Test fun manyFormerMembersDoNotConsumeTheFourActivePlaces() {
        val old = (1L..8L).mapIndexed { index, id -> GroupMemberEntity(1, id, index, "旧伙伴$id", active = false) }
        val roster = GroupMembershipRules.roster(old, listOf(10, 20), listOf(a, b))
        assertEquals(10, roster.size)
        assertEquals(2, roster.count { it.active })
        assertEquals((0..9).toList(), roster.map { it.position })
    }

    @Test fun staleDraftMentionsCannotBringFormerOrDeletedMembersBack() {
        val roster = listOf(a.copy(active = false), b, c.copy(position = 2))
        assertEquals(listOf(20L), GroupMembershipRules.mentions(listOf(10, 20, 30, 20, 99), roster, setOf(10, 20)))
        assertTrue(GroupMembershipRules.mentions(listOf(10, 20), roster, emptySet()).isEmpty())
    }

    @Test fun ordinaryRotationAndExplicitMentionsExcludeFormerMembers() {
        val roster = GroupMembershipRules.roster(listOf(a, b), listOf(20, 30), listOf(c))
        val active = roster.filter { it.active }.map { it.companionId }
        assertEquals(listOf(20L, 30L), GroupChatRules.select(active, emptyList(), 0).targets)
        try { GroupChatRules.select(active, listOf(10), 0); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun newMemberReadsSharedHistoryWithOriginalAuthorsButFormerMemberIsNotPresent() {
        val roster = GroupMembershipRules.roster(listOf(a, b), listOf(20, 30), listOf(c))
        val ta = CompanionEntity(id = 30, name = "新伙伴", persona = "喜欢花", apiBaseUrl = "", apiModel = "", createdAt = 0)
        val history = listOf(
            GroupMessageEntity(1, 1, "user", "大家好", 0),
            GroupMessageEntity(2, 1, "assistant", "我是原来的十号成员", 0, speakerId = 10, speakerName = "同名"),
            GroupMessageEntity(3, 1, "user", "欢迎新伙伴", 0),
        )
        val request = GroupChatRules.request(ChatGroupEntity(1, "共同聊天", 0, 0), roster, ta, history, "用户", emptyList(), ZonedDateTime.now())
        val system = request.first().content
        assertTrue(system.contains("群成员：同名（成员 20）、新伙伴（成员 30）"))
        assertTrue(system.contains("已离群的历史作者：同名（成员 10）"))
        assertTrue(request.any { it.role == "user" && "成员 10" in it.content && "我是原来的十号成员" in it.content })
        assertFalse(request.any { it.role == "assistant" })
        assertTrue(request.all { it.images.isEmpty() && it.toolCalls.isEmpty() })
    }

    @Test fun aNewMemberSnapshotMustMatchTheSelectedNewIdsExactly() {
        for (added in listOf(emptyList(), listOf(c, c), listOf(c.copy(companionId = 40)))) {
            try { GroupMembershipRules.roster(listOf(a, b), listOf(20, 30), added); fail() } catch (_: IllegalArgumentException) { }
        }
    }
}
