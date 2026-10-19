package com.cleo.cleos.ai

import com.cleo.cleos.data.GroupBackupRules
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class GroupCallTest {
    private val group = ChatGroupEntity(1, "测试群", 10, 10, draft = "未发送的草稿")
    private val roster = listOf(GroupMemberEntity(1, 11, 0, "同名"), GroupMemberEntity(1, 22, 1, "同名"),
        GroupMemberEntity(1, 33, 2, "旁观者"))

    @Test fun exactlyTwoActualActiveParticipantsAreRequired() {
        assertEquals(listOf(11L,22L), GroupCallRules.selected(listOf(11,22), setOf(11,22,33)))
        for (ids in listOf(listOf(11L), listOf(11L,11), listOf(11L,33), listOf(11L,22,33))) {
            assertThrows(IllegalArgumentException::class.java) { GroupCallRules.selected(ids, setOf(11,22)) }
        }
    }

    @Test fun speakingOrderAlternatesOnlyTheChosenTwo() {
        assertEquals(listOf(11L,22), GroupCallRules.order(listOf(11,22),0))
        assertEquals(listOf(22L,11), GroupCallRules.order(listOf(11,22),1))
        assertEquals(listOf(11L,22), GroupCallRules.order(listOf(11,22),2))
    }

    @Test fun audioSlotRemainsExclusiveAndAnotherOwnerCannotReleaseIt() {
        val slot = CallSlot(); val privateCall = Any(); val groupCall = Any()
        assertTrue(slot.take(privateCall))
        assertFalse(slot.take(groupCall))
        slot.release(groupCall)
        assertFalse(slot.take(groupCall))
        slot.release(privateCall)
        assertTrue(slot.take(groupCall))
        assertFalse(slot.take(privateCall))
    }

    @Test fun cancellationCannotReleaseAudioBeforeCleanupCompletes() = runBlocking {
        val slot = CallSlot(); val first = Any(); val second = Any()
        val entered = CompletableDeferred<Unit>(); val cleaning = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        assertTrue(slot.take(first))
        val job = launch {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { cleaning.complete(Unit); finish.await(); slot.release(first) } }
        }
        entered.await(); job.cancel(); cleaning.await()
        assertFalse(slot.take(second))
        finish.complete(Unit); job.join()
        assertTrue(slot.take(second))
    }

    @Test fun callPromptKeepsActualSpeakerAndHasNoImagesOrPrivateContext() {
        val ta = CompanionEntity(id = 22, name = "同名", persona = "测试角色设定", createdAt = 1,
            apiBaseUrl = "https://example.test/v1", apiModel = "synthetic-model")
        val history = listOf(GroupMessageEntity(1,1,"user","[群通话转写]\n一起聊聊猫",10),
            GroupMessageEntity(2,1,"assistant","[群通话]\n我先说。",10,speakerId=11,speakerName="同名"),
            GroupMessageEntity(3,2,"user","另一个群的私密内容",10))
        val request = GroupCallRules.request(group, roster, listOf(11,22), ta, history, "用户", "", ZonedDateTime.now())
        assertTrue(request.first().content.contains("你是同名（成员 22）"))
        assertTrue(request.first().content.contains("本次在场的 TA 编号只有 11, 22"))
        assertTrue(request.first().content.contains("本次没有任何工具"))
        assertTrue(request.first().content.contains("只以自己的身份回复"))
        assertFalse(request.any { it.content.contains("另一个群的私密内容") })
        assertTrue(request.all { it.images.isEmpty() })
        assertTrue(request.any { it.role=="user" && it.content.contains("成员 11") && it.content.contains("我先说") })
    }

    @Test fun voiceTranscriptsRemainValidExistingGroupBackupData() {
        val user = GroupMessageEntity(1,1,"user",GroupCallRules.userWords("大家好"),10,mentions="[11,22]")
        val turn = GroupTurnEntity(1,1,1,"[11,22]",10,status="completed")
        val a = GroupMessageEntity(2,1,"assistant",GroupCallRules.transcript("你好。",""),11,
            speakerId=11,speakerName="同名",turnId=1)
        val b = a.copy(id=3,speakerId=22,content=GroupCallRules.transcript("早上好。","下一句被打断。"))
        GroupBackupRules.validate(listOf(group), roster, listOf(turn), listOf(user,a,b))
        assertEquals("未发送的草稿", group.draft)
        assertTrue(b.content.contains("未完整播完"))
        assertTrue(GroupCallRules.transcript("","").isEmpty())
    }

    @Test fun invalidTranscriptionDoesNotCreateFakeUserWords() {
        assertThrows(IllegalArgumentException::class.java) { GroupCallRules.userWords(" …… ") }
        assertThrows(IllegalArgumentException::class.java) { GroupCallRules.userWords("猫".repeat(GroupChatRules.MAX_TEXT)) }
        assertEquals("[群通话转写]\n猫猫", GroupCallRules.userWords(" 猫猫 "))
    }

    @Test fun speechPipelinePlaysInOrderAndCommitsOnlyAfterPlayback() = runBlocking {
        val timeline = mutableListOf<String>()
        GroupCallSpeech.play(flowOf(ChatEvent.Delta("第一句话说完。第二句话也说完。")),
            { "sound:$it" }, { timeline += "played:$it" },
            { timeline += "started:$it" }, { timeline += "committed:$it" })
        val committed = timeline.filter { it.startsWith("committed:") }
        assertEquals(listOf("committed:第一句话说完。", "committed:第二句话也说完。"),committed)
        for (text in listOf("第一句话说完。","第二句话也说完。")) {
            assertTrue(timeline.indexOf("played:sound:$text") < timeline.indexOf("committed:$text"))
        }
    }

    @Test fun failedSynthesisNeverClaimsWordsWerePlayed() = runBlocking {
        var began = false; var committed = false
        val failure = runCatching {
            GroupCallSpeech.play(flowOf(ChatEvent.Delta("这段没有合成成功。")),
                { _: String -> throw IllegalStateException("synthetic failure") }, { _: Unit -> error("must not play") },
                { began = true }, { committed = true })
        }
        assertTrue(failure.isFailure); assertFalse(began); assertFalse(committed)
    }

    @Test fun failedPlaybackDoesNotCommitTheWholeSentence() = runBlocking {
        var started = ""; val completed = mutableListOf<String>()
        val failed = runCatching {
            GroupCallSpeech.play(flowOf(ChatEvent.Delta("还没有说完的这一句话。")),
                { it }, { _: String -> error("synthetic audio failure") }, { started=it }, { completed+=it })
        }
        assertTrue(failed.isFailure); assertTrue(started.isNotBlank()); assertTrue(completed.isEmpty())
        assertTrue(GroupCallRules.transcript(completed.joinToString(""),started).contains("未完整播完"))
    }

    @Test fun interruptCancelsPlaybackAndDiscardsAllQueuedWords() = runBlocking {
        val playing = CompletableDeferred<Unit>(); var started=0; var committed=0; var released=false
        val job = launch {
            GroupCallSpeech.play(flowOf(ChatEvent.Delta("第一句正在播放。第二句话不要再播放。第三句话也要丢弃。")),
                { it }, { _: String -> try { playing.complete(Unit); awaitCancellation() } finally { released=true } },
                { started++ }, { committed++ })
        }
        playing.await(); job.cancelAndJoin()
        assertTrue(released); assertEquals(1,started); assertEquals(0,committed)
    }

    @Test fun interruptDuringTtsCancelsUpstreamAndNeverPlaysLateAudio() = runBlocking {
        val synthesizing=CompletableDeferred<Unit>(); var released=false; var played=false; var streamClosed=false
        val job=launch {
            GroupCallSpeech.play(flow {
                try { emit(ChatEvent.Delta("第一句声音正在合成。还有第二句话。")); awaitCancellation() }
                finally { streamClosed=true }
            }, { _: String -> try { synthesizing.complete(Unit); awaitCancellation() } finally { released=true } },
                { _: Unit -> played=true }, {}, {})
        }
        synthesizing.await(); job.cancelAndJoin()
        assertTrue(released); assertTrue(streamClosed); assertFalse(played)
    }

    @Test fun reasoningHeartbeatsHaveAWholeMemberDeadline() = runBlocking {
        var closed=false; var played=false
        val finished=withTimeoutOrNull(80) {
            GroupCallSpeech.play(flow {
                try { while (true) { emit(ChatEvent.Reasoning("hidden")); delay(1) } }
                finally { closed=true }
            }, { it }, { played=true }, {}, {})
            true
        }
        assertNull(finished); assertTrue(closed); assertFalse(played)
    }

    @Test fun emptyReasoningOnlyAndUnexpectedToolCallsNeverSucceedAsSpeech() = runBlocking {
        for (events in listOf(flowOf(ChatEvent.Reasoning("hidden")), emptyFlow())) {
            assertTrue(runCatching { GroupCallSpeech.play(events, { it }, {}, {}, {}) }.isFailure)
        }
        assertTrue(runCatching {
            GroupCallSpeech.play(flowOf(ChatEvent.ToolCalls(listOf(ToolCall("x","not_offered","{}")))),{it},{},{},{})
        }.isFailure)
    }

    @Test fun overlongResponseIsBoundedBeforeTts() = runBlocking {
        var synthesized=false
        assertTrue(runCatching {
            GroupCallSpeech.play(flowOf(ChatEvent.Delta("长".repeat(GroupCallRules.MAX_REPLY+1))),
                { synthesized=true; it }, {}, {}, {})
        }.isFailure)
        assertFalse(synthesized)
    }

    @Test fun speechPipelineBackpressureLimitsWorkWhilePlaybackIsBlocked() = runBlocking {
        val playing=CompletableDeferred<Unit>(); var generated=0; var synthesized=0
        val job=launch {
            GroupCallSpeech.play(flow {
                repeat(30) { generated++; emit(ChatEvent.Delta("这是第${it}句话结束。")) }
            }, { synthesized++; it }, { _: String -> playing.complete(Unit); awaitCancellation() }, {}, {})
        }
        playing.await(); repeat(20) { yield() }
        assertTrue("bounded synthesis", synthesized<=3)
        assertTrue("bounded generation", generated<12)
        job.cancelAndJoin()
    }
}
