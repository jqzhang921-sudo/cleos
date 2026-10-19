package com.cleo.cleos.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class GroupCallTraceTest {
    private val records = mutableListOf<String>()
    private var now = 0L
    private fun trace() = GroupCallTrace({ records += it }, { now }, "abc00001")
    private fun advance(ms: Long) { now += ms * 1_000_000 }

    @Test fun firstTextUsesItsOwnClockAndDoesNotConfuseReasoningWithBody() = runBlocking {
        val t=trace().member(1)
        val expected=listOf(ChatEvent.Reasoning("private-reasoning"),ChatEvent.Delta(" "),ChatEvent.Delta("private-body"),ChatEvent.Delta("second-part"))
        val result=t.model { flow { advance(20);emit(expected[0]);advance(10);emit(expected[1]);advance(50);emit(expected[2]);advance(30);emit(expected[3]) } }.toList()
        assertEquals(expected,result)
        val first=records.single { "stage=first_text " in it }
        assertTrue(first.contains("member=1"));assertTrue(first.contains("elapsedMs=80"))
        assertTrue(records.any { "stage=model result=ok elapsedMs=110" in it })
        for(secret in listOf("private-reasoning","private-body","second-part"))assertFalse(records.toString().contains(secret))
    }
    @Test fun stagesDistinguishModelSynthesisDecoderAndPlaybackWithoutChangingChunks() = runBlocking {
        val t=trace().member(2)
        val played=mutableListOf<String>();val saved=mutableListOf<String>()
        GroupCallSpeech.play(t.model { flowOf(ChatEvent.Delta("这是第一句话。接着说第二句话。")) },
            {text->t.measure(GroupCallStep.SYNTHESIZE) {advance(10);text}.let { value->t.measure(GroupCallStep.DECODE) {advance(5);value} }},
            {text->t.measure(GroupCallStep.PLAY) {advance(40);played+=text}},
            {t.firstPlayback()},{text->t.measure(GroupCallStep.STORE) {saved+=text}})
        assertEquals(listOf("这是第一句话。","接着说第二句话。"),played);assertEquals(played,saved)
        assertEquals(1,records.count { "stage=first_audio " in it })
        for(step in listOf("model","synthesize","decode","play","store"))assertTrue(step,records.any { "stage=$step result=ok" in it })
        assertTrue(records.all { "requestId=abc00001" in it && "member=2" in it })
    }
    @Test fun noTextAndCancellationNeverFabricateFirstTextOrFirstAudio() = runBlocking {
        val t=trace()
        assertTrue(t.model { flowOf(ChatEvent.Reasoning("hidden")) }.toList().isNotEmpty())
        val entered=CompletableDeferred<Unit>()
        val job=launch { t.model { flow { entered.complete(Unit);awaitCancellation() } }.collect() }
        entered.await();job.cancelAndJoin()
        assertTrue(records.any { "stage=model result=cancelled" in it })
        assertFalse(records.any { "stage=first_text" in it || "stage=first_audio" in it || "event=group_call_failure" in it })
    }
    @Test fun originalCancellationAndPersistenceFailureKeepTheirControlFlowTypes() = runBlocking {
        val stop=CancellationException("private-stop")
        val failure=runCatching { trace().measure(GroupCallStep.PLAY) { throw stop } }.exceptionOrNull()
        assertSame(stop,failure)
        val disk=GroupCallPersistenceException(IOException("private-file"))
        assertSame(disk,runCatching { trace().measure(GroupCallStep.STORE) { throw disk } }.exceptionOrNull())
        assertTrue(records.any { "stage=store result=failed" in it && "category=storage" in it })
        assertFalse(records.toString().contains("private-"))
    }
    @Test fun synthesisFailureRetainsTheFailingStageThroughTheActualSpeechPipeline() = runBlocking {
        val t=trace()
        val error=runCatching { GroupCallSpeech.play(t.model { flowOf(ChatEvent.Delta("这是合成测试的一句话。")) },
            {t.measure(GroupCallStep.SYNTHESIZE) {throw SpeechException("secret-token-provider-body",category="http_429")}},
            {fail("no playback")},{fail("no first audio")},{fail("no persisted speech")}) }.exceptionOrNull()
        assertTrue(error is GroupCallStepException)
        assertEquals(GroupCallStep.SYNTHESIZE,(error as GroupCallStepException).step)
        assertTrue(records.any { "stage=synthesize result=failed" in it && "category=http_429" in it })
        assertFalse(records.toString().contains("secret-token"))
    }
    @Test fun failedDecoderAndPlaybackDoNotPretendToBeNetworkFailures() = runBlocking {
        for((step,category) in listOf(GroupCallStep.DECODE to "decode_error",GroupCallStep.PLAY to "playback_error")) {
            val error=runCatching { trace().measure(step) {throw IOException("private-path") } }.exceptionOrNull()
            assertEquals(step,(error as GroupCallStepException).step)
            assertTrue(records.any { "stage=${step.label} result=failed" in it && "category=$category" in it })
        }
    }
    @Test fun emptyAndTooLongTranscriptsAreTypedAndCannotCreateTurnsOrChangeRotation() = runBlocking {
        val rounds=GroupCallRounds(listOf(11,22));var begun=false
        for((text,empty) in listOf(" … 😼 " to true,"a".repeat(GroupChatRules.MAX_TEXT) to false)) {
            val error=runCatching { rounds.run({trace().measure(GroupCallStep.TRANSCRIBE) {GroupCallRules.userWords(text).substringAfter('\n')}},
                {_,ids->begun=true;ids},{},{}) }.exceptionOrNull()
            assertTrue(error is GroupCallInputException);assertEquals(empty,(error as GroupCallInputException).empty)
        }
        assertFalse(begun)
        var next=emptyList<Long>()
        rounds.run({"下一句"},{_,ids->next=ids;ids},{},{})
        assertEquals(listOf(11L,22),next)
        assertTrue(records.any { "event=group_call_input" in it && "category=empty_transcript" in it })
        assertTrue(records.any { "category=transcript_too_long" in it });assertFalse(records.any { "event=group_call_failure" in it })
    }
    @Test fun ownTranscriptionDeadlineAndOuterCancellationRemainDifferent() = runBlocking {
        val t=trace();val rounds=GroupCallRounds(listOf(11,22),30)
        val own=runCatching { rounds.run({t.measure(GroupCallStep.TRANSCRIBE) {awaitCancellation()}},{_,ids->ids},{},{}) }.exceptionOrNull()
        assertTrue(own is GroupCallTranscriptionTimeoutException)
        val outer=runCatching { withTimeout(30) {GroupCallRounds(listOf(11,22),10_000).run({t.measure(GroupCallStep.TRANSCRIBE) {awaitCancellation()}},{_,ids->ids},{},{})} }.exceptionOrNull()
        assertTrue(outer is TimeoutCancellationException)
        assertFalse(records.any { "event=group_call_failure" in it })
    }
    @Test fun networkCategoriesUseTypedCauseNeverProviderErrorProse() {
        for((cause,category) in listOf(UnknownHostException("private-host") to "dns",SocketTimeoutException("private-timeout") to "timeout",
            SSLException("private-cert") to "tls",IOException("private-io") to "network")) {
            val error=ChatException("secret-key raw-response").apply {initCause(cause)}
            trace().failure(GroupCallStep.TRANSCRIBE,error)
            assertEquals(category,GroupCallTrace.category(error))
        }
        assertFalse(records.toString().contains("private-"));assertFalse(records.toString().contains("secret-key"))
    }
    @Test fun arbitraryVoiceCategoriesAndClassNamesCannotEnterMetadata() {
        for(category in listOf("sk-secret","https://private.invalid/?key=secret","network_sk-secret","http_999")) {
            trace().failure(GroupCallStep.SYNTHESIZE,SpeechException("private-response",category=category))
        }
        val raw=records.joinToString()
        for(secret in listOf("sk-secret","https://","private-response","http_999"))assertFalse(secret,raw.contains(secret))
    }
    @Test fun failureMessagesAreStageSpecificAndNeverEchoRawResponses() {
        val raw=ChatException("secret-token raw-provider-body",401)
        assertTrue(GroupCallTrace.message(GroupCallStep.TRANSCRIBE,raw).contains("转写服务"))
        assertTrue(GroupCallTrace.message(GroupCallStep.MODEL,raw).contains("模型"))
        assertTrue(GroupCallTrace.message(GroupCallStep.SYNTHESIZE,raw).contains("声音"))
        assertFalse(GroupCallTrace.message(GroupCallStep.TRANSCRIBE,raw).contains("secret-token"))
        assertTrue(GroupCallTrace.message(GroupCallStep.TRANSCRIBE,GroupCallInputException(true)).contains("没听清"))
    }
    @Test fun diagnosticStorageFailuresCannotChangeRepliesOrStoredResults() = runBlocking {
        val t=GroupCallTrace({throw IOException("unavailable")})
        val result=t.model { flowOf(ChatEvent.Delta("ordinary reply")) }.toList()
        assertEquals(listOf(ChatEvent.Delta("ordinary reply")),result)
        assertEquals(7,t.measure(GroupCallStep.STORE) {7});t.firstPlayback();t.complete();t.cancelled()
    }
    @Test fun durationsAreBoundedAndMemberTracesHaveIndependentFirstAudioClocks() = runBlocking {
        val root=trace();root.audio(Long.MAX_VALUE);advance(1000)
        val first=root.member(1);advance(50);first.firstPlayback();first.firstPlayback();advance(1000)
        val second=root.member(2);advance(20);second.firstPlayback()
        assertTrue(records.any { "audioMs=60000" in it })
        assertTrue(records.any { "member=1 stage=first_audio result=ok elapsedMs=50" in it })
        assertTrue(records.any { "member=2 stage=first_audio result=ok elapsedMs=20" in it })
        assertEquals(2,records.count { "stage=first_audio" in it })
        now=-1;root.measure(GroupCallStep.STORE) {now=-10};assertTrue(records.any { "stage=store result=ok elapsedMs=0" in it })
    }
}
