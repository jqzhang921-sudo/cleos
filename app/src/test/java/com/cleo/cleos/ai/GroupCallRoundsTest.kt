package com.cleo.cleos.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test

class GroupCallRoundsTest {
    @Test fun threeFullRoundsUseAlternatingActorsAndOnlyFinishAfterBothSpeak() = runBlocking {
        val rounds = GroupCallRounds(listOf(11,22))
        val trace = mutableListOf<String>()
        repeat(3) { n ->
            rounds.run({ "第${n}次用户发言" }, { words, ids -> trace += "begin:$words"; ids },
                { id ->
                    GroupCallSpeech.play(flowOf(ChatEvent.Delta("成员${id}的测试发言。")),
                        { "voice-$id:$it" }, { trace += "play:$id" }, {}, { trace += "saved:$id" })
                }, { ids -> trace += "finish:${ids.joinToString()}" })
        }
        assertEquals(listOf("play:11","play:22","play:22","play:11","play:11","play:22"),trace.filter { it.startsWith("play:") })
        assertEquals(3,trace.count { it.startsWith("begin:") })
        assertEquals(3,trace.count { it.startsWith("finish:") })
        for (i in trace.indices.filter { trace[it].startsWith("finish:") }) assertTrue(trace[i-1].startsWith("saved:"))
    }

    @Test fun cancelDuringAsrCreatesNoSlotsAndDoesNotAdvanceNextSpeaker() = runBlocking {
        val rounds=GroupCallRounds(listOf(11,22));val entered=CompletableDeferred<Unit>()
        var begun=0;var finished=0
        val job=launch { rounds.run({ entered.complete(Unit);awaitCancellation() },
            { _,ids -> begun++;ids },{}, { finished++ }) }
        entered.await();job.cancelAndJoin()
        assertEquals(0,begun);assertEquals(0,finished)
        var next=emptyList<Long>()
        rounds.run({"再试一次"},{_,ids->next=ids;ids},{},{})
        assertEquals(listOf(11L,22),next)
    }

    @Test fun cancellationDuringDurableBeginFinalizesReturnedSlotsWithoutRequestingModels() = runBlocking {
        val rounds=GroupCallRounds(listOf(11,22));val writing=CompletableDeferred<Unit>();val committed=CompletableDeferred<Unit>()
        var requested=0;var finalized=emptyList<Long>()
        val job=launch {
            rounds.run({"已经说完的用户发言"},{_,ids->withContext(Dispatchers.Default){writing.complete(Unit);committed.await();ids}},
                {requested++},{finalized=it})
        }
        writing.await();job.cancel();committed.complete(Unit);job.join()
        assertEquals(0,requested);assertEquals(listOf(11L,22),finalized)
    }

    @Test fun interruptWhileFirstMemberSpeaksSkipsSecondAndKeepsCompletedSentence() = runBlocking {
        val rounds=GroupCallRounds(listOf(11,22));val playing=CompletableDeferred<Unit>()
        val requested=mutableListOf<Long>();val saved=mutableListOf<String>();var finalized=false
        val job=launch {
            rounds.run({"测试用户发言"},{_,ids->ids},{id->
                requested+=id
                GroupCallSpeech.play(flowOf(ChatEvent.Delta("第一句话已经说完。第二句话没有说完。")),{it},
                    {text->if(text.startsWith("第二")){playing.complete(Unit);awaitCancellation()}},{},{saved+=it})
            },{finalized=true})
        }
        playing.await();job.cancelAndJoin()
        assertEquals(listOf(11L),requested);assertEquals(listOf("第一句话已经说完。"),saved);assertTrue(finalized)
    }

    @Test fun handledMemberTtsFailureDoesNotPreventTheOtherMemberFromSpeaking() = runBlocking {
        val rounds=GroupCallRounds(listOf(11,22));val status=mutableMapOf<Long,String>()
        rounds.run({"聊两句吧"},{_,ids->ids},{id->
            val result=runCatching { GroupCallSpeech.play(flowOf(ChatEvent.Delta("这是一句测试的话。")),
                {if(id==11L)error("synthetic TTS failure");it},{},{},{}) }
            status[id]=if(result.isSuccess)"sent" else "failed"
        },{})
        assertEquals(mapOf(11L to "failed",22L to "sent"),status)
    }

    @Test fun failedBeginDoesNotAlternateOrPretendItFinalizedAStoredTurn() = runBlocking {
        val rounds=GroupCallRounds(listOf(11,22));var finalized=false
        val failed=runCatching { rounds.run<Long>({"测试"},{_,_->error("synthetic transaction rollback")},{},{finalized=true}) }.exceptionOrNull()
        assertTrue(failed is GroupCallPersistenceException);assertFalse(finalized)
        var next=emptyList<Long>()
        rounds.run({"再试"},{_,ids->next=ids;ids},{},{})
        assertEquals(listOf(11L,22),next)
    }

    @Test fun failureToSaveDuringSpeechSkipsRemainingMemberAndStillFinalizes() = runBlocking {
        val rounds=GroupCallRounds(listOf(11,22));val requested=mutableListOf<Long>();var finalized=false
        val failed=runCatching { rounds.run({"测试"},{_,ids->ids},{id->
            requested+=id;throw GroupCallPersistenceException(IllegalStateException("synthetic storage error"))
        },{finalized=true}) }.exceptionOrNull()
        assertTrue(failed is GroupCallPersistenceException);assertEquals(listOf(11L),requested);assertTrue(finalized)
    }

    @Test fun failedFinalizationIsReportedAsStorageFailure() = runBlocking {
        val rounds=GroupCallRounds(listOf(11,22));var requested=0
        val failed=runCatching { rounds.run({"测试"},{_,ids->ids},{requested++},{error("synthetic storage error")}) }.exceptionOrNull()
        assertTrue(failed is GroupCallPersistenceException);assertEquals(2,requested)
    }

    @Test fun intentionalLeaseCancellationIsNotReportedAsAStorageFailure() = runBlocking {
        val owner=Job();val rounds=GroupCallRounds(listOf(11,22));var requested=false
        val failure=runCatching { rounds.run({"测试"},{_,ids->
            owner.cancel();owner.ensureActive();ids
        },{requested=true},{}) }.exceptionOrNull()
        assertTrue(failure is CancellationException);assertFalse(failure is GroupCallPersistenceException);assertFalse(requested)
    }

    @Test fun ownAsrDeadlineIsRecoverableWithoutPersistingATurn() = runBlocking {
        val rounds=GroupCallRounds(listOf(11,22),40);var begun=false
        val failure=runCatching { rounds.run({awaitCancellation()},{_,ids->begun=true;ids},{},{}) }.exceptionOrNull()
        assertTrue(failure is GroupCallTranscriptionTimeoutException);assertFalse(begun)
        var called=0
        rounds.run({"恢复后的下一轮"},{_,ids->ids},{called++},{})
        assertEquals(2,called)
    }

    @Test fun outerDeadlineIsNotMisreportedAsAnAsrError() = runBlocking {
        val rounds=GroupCallRounds(listOf(11,22),10_000);var begun=false
        val failure=runCatching { withTimeout(40) {
            rounds.run({awaitCancellation()},{_,ids->begun=true;ids},{},{})
        } }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException);assertFalse(begun)
    }

    @Test fun concurrentRoundWaiterCanBeCancelledWithoutStartingAnotherTranscription() = runBlocking {
        val rounds=GroupCallRounds(listOf(11,22));val speaking=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        var unexpectedAsr=false
        val first=launch { rounds.run({"第一轮"},{_,ids->ids},{speaking.complete(Unit);finish.await()},{}) }
        speaking.await()
        val waiting=launch { rounds.run({unexpectedAsr=true;"不该开始"},{_,ids->ids},{},{}) }
        yield();waiting.cancelAndJoin();finish.complete(Unit);first.join()
        assertFalse(unexpectedAsr)
    }

    @Test fun blankAsrCannotCreateAUserTurnOrAdvanceTheOrder() = runBlocking {
        val rounds=GroupCallRounds(listOf(11,22));var begun=false
        assertTrue(runCatching { rounds.run({"……"},{_,ids->begun=true;ids},{},{}) }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(begun)
    }

    @Test fun cancelBeforeLazyBodyStartsStillRunsCompletionAndReleasesAudio() = runBlocking {
        val slot=CallSlot();val owner=Any();var body=false;var closed=false
        assertTrue(slot.take(owner))
        val job=ownedCallJob(this,slot,owner,{closed=true}) { body=true }
        job.cancelAndJoin()
        assertFalse(body);assertTrue(closed);assertTrue(slot.take(Any()))
    }

    @Test fun alreadyCancelledScopeCannotLeaveARingingOwnerBehind() = runBlocking {
        val parent=Job().also { it.cancel() };val slot=CallSlot();val owner=Any();var closed=false;var body=false
        assertTrue(slot.take(owner))
        val job=ownedCallJob(CoroutineScope(parent+Dispatchers.Default),slot,owner,{closed=true}) { body=true }
        job.start();job.join()
        assertFalse(body);assertTrue(closed);assertTrue(slot.take(Any()))
    }

    @Test fun ownedJobKeepsAudioUntilCancellationCleanupAndCompletionHaveFinished() = runBlocking {
        val slot=CallSlot();val owner=Any();val started=CompletableDeferred<Unit>();val cleaning=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        var closed=false;assertTrue(slot.take(owner))
        val job=ownedCallJob(this,slot,owner,{closed=true}) {
            try { started.complete(Unit);awaitCancellation() }
            finally { withContext(NonCancellable) { cleaning.complete(Unit);finish.await() } }
        }
        job.start();started.await();job.cancel();cleaning.await()
        assertFalse(closed);assertFalse(slot.take(Any()))
        finish.complete(Unit);job.join()
        assertTrue(closed);assertTrue(slot.take(Any()))
    }
}
