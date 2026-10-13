package com.cleo.cleos.ai

import com.cleo.cleos.data.db.GroupMessageEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GroupReplyTimeoutTest {
    @Test fun reasoningHeartbeatsCannotKeepOneMemberWaitingForever() = runBlocking {
        var cleaned = false
        val stages = mutableListOf<GroupReplyStage>()
        val events = flow {
            try { while (true) { emit(ChatEvent.Reasoning("hidden")); delay(1) } }
            finally { cleaned = true }
        }
        try {
            GroupReplyEngine.reply(events, timeoutMillis = 40, onStage = { stages += it }) { fail("Reasoning must not become visible text") }
            fail("Expected a member deadline")
        } catch (_: GroupReplyTimeoutException) { }
        assertTrue(cleaned)
        assertEquals(listOf(GroupReplyStage.THINKING), stages)
    }

    @Test fun unfinishedTextIsRetainedWhenTheMemberDeadlineExpires() = runBlocking {
        var shown = ""
        var cleaned = false
        try {
            GroupReplyEngine.reply(flow {
                try { emit(ChatEvent.Delta("Already spoken")); awaitCancellation() }
                finally { cleaned = true }
            }, timeoutMillis = 40) { shown = it }
            fail()
        } catch (_: GroupReplyTimeoutException) { }
        assertEquals("Already spoken", shown)
        assertTrue(cleaned)
    }

    @Test fun aTimedOutSlotCanFailAndTheNextMemberStillReplies() = runBlocking {
        val calls = mutableListOf<Long>()
        val failed = mutableListOf<Long>()
        val sent = mutableMapOf<Long, String>()
        val slots = listOf(10L, 20L).map { GroupMessageEntity(id = it, groupId = 1, role = "assistant",
            content = "", createdAt = 0, speakerId = it, turnId = 1, status = "pending") }
        GroupReplyEngine.round(slots, null, { true }) { slot ->
            calls += slot.speakerId!!
            try {
                sent[slot.speakerId] = GroupReplyEngine.reply(if (slot.speakerId == 10L) flow { awaitCancellation() }
                    else flowOf(ChatEvent.Delta("Next member answered")), timeoutMillis = 40) { }
            } catch (_: GroupReplyTimeoutException) { failed += slot.speakerId }
        }
        assertEquals(listOf(10L, 20L), calls)
        assertEquals(listOf(10L), failed)
        assertEquals(mapOf(20L to "Next member answered"), sent)
    }

    @Test fun manualStopIsNotConvertedIntoAnAutomaticMemberTimeout() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        var timedOut = false
        val job = launch {
            try {
                GroupReplyEngine.reply(flow { started.complete(Unit); awaitCancellation() }, timeoutMillis = 10_000) { }
            } catch (_: GroupReplyTimeoutException) { timedOut = true }
            catch (e: CancellationException) { cancelled = true; throw e }
        }
        started.await(); job.cancelAndJoin()
        assertTrue(cancelled)
        assertFalse(timedOut)
    }

    @Test fun displayedPhaseFollowsRealEventsAndDoesNotExposeReasoning() = runBlocking {
        val stages = mutableListOf<GroupReplyStage>()
        var shown = ""
        val result = GroupReplyEngine.reply(flowOf(ChatEvent.Delta(""), ChatEvent.Reasoning("private thought"),
            ChatEvent.Delta("Hello"), ChatEvent.Reasoning("later hidden thought"), ChatEvent.Delta("!")),
            onStage = { stages += it }) { shown = it }
        assertEquals(listOf(GroupReplyStage.THINKING, GroupReplyStage.ANSWERING), stages)
        assertEquals("Hello!", result)
        assertEquals(result, shown)
    }

    @Test fun elapsedLabelUsesEachMembersOwnStartAndClampsAnEarlierUiTick() {
        val first = GroupReplyProgress(1_000_000_000L)
        assertEquals("等待接口回复 · 12秒", first.label(13_900_000_000L))
        assertEquals("正在思考 · 12秒", first.copy(stage = GroupReplyStage.THINKING).label(13_900_000_000L))
        assertEquals("正在回复 · 12秒", first.copy(stage = GroupReplyStage.ANSWERING).label(13_900_000_000L))
        assertEquals("等待接口回复 · 0秒", GroupReplyProgress(14_000_000_000L).label(13_900_000_000L))
    }

    @Test fun realHttpHeartbeatsAreCancelledWithoutWaitingForTheSocketReadTimeout() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val socket = AtomicReference<Socket?>()
        val streaming = CompletableDeferred<Unit>()
        val worker = thread(name = "group-timeout-fixture", isDaemon = true) {
            try {
                server.accept().use { peer ->
                    socket.set(peer)
                    peer.soTimeout = 2000
                    val reader = peer.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val writer = peer.getOutputStream().bufferedWriter()
                    writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                    writer.write(": heartbeat\n\n"); writer.flush()
                    streaming.complete(Unit)
                    // Safety bound for a broken cancellation implementation: the fixture never runs indefinitely.
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
                    while (System.nanoTime() < until) {
                        writer.write(": heartbeat\n\n"); writer.flush(); Thread.sleep(20)
                    }
                }
            } catch (_: IOException) { }
        }
        val http = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
        try {
            withTimeout(5000) { supervisorScope {
                val reply = async {
                    GroupReplyEngine.reply(ChatClient(http, diagnostic = {}).stream(
                        ApiEndpoint("http://127.0.0.1:${server.localPort}/v1", "fixture-key", "fixture-model"),
                        listOf(ApiMessage("user", "synthetic test"))), timeoutMillis = 1500) { fail("Heartbeat is not text") }
                }
                streaming.await()
                try { reply.await(); fail("Expected total deadline despite ongoing heartbeats") }
                catch (_: GroupReplyTimeoutException) { }
            } }
        } finally {
            socket.get()?.close(); server.close(); worker.join(1000)
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
