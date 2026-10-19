package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GroupMessageDeletionTest {
    private val group = ChatGroupEntity(1, "测试群", 100, 200, draft = "保留草稿", mentions = "[11]", nextSpeaker = 3)
    private val members = listOf(GroupMemberEntity(1, 11, 0, "同名"), GroupMemberEntity(1, 22, 1, "同名"))
    private val user = GroupMessageEntity(1, 1, "user", "起始发言", 300)
    private val a = GroupMessageEntity(2, 1, "assistant", "第一位的原话", 301, speakerId = 11, turnId = 10)
    private val b = GroupMessageEntity(3, 1, "assistant", "第二位的原话", 301, speakerId = 22, turnId = 10)
    private val turn = GroupTurnEntity(10, 1, 1, "[11,22]", 301, "completed")
    private val note = GroupMemoryEntity(1, 1, "已整理", "内容需要保留", 302, 302, 2, "message")
    private fun plan(selected: GroupMessageEntity = a, whole: Boolean = false,
        turns: List<GroupTurnEntity> = listOf(turn), replies: List<GroupMessageEntity> = listOf(a,b),
        notes: List<GroupMemoryEntity> = listOf(note), g: ChatGroupEntity = group, epoch: Long = 7) =
        GroupDeletionRules.plan(g, epoch, selected, user, turns, replies, whole, notes)

    private fun validAfter(p: GroupMessageDeletion, messages: List<GroupMessageEntity> = listOf(user,a,b),
        turns: List<GroupTurnEntity> = listOf(turn), notes: List<GroupMemoryEntity> = listOf(note)) {
        val removed = p.removed.map { it.id }.toSet()
        val after = messages.filter { it.id !in removed }
        val afterTurns = turns.filter { it.id !in p.removeTurns }.map { old -> p.updateTurns.firstOrNull { it.id == old.id } ?: old }
        val afterNotes = notes.map { if (it.sourceMessageId in removed) it.copy(sourceMessageId = null) else it }
        // Exercise the actual backup validator and serializers, including retained sourceKind=message.
        GroupBackupRules.validate(listOf(group), members, afterTurns, after, afterNotes)
        assertEquals(after, Json.decodeFromString<List<GroupMessageEntity>>(Json.encodeToString(after)))
    }

    @Test fun deletingOneAssistantKeepsTheAnchorAndOtherSpeakerAndRepairsTargets() {
        val p = plan()
        assertEquals(listOf(2L),p.removed.map { it.id })
        assertTrue(p.removeTurns.isEmpty())
        assertEquals("[22]",p.updateTurns.single().targets)
        assertEquals("completed",p.updateTurns.single().status)
        assertEquals(listOf(note),p.notes)
        validAfter(p)
    }

    @Test fun wholeRoundFromEitherSpeakerHasTheSameThreeRecords() {
        for (selected in listOf(a,b)) {
            val p=plan(selected, true)
            assertEquals(listOf(1L,2,3),p.removed.map { it.id })
            assertEquals(listOf(10L),p.removeTurns)
            assertTrue(p.updateTurns.isEmpty())
            validAfter(p)
        }
    }

    @Test fun userAnchorDeletionAlwaysExplicitlyExpandsToTheRound() {
        val p=plan(user, false)
        assertTrue(p.wholeRound)
        assertEquals(3,p.removed.size)
        validAfter(p)
    }

    @Test fun removingTheLastReplyDropsTheTurnButKeepsUserWords() {
        val p=plan(turns=listOf(turn.copy(targets="[11]")), replies=listOf(a))
        assertEquals(listOf(a),p.removed)
        assertEquals(listOf(10L),p.removeTurns)
        validAfter(p,listOf(user,a),listOf(turn.copy(targets="[11]")))
    }

    @Test fun userMessageWithoutAnyRepliesCanBeRemovedOnItsOwn() {
        val p=plan(user,true,emptyList(),emptyList(),emptyList())
        assertFalse(p.wholeRound)
        assertEquals(listOf(user),p.removed)
        validAfter(p,listOf(user),emptyList(),emptyList())
    }

    @Test fun failedBlankPlaceholderCanBeDeletedWithoutLosingTheSuccessfulReply() {
        val failed=b.copy(content="",status="failed",error="合成失败")
        val p=plan(failed, turns=listOf(turn.copy(status="failed")), replies=listOf(a,failed))
        assertEquals("[11]",p.updateTurns.single().targets)
        assertEquals("completed",p.updateTurns.single().status)
        validAfter(p,listOf(user,a,failed),listOf(turn.copy(status="failed")))
    }

    @Test fun deletingSuccessDoesNotTurnRemainingFailureIntoSuccess() {
        val failed=b.copy(content="",status="interrupted")
        val p=plan(turns=listOf(turn.copy(status="interrupted")),replies=listOf(a,failed))
        assertEquals("interrupted",p.updateTurns.single().status)
        validAfter(p,listOf(user,a,failed),listOf(turn.copy(status="interrupted")))
    }

    @Test fun callMarkersAndAnUnfinishedReplyRemainValidAfterDeletingTheOtherSpeaker() {
        val speech=a.copy(content="[群通话]\n已播完。\n[群通话，这句未完整播完]\n被打断。",status="sent")
        val cancelled=b.copy(content="",status="cancelled")
        val p=plan(cancelled,turns=listOf(turn.copy(status="cancelled")),replies=listOf(speech,cancelled))
        assertEquals(listOf(cancelled),p.removed)
        validAfter(p,listOf(user,speech,cancelled),listOf(turn.copy(status="cancelled")))
    }

    @Test fun aSharedUserAnchorWithMultipleImportedTurnsLeavesNoOrphanedTurn() {
        val second=turn.copy(id=20,targets="[11]")
        val c=a.copy(id=4,turnId=20)
        val p=plan(b,true,listOf(turn,second),listOf(a,b,c))
        assertEquals(listOf(1L,2,3,4),p.removed.map { it.id })
        assertEquals(listOf(10L,20),p.removeTurns)
        validAfter(p,listOf(user,a,b,c),listOf(turn,second))
    }

    @Test fun aSingleReplyDoesNotRemoveOtherTurnsSharingItsAnchor() {
        val second=turn.copy(id=20,targets="[11]")
        val c=a.copy(id=4,turnId=20)
        val p=plan(c,false,listOf(turn,second),listOf(a,b,c))
        assertEquals(listOf(4L),p.removed.map { it.id })
        assertEquals(listOf(20L),p.removeTurns)
        validAfter(p,listOf(user,a,b,c),listOf(turn,second))
    }

    @Test fun contentOrNotesChangingAfterPreviewRequireAnotherConfirmation() {
        val before=plan()
        for (after in listOf(plan(a.copy(content="迟到内容"),replies=listOf(a.copy(content="迟到内容"),b)),
            plan(notes=listOf(note.copy(content="后来修改"))),plan(notes=emptyList()))) {
            try { GroupDeletionRules.requireCurrent(before,after);fail() } catch (_:IllegalStateException) {}
        }
    }

    @Test fun restoredEpochOrRecreatedGroupCannotReuseAnOldConfirmation() {
        for (after in listOf(plan(epoch=8),plan(g=group.copy(createdAt=999)))) {
            try { GroupDeletionRules.requireCurrent(plan(),after);fail() } catch (_:IllegalStateException) {}
        }
    }

    @Test fun unrelatedDraftEditsAreNotPartOfTheDeletionAndDoNotInvalidateItsScope() {
        GroupDeletionRules.requireCurrent(plan(),plan(g=group.copy(draft="新的草稿",nextSpeaker=8,updatedAt=999)))
    }

    @Test fun runningTurnOrPendingReplyCannotBeDeleted() {
        for (attempt in listOf<()->Unit>({plan(turns=listOf(turn.copy(status="running")))},
            {plan(replies=listOf(a,b.copy(status="pending")))})) {
            try { attempt();fail() } catch (_:IllegalArgumentException) {}
        }
    }

    @Test fun crossGroupSelectionRepliesAndNotesAreRejected() {
        for (attempt in listOf<()->Unit>({plan(a.copy(groupId=2))},
            {plan(replies=listOf(a,b.copy(groupId=2)))}, {plan(notes=listOf(note.copy(groupId=2)))})) {
            try { attempt();fail() } catch (_:IllegalArgumentException) {}
        }
    }

    @Test fun malformedTargetsOrMissingSelectedReplyCannotBroadenTheDeletion() {
        for (attempt in listOf<()->Unit>({plan(turns=listOf(turn.copy(targets="[]")))},
            {plan(turns=listOf(turn.copy(targets="[22,11]")))}, {plan(a.copy(id=99))})) {
            try { attempt();fail() } catch (_:IllegalArgumentException) {}
        }
    }

    @Test fun onlyNotesLinkedToRemovedRowsAreDetached() {
        val other=note.copy(id=2,sourceMessageId=b.id)
        val p=plan(notes=listOf(note,other))
        assertEquals(listOf(note),p.notes)
        validAfter(p,notes=listOf(note,other))
    }

    @Test fun mediaCandidatesExcludeAvatarsAndUnsafePaths() {
        val photo=MessageImage("photo.jpg",100,100)
        val row=a.copy(images=MessageImages.encode(listOf(photo)),speakerAvatar="kept-avatar.jpg")
        val p=plan(row,replies=listOf(row,b))
        assertEquals(setOf("photo.jpg"),p.files)
        val unsafe=row.copy(images=MessageImages.encode(listOf(photo.copy(file="../outside.jpg"))))
        assertTrue(plan(unsafe,replies=listOf(unsafe,b)).files.isEmpty())
    }
}
