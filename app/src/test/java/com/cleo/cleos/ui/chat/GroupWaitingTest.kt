package com.cleo.cleos.ui.chat

import com.cleo.cleos.ai.GroupReplyProgress
import com.cleo.cleos.ai.GroupReplyStage
import com.cleo.cleos.ai.GroupToolRecord
import com.cleo.cleos.ai.GroupToolRules
import com.cleo.cleos.ai.GroupToolTrace
import com.cleo.cleos.data.db.GroupMessageEntity
import org.junit.Assert.*
import org.junit.Test

class GroupWaitingTest {
    private fun user(id: Long = 1) = GroupMessageEntity(id, 1, "user", "大家好", 100, speakerName = "我")
    private fun member(id: Long, status: String = "pending", turn: Long = 9, content: String = "") =
        GroupMessageEntity(id, 1, "assistant", content, 100 + id, speakerId = id + 10, speakerName = "伙伴${id + 10}", turnId = turn, status = status)
    private fun items(vararg rows: GroupMessageEntity) = rows.map { GroupChatItem.Message(it) }
    private fun entries(list: List<GroupChatItem>, streaming: Set<Long> = emptySet()) =
        groupListEntries(list) { GroupWaiting.isWaiting(it, streaming) }

    @Test fun onlyAMemberWithNothingToShowIsWaiting() {
        assertTrue(GroupWaiting.isWaiting(member(2), emptySet()))
        assertTrue(GroupWaiting.isWaiting(member(2, "running"), emptySet()))
        // Words, even streamed ones, make it a real message.
        assertFalse(GroupWaiting.isWaiting(member(2, "running", content = "我来说"), emptySet()))
        assertFalse(GroupWaiting.isWaiting(member(2, "running"), setOf(2L)))
        // Real information is never hidden behind the strip.
        assertFalse(GroupWaiting.isWaiting(member(2, "running").copy(error = "接口超时"), emptySet()))
        assertFalse(GroupWaiting.isWaiting(member(2, "running").copy(toolTrace = """{"notice":"没有查到"}"""), emptySet()))
        assertFalse(GroupWaiting.isWaiting(member(2, "failed"), emptySet()))
        assertFalse(GroupWaiting.isWaiting(member(2, "cancelled"), emptySet()))
        assertFalse(GroupWaiting.isWaiting(user(), emptySet()))
    }

    @Test fun aRunOfWaitingMembersIsOneEntryAndKeepsItsOrderAndRows() {
        val source = items(user(), member(2, "running"), member(3), member(4))
        val result = entries(source)
        assertEquals(2, result.size)
        val strip = result[1] as GroupListEntry.Waiting
        assertEquals(listOf(2L, 3L, 4L), strip.rows.map { it.id })
        assertEquals("waiting:1:9:4", strip.key)
        assertEquals(source[0], (result[0] as GroupListEntry.Chat).item)
    }

    @Test fun theStripKeepsItsKeyWhileMembersStartWritingOneByOne() {
        val source = items(user(), member(2, "running"), member(3), member(4))
        val before = entries(source).last().key
        // 伙伴12 starts streaming: a real message appears, the strip keeps its key and holds the other two.
        val after = entries(source, streaming = setOf(2L))
        assertEquals(listOf("message:1", "message:2", before), after.map { it.key })
        assertEquals(listOf(3L, 4L), (after.last() as GroupListEntry.Waiting).rows.map { it.id })
        // Everyone has started: no strip is left.
        assertTrue(entries(source, streaming = setOf(2L, 3L, 4L)).all { it is GroupListEntry.Chat })
    }

    @Test fun differentTurnsAndSeparatedRunsAreNotMerged() {
        val two = entries(items(member(2, turn = 9), member(3, turn = 10)))
        assertEquals(listOf("waiting:1:9:2", "waiting:1:10:3"), two.map { it.key })
        val apart = entries(items(member(2), member(3, "running", content = "先说"), member(4)))
        assertEquals(listOf("waiting:1:9:2", "message:3", "waiting:1:9:4"), apart.map { it.key })
    }

    @Test fun everyEntryKeyIsUniqueAndNoRowIsLost() {
        val source = items(user(), member(2, "running"), member(3), user(5), member(6, turn = 10), member(7, turn = 10))
        val result = entries(source)
        assertEquals(result.size, result.map { it.key }.distinct().size)
        val rows = result.flatMap { when (it) {
            is GroupListEntry.Chat -> listOf((it.item as GroupChatItem.Message).message)
            is GroupListEntry.Waiting -> it.rows
        } }
        assertEquals(source.map { it.message }, rows)
    }

    @Test fun theStripNamesWhoIsAnsweringAndWhoIsInLine() {
        val running = GroupWaiting.split(listOf(member(2, "running"), member(3), member(4)))
        assertEquals(listOf(2L), running.active.map { it.id })
        assertEquals(listOf(3L, 4L), running.queued.map { it.id })
        val progress = mapOf(2L to GroupReplyProgress(0L, GroupReplyStage.THINKING))
        assertEquals("伙伴12 正在思考", GroupWaiting.label(running, progress))
        // No progress reported yet: say so, not a guess.
        assertEquals("伙伴12 正在连接", GroupWaiting.label(running, emptyMap()))
        // Nobody answering yet: the next in line is named, and the rest wait behind it.
        val idle = GroupWaiting.split(listOf(member(3), member(4)))
        assertTrue(idle.active.isEmpty())
        assertEquals(listOf(3L), idle.lead.map { it.id })
        assertEquals(listOf(4L), idle.queued.map { it.id })
        assertEquals("伙伴13 准备接话", GroupWaiting.label(idle, emptyMap()))
        // Several answering at once are all named.
        val both = GroupWaiting.split(listOf(member(2, "running"), member(3, "running")))
        assertEquals("伙伴12、伙伴13 正在回复", GroupWaiting.label(both, mapOf(2L to GroupReplyProgress(0L, GroupReplyStage.ANSWERING))))
    }

    @Test fun stageWordsAreRealAndCarryNoClock() {
        val words = GroupReplyStage.entries.map { GroupWaiting.stageText(it) } + GroupWaiting.stageText(null)
        assertEquals(words.size, words.distinct().size)
        assertTrue(words.none { w -> w.any { it.isDigit() } || "秒" in w })
        assertEquals("等待回应", GroupWaiting.stageText(GroupReplyStage.WAITING))
        assertEquals("正在处理", GroupWaiting.stageText(GroupReplyStage.TOOLS))
    }

    @Test fun removingAnEarlierWaitingRunDoesNotChangeTheLaterRunKey() {
        val source = items(member(2), member(3, "running", content = "已经在说"), member(4))
        val before = entries(source).last().key
        val after = entries(source, streaming = setOf(2L)).last().key
        assertEquals(before, after)
    }

    @Test fun unrelatedGroupsAndMissingTurnMetadataNeverMergeWaitingSlots() {
        val unknown = items(member(2).copy(turnId = null), member(3).copy(turnId = null))
        val differentGroups = items(member(2), member(3).copy(groupId = 2))
        for (source in listOf(unknown, differentGroups)) {
            val result = entries(source)
            assertEquals(2, result.size)
            assertEquals(2, result.map { it.key }.distinct().size)
        }
    }

    @Test fun theStripDoesNotReplacePhotosVoiceOrToolRecords() {
        val photo = member(2).copy(images = """[{"file":"synthetic.jpg","width":100,"height":100}]""")
        val voice = member(3).copy(audio = """{"file":"synthetic.wav","ms":1000}""")
        val tool = member(4).copy(toolTrace = GroupToolRules.encode(GroupToolTrace(listOf(
            GroupToolRecord("fixture", "get_weather", "{}", state = "returned", result = "done", note = "已读取")))))
        for (row in listOf(photo, voice, tool)) {
            assertFalse(GroupWaiting.isWaiting(row, emptySet()))
            assertTrue(entries(items(row)).single() is GroupListEntry.Chat)
        }
    }

    @Test fun callCardsKeepTheirOwnIdentityAndOriginalMessageIds() {
        val anchor = user().copy(content = "[群通话转写]\n合成转写")
        val reply = member(2, "sent", content = "[群通话]\n合成回复")
        val card = GroupChatItem.CallRound(anchor, listOf(reply))
        val source = listOf(card, GroupChatItem.Message(member(3)))
        val result = entries(source)
        assertSame(card, (result.first() as GroupListEntry.Chat).item)
        assertEquals("call:1", result.first().key)
        assertEquals(listOf(1L, 2L), card.messages.map { it.id })
        assertTrue(result.last() is GroupListEntry.Waiting)
    }
}
