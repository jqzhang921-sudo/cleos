package com.cleo.cleos.ui.chat

import com.cleo.cleos.ai.GroupCallRules
import com.cleo.cleos.data.db.GroupMessageEntity
import com.cleo.cleos.data.db.GroupTurnEntity
import org.junit.Assert.*
import org.junit.Test

class GroupChatItemsTest {
    private fun user(id: Long = 1, group: Long = 1) = GroupMessageEntity(id, group, "user", GroupCallRules.userWords("你好"), 100,
        speakerName = "我", mentions = "[11,12]")
    private fun reply(id: Long, turn: Long = 9, speaker: Long = 11) = GroupMessageEntity(id, 1, "assistant",
        GroupCallRules.transcript("听见啦", ""), 100, speakerId = speaker, speakerName = "伙伴$speaker", turnId = turn)
    private fun turn(id: Long = 9, anchor: Long = 1, targets: String = "[11,12]") = GroupTurnEntity(id, 1, anchor, targets, 100, "completed")
    private val source get() = listOf(user(), reply(2), reply(3, speaker = 12))
    private fun flatten(items: List<GroupChatItem>) = items.flatMap { when (it) {
        is GroupChatItem.Message -> listOf(it.message)
        is GroupChatItem.CallRound -> it.messages
    } }

    @Test fun existingCallFoldsWithoutChangingStoredRowsOrTheirActionIds() {
        val messages = source
        val before = messages.toList()
        val card = groupChatItems(messages, listOf(turn())).single() as GroupChatItem.CallRound
        assertEquals(listOf(1L, 2L, 3L), card.messages.map { it.id })
        assertEquals(messages, card.messages)
        assertEquals(before, messages)
        assertFalse(card.ongoing)
        assertFalse(card.incomplete)
    }

    @Test fun adjacentRoundsWithIdenticalTimesRemainSeparateCardsBetweenOrdinaryMessages() {
        val normal = user(4).copy(content = "普通文字")
        val second = listOf(user(5), reply(6, turn = 10, speaker = 12), reply(7, turn = 10))
        val all = listOf(normal.copy(id = 0)) + source + second + normal.copy(id = 8)
        val items = groupChatItems(all, listOf(turn(), turn(10, 5, "[12,11]")))
        assertEquals(listOf(1L, 5L), items.filterIsInstance<GroupChatItem.CallRound>().map { it.anchor.id })
        assertEquals(all, flatten(items))
        assertEquals(items.size, items.map { it.key }.distinct().size)
    }

    @Test fun pendingAndCancelledSlotsRemainVisibleAlongsideActualPlayedText() {
        val unfinished = source[1].copy(content = GroupCallRules.transcript("完整一句", "只播了一半"), error = "被打断")
        val cancelled = source[2].copy(content = "", status = "cancelled", error = "通话这轮已停止")
        val rows = listOf(user(), unfinished, cancelled)
        val card = groupChatItems(rows, listOf(turn())).single() as GroupChatItem.CallRound
        assertTrue(card.incomplete)
        assertFalse(card.ongoing)
        assertEquals(GroupCallWords("完整一句", "只播了一半"), groupCallWords(card.replies.first().content))
        val running = groupChatItems(listOf(user(), source[1].copy(content = "", status = "running"),
            source[2].copy(content = "", status = "pending")), listOf(turn().copy(status = "running"))).single() as GroupChatItem.CallRound
        assertTrue(running.ongoing)
        assertEquals(3, running.messages.size)
    }

    @Test fun deletedReplyChangesCardContentsAndDeletingLastReplyKeepsTranscription() {
        val reduced = listOf(user(), source[2])
        val card = groupChatItems(reduced, listOf(turn(targets = "[12]"))).single() as GroupChatItem.CallRound
        assertEquals(listOf(1L, 3L), card.messages.map { it.id })
        val anchorOnly = groupChatItems(listOf(user()), emptyList()).single() as GroupChatItem.CallRound
        assertEquals(listOf(user()), anchorOnly.messages)
        assertEquals(emptyList<GroupChatItem>(), groupChatItems(emptyList(), emptyList()))
    }

    @Test fun metadataArrivingOutOfOrderCannotHideMessages() {
        val rows = source
        val noTurns = groupChatItems(rows, emptyList())
        assertTrue(noTurns.all { it is GroupChatItem.Message })
        assertEquals(rows, flatten(noTurns))
        val missingRow = listOf(rows[0], rows[1])
        assertEquals(missingRow, flatten(groupChatItems(missingRow, listOf(turn()))))
        assertTrue(groupChatItems(missingRow, listOf(turn())).none { it is GroupChatItem.CallRound })
    }

    @Test fun crossGroupAndMismatchedTargetsNeverFoldTogether() {
        val wrongGroup = listOf(user(), source[1].copy(groupId = 2), source[2])
        val wrongTargets = listOf(turn(targets = "[12,11]"))
        for ((rows, turns) in listOf(wrongGroup to listOf(turn()), source to wrongTargets,
            source to listOf(turn().copy(groupId = 2)), source to listOf(turn(targets = "[11,11]")))) {
            val items = groupChatItems(rows, turns)
            assertTrue(items.none { it is GroupChatItem.CallRound })
            assertEquals(rows, flatten(items))
        }
    }

    @Test fun photosAudioAndUnexpectedToolDataStayInNormalBubbles() {
        for (changed in listOf(source[1].copy(images = "[]"), source[1].copy(audio = "{}"),
            source[1].copy(toolTrace = "{\"notice\":\"important\"}"), source[1].copy(content = "普通回复"))) {
            val rows = listOf(user(), changed, source[2])
            val items = groupChatItems(rows, listOf(turn()))
            assertTrue(items.none { it is GroupChatItem.CallRound })
            assertEquals(rows, flatten(items))
        }
    }

    @Test fun interleavedUserTextIsNeverConsumedByAnEarlierCall() {
        val rows = listOf(user(), source[1], user(4).copy(content = "新话题"), source[2])
        val items = groupChatItems(rows, listOf(turn()))
        assertEquals(rows, flatten(items))
        assertTrue(items.none { it is GroupChatItem.CallRound })
    }

    @Test fun multipleImportedTurnsUseOnlyTheirExactAnchorAndKeepTheirOriginalOrder() {
        val rows = source + listOf(reply(4, turn = 10, speaker = 13))
        val items = groupChatItems(rows, listOf(turn(), turn(10, targets = "[13]")))
        assertEquals(rows, (items.single() as GroupChatItem.CallRound).messages)
    }

    @Test fun markersInsideOrdinaryWordsAndQuotedLabelsAreNotGloballyReplaced() {
        assertNull(groupCallWords("他说[群通话]\n例子"))
        val quoted = "说到 [群通话] 这个标签"
        assertEquals(GroupCallWords(quoted), groupCallWords(GroupCallRules.transcript(quoted, "")))
        assertEquals(GroupCallWords("", "半句话"), groupCallWords(GroupCallRules.transcript("", "半句话")))
        val ordinary = source.map { if (it.role == "user") it.copy(content = "这里有 [群通话转写]\n例子") else it }
        assertTrue(groupChatItems(ordinary, listOf(turn())).all { it is GroupChatItem.Message })
    }
}
