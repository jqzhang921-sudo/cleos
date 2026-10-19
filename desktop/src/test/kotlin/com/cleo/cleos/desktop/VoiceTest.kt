package com.cleo.cleos.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioSystem
import kotlin.math.sin
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

internal fun syntheticVoice(root:Path,name:String="desktop-voice-${newId()}.wav"):VoiceDraft {
    val samples=ByteArray(32000)
    for(i in 0 until 16000){val v=(sin(2*Math.PI*440*i/16000)*4000).toInt();samples[i*2]=v.toByte();samples[i*2+1]=(v shr 8).toByte()}
    Files.createDirectories(root.resolve("media"));Files.write(root.resolve("media/$name"),wavBytes(samples))
    return VoiceDraft("media/$name",1000)
}
class VoiceTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `recorder opens only on start caps duration writes valid PCM WAV and releases device`() = runBlocking(Dispatchers.Swing) {
        val opened=AtomicInteger();val closed=AtomicInteger();var result:VoiceDraft?=null;val root=temp.newFolder().toPath()
        val recorder=VoiceRecorder {object:Microphone {
            override fun open(){opened.incrementAndGet()}
            override fun read(bytes:ByteArray):Int {bytes.fill(1);return bytes.size}
            override fun close(){closed.incrementAndGet()}
        }}
        try {
            assertEquals(0,opened.get());recorder.start(root,"original"){result=it}
            withTimeout(5000){while(result==null && recorder.state.active)delay(10)}
            val draft=result!!;assertEquals(120000,draft.millis);assertFalse(recorder.state.active)
            assertEquals(1,opened.get());assertTrue(closed.get()>0)
            AudioSystem.getAudioInputStream(localMedia(root,draft.file)!!.toFile()).use {input->
                assertEquals(16000f,input.format.sampleRate);assertEquals(16,input.format.sampleSizeInBits);assertEquals(1,input.format.channels)
                assertEquals(120L*16000,input.frameLength)
            }
        }finally{recorder.close()}
    }
    @Test fun `stop does not block UI closes blocked microphone and preserves captured samples`() = runBlocking(Dispatchers.Swing) {
        val blocked=CountDownLatch(1);val release=CountDownLatch(1);var calls=0;var result:VoiceDraft?=null
        val root=temp.newFolder().toPath()
        val recorder=VoiceRecorder {object:Microphone {
            override fun open(){}
            override fun read(bytes:ByteArray):Int {if(calls++<10){bytes.fill(1);return bytes.size};blocked.countDown();release.await(5,TimeUnit.SECONDS);throw java.io.IOException("closed test device")}
            override fun close(){release.countDown()}
        }}
        try {
            recorder.start(root,"a"){result=it};withContext(Dispatchers.IO){assertTrue(blocked.await(3,TimeUnit.SECONDS))}
            val start=System.nanoTime();recorder.stop();assertTrue((System.nanoTime()-start)/1_000_000<200)
            withTimeout(3000){while(recorder.state.active)delay(10)}
            assertEquals(1000,result!!.millis);assertNotNull(localMedia(root,result!!.file))
        }finally{recorder.close()}
    }
    @Test fun `close cancels recording without finishing callback or leaving audio file`() = runBlocking(Dispatchers.Swing) {
        val release=CountDownLatch(1);val entered=CountDownLatch(1);var finished=false
        val root=temp.newFolder().toPath()
        val recorder=VoiceRecorder {object:Microphone {
            override fun open(){}
            override fun read(bytes:ByteArray):Int {entered.countDown();release.await(3,TimeUnit.SECONDS);return -1}
            override fun close(){release.countDown()}
        }}
        recorder.start(root,"a"){finished=true};withContext(Dispatchers.IO){assertTrue(entered.await(3,TimeUnit.SECONDS))};recorder.close()
        withTimeout(3000){while(recorder.state.active)delay(10)}
        assertFalse(finished);assertFalse(Files.exists(root.resolve("media")))
    }
    @Test fun `voice draft survives restart backup restore with transcript and HTTP permission reset`() = runBlocking(Dispatchers.Swing) {
        val root=temp.newFolder().toPath();val voice=syntheticVoice(root).copy(transcript="仅合成测试的文字")
        val original=DesktopData.fresh().let {d->d.copy(transcription=TranscriptionSettings("http://127.0.0.1/v1","test-asr",true),conversations=d.conversations.map {it.copy(draftVoice=voice)})}
        LocalStore(root).use {it.save(original)};LocalStore(root).use {assertEquals(original,it.load())}
        val archive=DesktopBackupArchive();val p=archive.export(root,original,"合成",temp.newFolder().toPath().resolve("voice.zip"))
        val target=temp.newFolder().toPath();archive.write(p,target)
        assertEquals(voice,p.data.conversations.single().draftVoice);assertFalse(p.data.transcription.allowHttp)
        assertEquals(fileDigest(root.resolve(voice.file)),fileDigest(target.resolve(voice.file)))
        assertThrows(IllegalArgumentException::class.java){original.copy(conversations=original.conversations.map {it.copy(draftVoice=voice.copy(file="../private.wav"))}).checked()}
        Unit
    }
    @Test fun `transcription is explicit retryable cancelable and send passes text with local audio only`() = runBlocking(Dispatchers.Swing) {
        val root=temp.newFolder().toPath();val voice=syntheticVoice(root);val original=DesktopData.fresh()
        LocalStore(root).use {it.save(original.copy(conversations=original.conversations.map {it.copy(draftVoice=voice)}))}
        val vault=MemoryVault();val vm=DesktopController(LocalStore(root),vault);var requests=0
        vm.transcriber=VoiceTranscriber {_,_,_->requests++;if(requests==1)throw TextFailure("合成故障");"合成转写文字"}
        try {
            assertEquals(0,requests);vm.send();assertTrue(vm.notice.contains("转写"));assertTrue(vm.selected.messages.isEmpty())
            assertTrue(vm.configureVoice(TranscriptionSettings("https://example.invalid/v1","test"),"synthetic-voice-key",false))
            assertFalse(vm.hasKey(vm.data.companions.first()))
            vm.transcribeVoice();withTimeout(3000){while(vm.transcribing)delay(10)}
            assertNotNull(vm.selected.draftVoice);assertEquals("合成故障",vm.notice)
            vm.transcribeVoice();withTimeout(3000){while(vm.transcribing)delay(10)}
            assertEquals("合成转写文字",vm.selected.draftVoice!!.transcript)
            vm.voiceTranscript("人工确认的合成文字");assertEquals("人工确认的合成文字",vm.selected.draftVoice!!.transcript)
            val prompt=prompt(vm.data,vm.selected,vm.data.companions.first());assertFalse(prompt.toString().contains("人工确认"))
            val before=vm.selected.draftVoice
            vm.transcriber=VoiceTranscriber {_,_,_->delay(10000);"不应出现"};vm.transcribeVoice();delay(30);vm.cancelTranscription()
            withTimeout(3000){while(vm.transcribing)delay(10)};assertEquals(before,vm.selected.draftVoice)
            assertFalse(Files.readString(root.resolve("library.json")).contains("synthetic-voice-key"))
            vm.removeVoice();assertNull(vm.selected.draftVoice);assertNull(localMedia(root,voice.file))
        }finally{vm.close()}
    }
    @Test fun `record transcribe and group send use separate keys and never put WAV bytes in chat requests`() = runBlocking(Dispatchers.Swing) {
        val chatBodies=CopyOnWriteArrayList<String>();val auth=CopyOnWriteArrayList<String>();val uploads=AtomicInteger()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/"){e->
            val body=e.requestBody.readAllBytes();val key=e.requestHeaders.getFirst("Authorization")
            val result=if(e.requestURI.path.endsWith("/audio/transcriptions")) {
                uploads.incrementAndGet();assertEquals("Bearer voice-fixture",key);assertTrue(body.toString(Charsets.ISO_8859_1).contains("RIFF"));"""{"text":"只含合成声音的转写"}"""
            }else {chatBodies+=body.toString(Charsets.UTF_8);auth+=key;"""{"choices":[{"message":{"content":"合成群回复"},"finish_reason":"stop"}]}"""}
            val bytes=result.toByteArray();e.sendResponseHeaders(200,bytes.size.toLong());e.responseBody.use {it.write(bytes)}
        };server.start()
        val root=temp.newFolder().toPath();val vm=DesktopController(LocalStore(root),MemoryVault())
        try {
            val endpoint=Endpoint("http://127.0.0.1:${server.address.port}/v1","test-chat",true)
            val a=vm.data.companions.first().copy(name="甲",endpoint=endpoint);assertTrue(vm.configure(a,"chat-fixture",false))
            val b=vm.addCompanion("乙").copy(endpoint=endpoint);assertTrue(vm.configure(b,"chat-fixture",false))
            assertTrue(vm.createGroup("合成语音群",listOf(a.id,b.id)))
            assertTrue(vm.configureVoice(TranscriptionSettings(endpoint.baseUrl,"test-asr",true),"voice-fixture",false))
            var reads=0;vm.recorder.close();vm.recorder=VoiceRecorder {object:Microphone {
                override fun open(){}
                override fun read(bytes:ByteArray):Int {if(reads++>=10)return -1;bytes.fill(1);return bytes.size}
                override fun close(){}
            }}
            val group=vm.selected.id;vm.startRecording()
            assertTrue(vm.mediaBusy);val oldSelection=vm.selected.id;vm.select(vm.data.conversations.first().id);assertEquals(oldSelection,vm.selected.id)
            withTimeout(3000){while(vm.selected.draftVoice==null)delay(10)}
            assertEquals(0,uploads.get());assertTrue(chatBodies.isEmpty())
            vm.transcribeVoice();withTimeout(5000){while(vm.transcribing)delay(10)}
            assertEquals("只含合成声音的转写",vm.selected.draftVoice!!.transcript);assertTrue(chatBodies.isEmpty())
            vm.voiceTranscript("确认过的合成语音文字");vm.send();withTimeout(5000){while(vm.busyThread!=null)delay(10)}
            assertEquals(group,vm.selected.id);assertEquals(3,vm.selected.messages.size);assertNull(vm.selected.draftVoice)
            assertEquals("audio",vm.selected.messages.first().attachments.single().kind)
            assertEquals(2,chatBodies.size);assertTrue(auth.all {it=="Bearer chat-fixture"})
            assertTrue(chatBodies.all {it.contains("确认过的合成语音文字") && !it.contains("RIFF") && !it.contains("voice-fixture")})
            assertNotNull(localMedia(root,vm.selected.messages.first().attachments.single().file))
        }finally{vm.close();server.stop(0)}
    }
}
