package com.cleo.cleos.data

import com.cleo.cleos.ai.*
import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GroupSpeechBackupTest {
    private val group=ChatGroupEntity(1,"fixture",0,0)
    private val members=listOf(GroupMemberEntity(1,10,0,"A"),GroupMemberEntity(1,20,1,"B"))
    private val user=GroupMessageEntity(1,1,"user","voice please",0)
    private val turn=GroupTurnEntity(1,1,1,"[10]",0,"completed")
    private val clip=GroupSpeechClip("voice_group_reply_1_10_fixture.mp3",2200,"spoken words")
    private val row=GroupMessageEntity(2,1,"assistant",GroupSpeechRules.merged(clip.text,"text afterward"),0,
        speakerId=10,turnId=1,audio=GroupSpeechRules.encode(clip))
    private fun validate(r:GroupMessageEntity=row) = GroupBackupRules.validate(listOf(group),members,listOf(turn),listOf(user,r))
    private fun rejects(r:GroupMessageEntity) { try{validate(r);fail()}catch(_:IllegalArgumentException){} }
    @Test fun sentAssistantVoiceRoundTripsBothSpokenAndSupplementalText() {
        validate();val parsed=Json.decodeFromString<GroupMessageEntity>(Json.encodeToString(row));assertEquals(row,parsed)
        assertEquals(clip,GroupSpeechRules.clip(parsed));assertEquals(15, BackupFile.VERSION)
        assertEquals(clip.audio(),MessageAudios.decode(row.audio));assertTrue(GroupMemoryRules.canRemember(row))
    }
    @Test fun mismatchedAuthorGroupContentAndPrivateAudioAreRejected() {
        rejects(row.copy(speakerId=20));rejects(row.copy(content="unrelated caption"))
        rejects(row.copy(audio=GroupSpeechRules.encode(clip.copy(file="voice_group_reply_2_10_other.mp3"))))
        rejects(row.copy(audio=MessageAudios.encode(MessageAudio("voice_private.mp3",2200))))
        rejects(row.copy(audio=GroupSpeechRules.encode(clip.copy(text=""))))
    }
    @Test fun userVoiceAndAssistantVoiceFormatsCannotBeSwapped() {
        val badUser=user.copy(audio=row.audio)
        try{GroupBackupRules.validate(listOf(group),members,listOf(turn),listOf(badUser,row));fail()}catch(_:IllegalArgumentException){}
        rejects(row.copy(audio=MessageAudios.encode(MessageAudio("voice_group_1_user.wav",1000))))
    }
    @Test fun missingAssistantAudioKeepsSpokenWordsAndAdditionalText() {
        val restored=GroupVoiceRules.restored(row){false};validate(restored)
        assertNull(restored.audio);assertEquals(row.content,restored.content);assertEquals("sent",restored.status)
        assertEquals(row,GroupVoiceRules.restored(row){true})
    }
    @Test fun interruptionDoesNotDiscardACommittedVoiceOrResumeGeneration() {
        val running=row.copy(status="running");val restored=GroupBackupRules.restored(running);validate(restored)
        assertEquals("interrupted",restored.status);assertEquals(row.audio,restored.audio);assertEquals(row.content,restored.content)
    }
    @Test fun voiceAndGalleryPictureCanCoexistForTheSameAssistant() {
        val photo=MessageImage("gallery.jpg",80,80,"Openverse",PhotoCredit("title","author","https://example.com/photo","CC BY","https://example.com/license"))
        validate(row.copy(images=MessageImages.encode(listOf(photo))))
    }
}
