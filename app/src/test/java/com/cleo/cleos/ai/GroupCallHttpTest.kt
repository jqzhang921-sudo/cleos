package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GroupCallHttpTest {
    @Test fun twoRoundsUseActualChatStreamsVoiceBodiesAndCurrentActorHistory() = runBlocking {
        val requests=CopyOnWriteArrayList<Pair<String,JsonObject>>()
        Fixture { peer ->
            val (path,body)=read(peer);requests+=path to body
            if(path.endsWith("/chat/completions")) {
                val id=body.getValue("model").jsonPrimitive.content.substringAfter("member-")
                val frame=buildJsonObject { put("choices",buildJsonArray { add(buildJsonObject {
                    put("delta",buildJsonObject { put("content","这是成员${id}的测试回复。") });put("finish_reason","stop")
                }) }) }
                reply(peer,"text/event-stream","data: $frame\n\ndata: [DONE]\n\n".toByteArray())
            } else {
                require(path.endsWith("/audio/speech"))
                reply(peer,"audio/wav",("synthetic-audio:"+body.getValue("voice").jsonPrimitive.content).toByteArray())
            }
        }.use { fixture ->
            val http=OkHttpClient();val client=ChatClient(http,diagnostic={})
            val group=ChatGroupEntity(1,"合成测试群",0,0)
            val members=listOf(GroupMemberEntity(1,11,0,"同名"),GroupMemberEntity(1,22,1,"同名"))
            val common=AppSettings(speechEngine="api",speechBaseUrl=fixture.base,speechModel="fixture-tts",speechVoice="shared")
            val tas=members.map { member -> CompanionEntity(id=member.companionId,name=member.name,apiBaseUrl=fixture.base,
                apiModel=if(member.companionId==11L)"text-only-11" else "member-22",createdAt=0,
                spokenModelOn=member.companionId==11L,spokenApiBaseUrl=fixture.base,spokenApiModel="member-11",
                voiceChoices=TaVoices.saved("{}",requireNotNull(TaVoices.scope(common)),if(member.companionId==11L)"alpha" else "beta")) }
            val rows=mutableListOf<GroupMessageEntity>();var seq=0L;var turn=0L
            val rounds=GroupCallRounds(listOf(11,22));val played=mutableListOf<String>()
            try {
                withTimeout(10_000) {
                    repeat(2) { n -> rounds.run({"第${n}轮合成转写"},{words,ids->
                        val anchor=++seq;turn++
                        rows+=GroupMessageEntity(anchor,1,"user",GroupCallRules.userWords(words),turn,mentions=GroupChatRules.encode(ids))
                        ids.map { id -> GroupMessageEntity(++seq,1,"assistant","",turn,speakerId=id,speakerName="同名",turnId=turn,status="pending") }
                    },{ slot ->
                        val ta=tas.first { it.id==slot.speakerId };val use=ta.modelFor(heard=true)
                        val request=GroupCallRules.request(group,members,listOf(11,22),ta,rows,"测试用户","",ZonedDateTime.now())
                        val said=StringBuilder()
                        GroupCallSpeech.play(client.stream(ApiEndpoint(use.baseUrl,"fixture-key",use.model),request),{words->
                            val voice=TaVoices.effective(common,ta)
                            val body=Speech.openAiBody(voice.speechModel,voice.speechVoice,words)
                            http.fetch(Request.Builder().url(fixture.base+"/audio/speech").post(body.toRequestBody("application/json".toMediaType())).build()) { it.body.string() }
                        },{played+=it},{},{CallSpeech.join(said,it)})
                        rows+=slot.copy(content=GroupCallRules.transcript(said.toString(),""),status="sent")
                    },{}) }
                }
                val chats=requests.filter { it.first.endsWith("/chat/completions") }.map { it.second }
                assertEquals(listOf("member-11","member-22","member-22","member-11"),chats.map { it.getValue("model").jsonPrimitive.content })
                assertEquals(listOf("synthetic-audio:alpha","synthetic-audio:beta","synthetic-audio:beta","synthetic-audio:alpha"),played)
                assertTrue(chats.all { it["tools"]==null || it["tools"]?.jsonArray?.isEmpty()==true })
                val second=chats[1].getValue("messages").jsonArray
                assertTrue(second.any { it.jsonObject["role"]?.jsonPrimitive?.content=="user" && it.toString().contains("成员 11") })
                assertEquals(6,rows.size);assertTrue(rows.all { it.status=="sent" })
                assertNull(fixture.error.get())
            } finally { http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll() }
        }
    }

    @Test fun cancellingWaitingGroupSpeechClosesTheActualHttpStream() = runBlocking {
        val entered=CompletableDeferred<Unit>();val disconnected=CountDownLatch(1);val cancellations=AtomicInteger()
        Fixture { peer ->
            read(peer)
            val out=peer.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: 10000000\r\nConnection: close\r\n\r\n".toByteArray());out.flush();entered.complete(Unit)
            try { while(true) { out.write(": heartbeat\n\n".toByteArray());out.flush();Thread.sleep(10) } }
            catch (_: IOException) { disconnected.countDown() }
        }.use { fixture ->
            val http=OkHttpClient.Builder().eventListener(object:EventListener(){override fun canceled(call:Call){cancellations.incrementAndGet()}}).build()
            var played=false
            try {
                withTimeout(5000) {
                    val job=launch { GroupCallSpeech.play(ChatClient(http,diagnostic={}).stream(ApiEndpoint(fixture.base,"fixture","member-11"),
                        listOf(ApiMessage("user","synthetic"))),{it},{played=true},{},{}) }
                    entered.await();job.cancelAndJoin()
                }
                assertFalse(played);assertTrue(cancellations.get()>0)
                assertTrue("server observes closed socket",disconnected.await(3,TimeUnit.SECONDS))
            } finally { http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll() }
        }
    }

    @Test fun cancellingActualTtsDownloadNeverStartsOrCommitsLatePlayback() = runBlocking {
        val synthesizing=CompletableDeferred<Unit>();val disconnected=CountDownLatch(1)
        Fixture { peer ->
            val (path,_)=read(peer)
            if(path.endsWith("/chat/completions")) {
                reply(peer,"text/event-stream",("data: {\"choices\":[{\"delta\":{\"content\":\"这句话正在合成声音。\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").toByteArray())
            } else {
                require(path.endsWith("/audio/speech"))
                val out=peer.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: 10000000\r\nConnection: close\r\n\r\n".toByteArray());out.flush()
                synthesizing.complete(Unit)
                try { while(true){out.write(ByteArray(1024));out.flush();Thread.sleep(10)} }
                catch(_:IOException){disconnected.countDown()}
            }
        }.use { fixture ->
            val http=OkHttpClient();var began=false;var played=false;var committed=false
            try {
                withTimeout(5000) {
                    val job=launch {
                        GroupCallSpeech.play(ChatClient(http,diagnostic={}).stream(ApiEndpoint(fixture.base,"fixture","member-11"),
                            listOf(ApiMessage("user","synthetic"))),{words->
                            http.fetch(Request.Builder().url(fixture.base+"/audio/speech")
                                .post(Speech.openAiBody("fixture","alpha",words).toRequestBody("application/json".toMediaType())).build()) { it.body.bytes() }
                        },{played=true},{began=true},{committed=true})
                    }
                    synthesizing.await();job.cancelAndJoin()
                }
                assertFalse(began);assertFalse(played);assertFalse(committed)
                assertTrue("TTS socket closes after cancellation",disconnected.await(3,TimeUnit.SECONDS))
            } finally { http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll() }
        }
    }

    private class Fixture(private val handler:(Socket)->Unit):AutoCloseable {
        private val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val running=AtomicBoolean(true)
        private val active=AtomicReference<Socket?>()
        val error=AtomicReference<Throwable?>()
        val base="http://127.0.0.1:${server.localPort}/v1"
        private val worker=thread(isDaemon=true,name="group-call-http-fixture") {
            while(running.get()) try { server.accept().use { peer -> active.set(peer);peer.soTimeout=3000;handler(peer);active.set(null) } }
            catch(e:Throwable) { if(running.get())error.compareAndSet(null,e) }
        }
        override fun close() { running.set(false);runCatching{active.get()?.close()};server.close();worker.join(1000) }
    }
    private companion object {
        fun read(peer:Socket):Pair<String,JsonObject> {
            val input=DataInputStream(peer.getInputStream())
            fun line():String {
                val text=StringBuilder()
                while(true){val c=input.read();if(c<0||c==10)break;if(c!=13)text.append(c.toChar())}
                return text.toString()
            }
            val path=line().split(' ')[1];var length=0
            while(true){val header=line();if(header.isEmpty())break;if(header.startsWith("Content-Length:",true))length=header.substringAfter(':').trim().toInt()}
            require(length in 1..100_000)
            val body=ByteArray(length);input.readFully(body)
            return path to Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        }
        fun reply(peer:Socket,mime:String,body:ByteArray) {
            val out=peer.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body);out.flush()
        }
    }
}
