package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

class GroupVoicesTest {
    private val audio = MessageAudio("voice_group_1_fixture.wav", 1000)
    @Test fun groupVoiceMetadataRejectsCrossGroupPrivatePathsAndOutOfRangeDurations() {
        assertTrue(GroupVoiceRules.validAudio(MessageAudios.encode(audio),1))
        assertFalse(GroupVoiceRules.validAudio(MessageAudios.encode(audio),2))
        for (a in listOf(audio.copy(file="voice_private.wav"),audio.copy(file="../voice_group_1_fixture.wav"),
            audio.copy(file="voice_group_1_fixture.mp3"),audio.copy(ms=799),audio.copy(ms=60001))) assertFalse(GroupVoiceRules.valid(a,1))
        assertFalse(GroupVoiceRules.validAudio("{}",1)); assertFalse(GroupVoiceRules.validAudio("",1))
    }
    @Test fun draftHasBoundedEditableTextAndSurvivesSerialization() {
        val draft = GroupVoiceDraft(audio.file,audio.ms,"用户改过的转写")
        assertEquals(draft,GroupVoiceRules.draft(GroupVoiceRules.encode(draft),1))
        assertFalse(GroupVoiceRules.validDraft(GroupVoiceRules.encode(draft.copy(text="x".repeat(8001))),1))
        assertFalse(GroupVoiceRules.validDraft(GroupVoiceRules.encode(draft.copy(error="x".repeat(221))),1))
        assertNull(GroupVoiceRules.draft(GroupVoiceRules.encode(draft),2))
    }
    @Test fun wavValidationRejectsFakeHeadersDurationMismatchAndTruncation() {
        val bytes=Voice.silence()
        assertTrue(GroupVoiceRules.validWav(audio,bytes.size.toLong(),bytes.copyOfRange(0,44)))
        assertFalse(GroupVoiceRules.validWav(audio,bytes.size.toLong()-2,bytes.copyOfRange(0,44)))
        assertFalse(GroupVoiceRules.validWav(audio.copy(ms=900),bytes.size.toLong(),bytes.copyOfRange(0,44)))
        for (index in listOf(0,8,12,16,20,22,24,28,32,34,36,40)) {
            val header=bytes.copyOfRange(0,44); header[index]=(header[index].toInt()+1).toByte()
            assertFalse("header $index",GroupVoiceRules.validWav(audio,bytes.size.toLong(),header))
        }
        assertFalse(GroupVoiceRules.validWav(audio,bytes.size.toLong(),ByteArray(43)))
    }
    @Test fun recordingLimitIsAcceptedAndLongerWavsRejected() {
        val max=Voice.silence(60000)
        assertTrue(GroupVoiceRules.validWav(audio.copy(ms=60000),max.size.toLong(),max.copyOfRange(0,44)))
        val long=Voice.silence(60001)
        assertFalse(GroupVoiceRules.validWav(audio.copy(ms=60001),long.size.toLong(),long.copyOfRange(0,44)))
    }
    @Test fun onlyOwnedCompleteFilesMayBeTranscribedOrSent() {
        val dir=kotlin.io.path.createTempDirectory("group-voice-test").toFile()
        try {
            val f=File(dir,audio.file); f.writeBytes(Voice.silence())
            GroupVoiceRules.requireFile(audio,1,f)
            val unrelated=File(dir,"voice_group_2_other.wav").apply { writeBytes(Voice.silence()) }
            for (action in listOf<()->Unit>({GroupVoiceRules.requireFile(audio,2,f)},
                {GroupVoiceRules.requireFile(audio,1,unrelated)},
                {f.writeBytes(ByteArray(12));GroupVoiceRules.requireFile(audio,1,f)},
                {f.delete();GroupVoiceRules.requireFile(audio,1,f)})) {
                try { action(); fail() } catch (_:IllegalArgumentException) {}
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun requestIncludesEditedTranscriptAndAuthorButNoRawSoundOrFilename() {
        val group=ChatGroupEntity(1,"Fixture",0,0)
        val roster=listOf(GroupMemberEntity(1,10,0,"A"),GroupMemberEntity(1,20,1,"B"))
        val ta=CompanionEntity(id=10,name="A",apiBaseUrl="https://example.com",apiModel="fixture",createdAt=0)
        val rows=listOf(GroupMessageEntity(1,1,"user","edited transcript",0,audio=MessageAudios.encode(audio)),
            GroupMessageEntity(2,2,"user","other group private audio text",0))
        val request=GroupChatRules.request(group,roster,ta,rows,"user",emptyList(),ZonedDateTime.now())
        val text=request.joinToString { it.content }
        assertTrue(text.contains("edited transcript"));assertTrue(text.contains("[user]"));assertTrue(text.contains("本次没有提供原始声音"))
        assertFalse(text.contains(audio.file)); assertFalse(text.contains("other group private")); assertTrue(request.all { it.images.isEmpty() })
    }
    @Test fun missingRecordingKeepsTranscriptAndHonestSentUserState() {
        val row=GroupMessageEntity(1,1,"user","actual edited transcript",0,audio=MessageAudios.encode(audio))
        val restored=GroupVoiceRules.restored(row){false}
        assertNull(restored.audio);assertEquals(row.content,restored.content);assertEquals("sent",restored.status)
        assertTrue(restored.error!!.contains("转写文字仍保留"));assertEquals(row,GroupVoiceRules.restored(row){true})
    }
    @Test fun transcriptionReturnsOnlyBoundedNonblankTrimmedText() = runBlocking {
        assertEquals("edited text",GroupVoiceTranscription.run { "  edited text\n" })
        for (result in listOf(" ","x".repeat(8001))) {
            try { GroupVoiceTranscription.run { result };fail() } catch (_:IllegalArgumentException) {}
        }
    }
    @Test fun transcriptionHasAnOverallDeadlineAndCancelsTheRequest() = runBlocking {
        var cancelled=false
        try { GroupVoiceTranscription.run(40) { try { delay(2000); "late" } finally { cancelled=true } };fail() }
        catch (_:IllegalStateException) {}
        assertTrue(cancelled)
    }
    @Test fun cancellingTranscriptionCannotCommitLateWords() = runBlocking {
        var committed=false
        val started=CompletableDeferred<Unit>()
        val job=launch { GroupVoiceTranscription.run { started.complete(Unit); delay(2000);"late" }; committed=true }
        started.await();job.cancelAndJoin();assertFalse(committed)
    }
    @Test fun errorHintsDoNotEchoProviderPayloadOrSecrets() {
        assertTrue(GroupVoiceRules.failure(401).contains("Key"))
        assertTrue(GroupVoiceRules.failure(429).contains("额度"))
        assertTrue(GroupVoiceRules.failure(500).contains("自己填写"))
    }
}
