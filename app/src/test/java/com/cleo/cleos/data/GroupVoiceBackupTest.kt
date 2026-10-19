package com.cleo.cleos.data

import com.cleo.cleos.ai.*
import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GroupVoiceBackupTest {
    private val group=ChatGroupEntity(1,"fixture",0,0)
    private val members=listOf(GroupMemberEntity(1,10,0,"A"),GroupMemberEntity(1,20,1,"B"))
    private val voice=GroupVoiceDraft("voice_group_1_fixture.wav",1000,"edited transcript")
    private val user=GroupMessageEntity(1,1,"user",voice.text,0,audio=MessageAudios.encode(voice.audio()))
    private fun validate(g:ChatGroupEntity=group,row:GroupMessageEntity=user) = GroupBackupRules.validate(listOf(g),members,emptyList(),listOf(row))
    private fun rejected(g:ChatGroupEntity=group,row:GroupMessageEntity=user) {
        try { validate(g,row);fail() } catch(_:IllegalArgumentException) {}
    }
    @Test fun oldGroupAndMessageBackupsDefaultToNoAudio() {
        val g=JsonObject(Json.parseToJsonElement(Json.encodeToString(group)).jsonObject-"draftAudio")
        val row=JsonObject(Json.parseToJsonElement(Json.encodeToString(user.copy(audio=null))).jsonObject-"audio")
        assertNull(Json.decodeFromString<ChatGroupEntity>(g.toString()).draftAudio)
        assertNull(Json.decodeFromString<GroupMessageEntity>(row.toString()).audio)
        assertEquals(15, BackupFile.VERSION)
    }
    @Test fun draftAndSentAudioRoundTripWithEditedWordsAndErrors() {
        val g=group.copy(draftAudio=GroupVoiceRules.encode(voice.copy(file="voice_group_1_draft.wav",text="",error="retry")))
        validate(g)
        assertEquals(g,Json.decodeFromString<ChatGroupEntity>(Json.encodeToString(g)))
        assertEquals(user,Json.decodeFromString<GroupMessageEntity>(Json.encodeToString(user)))
    }
    @Test fun draftAudioCannotBeOwnedBySentMessageOrAnotherGroup() {
        rejected(group.copy(draftAudio=GroupVoiceRules.encode(voice)))
        rejected(group.copy(draftAudio=GroupVoiceRules.encode(voice.copy(file="voice_group_2_other.wav"))))
        rejected(row=user.copy(audio=MessageAudios.encode(voice.audio().copy(file="voice_private.wav"))))
    }
    @Test fun audioCannotBeMixedWithPicturesOrUnrelatedDraftText() {
        val pending=GroupVoiceRules.encode(voice.copy(file="voice_group_1_draft.wav"))
        rejected(group.copy(draftAudio=pending,draft="unrelated"))
        val pictures=MessageImages.encode(listOf(MessageImage("group-user-1-picture.jpg",100,100,GroupUploadRules.SOURCE)))
        rejected(group.copy(draftAudio=pending,draftImages=pictures))
        rejected(row=user.copy(images=pictures));rejected(row=user.copy(content=""))
    }
    @Test fun restoredMissingVoiceRetainsWordsAndStillValidates() {
        val restored=GroupVoiceRules.restored(user){false};validate(row=restored)
        assertNull(restored.audio);assertEquals("edited transcript",restored.content);assertEquals("sent",restored.status)
    }
    @Test fun missingDraftRecordingSummaryExplainsWhatToDo() {
        assertTrue(BackupSummary(2,0,0,0,0,0,0,missingGroupDraftVoices=1).toString().contains("1 段群语音草稿文件缺失"))
    }
    @Test fun missingDraftRecordingPreservesEditedWordsAsOrdinaryTextDraft() {
        val g=group.copy(draftAudio=GroupVoiceRules.encode(voice.copy(file="voice_group_1_draft.wav")))
        val restored=GroupVoiceRules.restoredDraft(g){false}
        assertNull(restored.draftAudio);assertEquals(voice.text,restored.draft)
        validate(restored);assertEquals(g,GroupVoiceRules.restoredDraft(g){true})
        val pending=g.copy(draftAudio=GroupVoiceRules.encode(voice.copy(file="voice_group_1_draft.wav",text="")))
        assertEquals("",GroupVoiceRules.restoredDraft(pending){false}.draft)
    }
}
