package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class GroupSpeechTest {
    private val text = "short synthetic voice"
    private val audio = MessageAudio("voice_group_reply_1_10_fixture.wav", 1200)
    private val clip = GroupSpeechClip(audio.file, audio.ms, text)
    private val wav = ByteArray(44).also { "RIFF".toByteArray().copyInto(it); "WAVE".toByteArray().copyInto(it,8) }
    private val settings = AppSettings(speechEngine="openai",tools=setOf(ToolGroup.Speak))

    @Test fun voiceRequiresBothConfiguredServiceAndEnabledSpeakTool() {
        assertEquals(listOf("send_voice"),GroupToolRules.offered(settings).map { it.name })
        assertFalse(GroupToolRules.offered(settings.copy(speechEngine="")).any { it.name=="send_voice" })
        assertFalse(GroupToolRules.offered(settings.copy(tools=emptySet())).any { it.name=="send_voice" })
        assertFalse(GroupToolRules.offered(settings.copy(speechEngine="api",speechBaseUrl="")).any { it.name=="send_voice" })
    }
    @Test fun voiceOwnershipIncludesGroupSpeakerAndActuallySpokenWords() {
        assertTrue(GroupSpeechRules.valid(clip,1,10));assertFalse(GroupSpeechRules.valid(clip,2,10));assertFalse(GroupSpeechRules.valid(clip,1,20))
        assertFalse(GroupSpeechRules.valid(clip.copy(file="voice_private.mp3"),1,10))
        assertFalse(GroupSpeechRules.valid(clip.copy(file="../"+clip.file),1,10))
        assertFalse(GroupSpeechRules.valid(clip.copy(ms=0),1,10));assertFalse(GroupSpeechRules.valid(clip.copy(ms=180001),1,10))
        assertEquals(clip,GroupSpeechRules.decode(GroupSpeechRules.encode(clip),1,10))
        assertFalse(GroupVoiceRules.valid(clip.audio(),1))
    }
    @Test fun emptyOrOversizedVoiceNeverReachesTheProvider() = runBlocking {
        for (s in listOf(" ","a".repeat(301))) {
            try { GroupSpeechDelivery.deliver(s,1,10,{fail("provider must not be called");wav to "audio/wav"},{_,_,_->audio},{_,_->fail()},{fail()});fail() }
            catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun captionsCannotReplaceTheActualWordsAndOnlyExactDuplicateTextIsCollapsed() {
        val row=GroupMessageEntity(2,1,"assistant",text,0,speakerId=10,audio=GroupSpeechRules.encode(clip))
        assertTrue(GroupSpeechRules.validRow(row));assertFalse(GroupSpeechRules.validRow(row.copy(content="invented")))
        assertEquals(text,GroupSpeechRules.merged(text,text));assertEquals("supplement",GroupSpeechRules.remaining(GroupSpeechRules.merged(text,"supplement"),text))
        assertEquals("",GroupSpeechRules.remaining(text,text))
    }
    @Test fun unrelatedSettingsDoNotInvalidateVoiceButVoiceAndSwitchChangesDo() {
        assertTrue(GroupSpeechRules.sameVoice(settings,settings.copy(userName="other")))
        assertFalse(GroupSpeechRules.sameVoice(settings,settings.copy(tools=emptySet())))
        assertFalse(GroupSpeechRules.sameVoice(settings,settings.copy(speechEngine="siliconflow")))
        assertFalse(GroupSpeechRules.sameVoice(settings,settings.copy(speechVoices=mapOf("openai" to "nova"))))
    }
    @Test fun htmlJsonAndWrongContainerAreNotPublishedAsAudio() {
        assertTrue(GroupSpeechRules.sound(wav,"audio/wav"))
        assertFalse(GroupSpeechRules.sound("<html>server error</html>".toByteArray(),"audio/mpeg"))
        assertFalse(GroupSpeechRules.sound("{\"error\":\"private\"}".toByteArray(),"audio/wav"))
        assertFalse(GroupSpeechRules.sound(wav,"audio/ogg"));assertFalse(GroupSpeechRules.sound(byteArrayOf(),"audio/wav"))
    }
    @Test fun successfulDeliveryConfirmsOnlyAfterPersistedClip() = runBlocking {
        var stored:GroupSpeechClip?=null
        val outcome=GroupSpeechDelivery.deliver(text,1,10,{wav to "audio/wav"},{_,_,_->audio},{w,c->assertEquals(text,w);stored=c},{fail()})
        assertEquals(clip,stored);assertTrue(outcome.note.contains("已保存"));assertNull(outcome.photo);assertNull(outcome.imageResult)
    }
    @Test fun providerFailureRetainsOriginalWordsWithoutEchoingSecrets() = runBlocking {
        var words="";var saved=false
        val result=GroupSpeechDelivery.deliver(text,1,10,{error("secret-fixture-token")},{_,_,_->saved=true;audio},{w,c->words=w;assertNull(c)},{fail()})
        assertEquals(text,words);assertFalse(saved);assertFalse(result.result.contains("secret-fixture-token"));assertTrue(result.result.contains("没有发送语音"))
    }
    @Test fun diagnosticsRetainControlledFailureCategoryWithoutProviderProse() = runBlocking {
        val failures=mutableListOf<Pair<String,String>>()
        GroupSpeechDelivery.deliver(text,1,10,{throw SpeechException("secret-fixture-prose",category="http_503")},
            {_,_,_->audio},{_,_->},{},onFailure={phase,type->failures+=phase to type})
        assertTrue("request" to "http_503" in failures);assertFalse(failures.toString().contains("secret-fixture-prose"))
    }
    @Test fun synthesisDeadlineCancelsTheRequestAndKeepsWordsAsText() = runBlocking {
        var cancelled=false;var words=""
        GroupSpeechDelivery.deliver(text,1,10,{try{awaitCancellation()}finally{cancelled=true}},{_,_,_->audio},{w,c->words=w;assertNull(c)},{fail()},40)
        assertTrue(cancelled);assertEquals(text,words)
    }
    @Test fun cancellationDuringProviderRequestDoesNotPublishFallback() = runBlocking {
        val entered=CompletableDeferred<Unit>();var committed=false
        val task=launch { GroupSpeechDelivery.deliver(text,1,10,{entered.complete(Unit);awaitCancellation()},{_,_,_->audio},{_,_->committed=true},{fail()}) }
        entered.await();task.cancelAndJoin();assertFalse(committed)
    }
    @Test fun cancellationDuringSaveRemovesTheUnpublishedClip() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val removed=mutableListOf<String>();var committed=false
        val task=launch { GroupSpeechDelivery.deliver(text,1,10,{wav to "audio/wav"},{_,_,_->entered.complete(Unit);release.await();audio},{_,_->committed=true},{removed+=it}) }
        entered.await();task.cancel();release.complete(Unit);task.join()
        assertFalse(committed);assertEquals(listOf(audio.file),removed)
    }
    @Test fun cancellationAfterCommitBeginsKeepsTheCommittedClip() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var committed=false;val removed=mutableListOf<String>()
        val task=launch { GroupSpeechDelivery.deliver(text,1,10,{wav to "audio/wav"},{_,_,_->audio},{_,_->entered.complete(Unit);release.await();committed=true},{removed+=it}) }
        entered.await();task.cancel();release.complete(Unit);task.join();assertTrue(committed);assertTrue(removed.isEmpty())
    }
    @Test fun staleCommitFailureCleansAudioWithoutConfirmingOrPublishingFallback() = runBlocking {
        val removed=mutableListOf<String>();var calls=0
        try { GroupSpeechDelivery.deliver(text,1,10,{wav to "audio/wav"},{_,_,_->audio},{_,_->calls++;error("group ended")},{removed+=it});fail() }
        catch(_:IllegalStateException) {}
        assertEquals(1,calls);assertEquals(listOf(audio.file),removed)
    }
    @Test fun saveFailureUsesTextFallbackButNeverReportsAudioSuccess() = runBlocking {
        var kept=""
        val result=GroupSpeechDelivery.deliver(text,1,10,{wav to "audio/wav"},{_,_,_->error("disk fixture")},{w,c->kept=w;assertNull(c)},{fail()})
        assertEquals(text,kept);assertTrue(result.result.contains("没有发送语音"))
    }
    @Test fun committedSpeechAllowsAReplyWithNoFinalTextAndFollowupReceivesRealToolResult() = runBlocking {
        var requests=0;var committed=false
        val result=GroupToolReply.reply(listOf(ApiMessage("system","fixture"),ApiMessage("user","voice")),listOf(ToolSpecs.sendVoice),false,
            {messages,_,_->requests++;if(requests==1)flowOf(ChatEvent.ToolCalls(listOf(ToolCall("voice1","send_voice","""{"text":"$text"}"""))))
                else {assertTrue(messages.any { it.role=="tool" && it.content=="saved voice" });emptyFlow()}},
            {committed=true;ToolOutcome("saved voice","saved")},hasSpeech={committed}){}
        assertEquals("",result);assertEquals(2,requests)
    }
    @Test fun nextSpeakerReadsOnlyPublishedVoiceWordsWithCorrectAuthor() {
        val row=GroupMessageEntity(2,1,"assistant",GroupSpeechRules.merged(text,"unfinished-secret"),0,speakerId=10,speakerName="A",
            status="cancelled",audio=GroupSpeechRules.encode(clip))
        val history=GroupChatRules.request(ChatGroupEntity(1,"fixture",0,0),listOf(GroupMemberEntity(1,10,0,"A"),GroupMemberEntity(1,20,1,"B")),
            CompanionEntity(id=20,name="B",apiBaseUrl="https://example.com",apiModel="fixture",createdAt=0),
            listOf(row,row.copy(groupId=2,content="other-group")),"user",emptyList(),ZonedDateTime.now())
        val body=history.drop(1).joinToString { it.content }
        assertTrue(body.contains(text));assertTrue(body.contains("成员 10"));assertTrue(body.contains("没有提供原始声音"))
        assertFalse(body.contains("unfinished-secret"));assertFalse(body.contains("other-group"));assertFalse(body.contains(audio.file))
    }
}
