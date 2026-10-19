package com.cleo.cleos.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VoiceClientTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `multipart request uploads one synthetic WAV with isolated key and JSON text response`() = runBlocking {
        var body="";var auth="";var path=""
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/"){e->path=e.requestURI.path;auth=e.requestHeaders.getFirst("Authorization");body=e.requestBody.readAllBytes().toString(Charsets.ISO_8859_1)
            val response="""{"text":"合成识别文字"}""".toByteArray();e.sendResponseHeaders(200,response.size.toLong());e.responseBody.use {it.write(response)}};server.start()
        try {
            val root=temp.newFolder().toPath();val voice=syntheticVoice(root)
            val settings=TranscriptionSettings("http://127.0.0.1:${server.address.port}/v1","synthetic-asr",true)
            assertEquals("合成识别文字",VoiceClient().transcribe(settings,"synthetic-voice-key",root.resolve(voice.file)))
            assertEquals("/v1/audio/transcriptions",path);assertEquals("Bearer synthetic-voice-key",auth)
            assertTrue(body.contains("name=\"model\""));assertTrue(body.contains("synthetic-asr"));assertTrue(body.contains("filename=\"recording.wav\""))
            assertTrue(body.contains("RIFF"));assertFalse(body.contains(root.toString()));assertFalse(body.contains("messages"))
        }finally{server.stop(0)}
    }
    @Test fun `redirect never forwards credentials and failures never echo arbitrary response`() = runBlocking {
        val hits=AtomicInteger();val other=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        other.createContext("/"){hits.incrementAndGet();it.sendResponseHeaders(200,0);it.close()};other.start()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);var code=307
        server.createContext("/"){e->e.requestBody.readAllBytes();e.responseHeaders.add("Location","http://127.0.0.1:${other.address.port}/")
            val response="""{"code":"INSUFFICIENT_BALANCE","private":"do-not-echo"}""".toByteArray();e.sendResponseHeaders(code,response.size.toLong());e.responseBody.use {it.write(response)}};server.start()
        try {
            val root=temp.newFolder().toPath();val voice=syntheticVoice(root);val settings=TranscriptionSettings("http://127.0.0.1:${server.address.port}/v1","test",true)
            suspend fun failure():String=try{VoiceClient().transcribe(settings,"key",root.resolve(voice.file));error("expected failure")}catch(e:TextFailure){e.message.orEmpty()}
            assertFalse(failure().contains("do-not-echo"));assertEquals(0,hits.get())
            code=402;assertTrue(failure().contains("余额"))
            assertThrows(TextFailure::class.java){transcriptionUrl(settings.copy(allowHttp=false))}
            assertThrows(TextFailure::class.java){transcriptionUrl(settings.copy(baseUrl="https://user:pass@example.invalid/v1"))}
            Unit
        }finally{server.stop(0);other.stop(0)}
    }
    @Test fun `cancel interrupts pending network request`() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/"){e->e.requestBody.readAllBytes();entered.countDown();release.await(5,TimeUnit.SECONDS);runCatching {e.sendResponseHeaders(200,0);e.close()}};server.start()
        try {
            val root=temp.newFolder().toPath();val voice=syntheticVoice(root)
            val job=launch {VoiceClient().transcribe(TranscriptionSettings("http://127.0.0.1:${server.address.port}/v1","test",true),"key",root.resolve(voice.file))}
            assertTrue(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
            withTimeout(1500){job.cancelAndJoin()};assertTrue(job.isCancelled)
        }finally{release.countDown();server.stop(0)}
    }
    @Test fun `blank malformed oversized results and invalid WAV fail without losing source`() = runBlocking {
        var response="{\"text\":\"\"}";val hits=AtomicInteger()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/"){e->e.requestBody.readAllBytes();hits.incrementAndGet();val bytes=response.toByteArray();runCatching {e.sendResponseHeaders(200,bytes.size.toLong());e.responseBody.use {it.write(bytes)}}};server.start()
        try {
            val root=temp.newFolder().toPath();val voice=syntheticVoice(root);val file=root.resolve(voice.file);val digest=fileDigest(file)
            val settings=TranscriptionSettings("http://127.0.0.1:${server.address.port}/v1","test",true)
            for(body in listOf("{\"text\":\"\"}","<html>not transcription</html>","{\"text\":\""+"x".repeat(200001)+"\"}")) {
                response=body
                try{VoiceClient().transcribe(settings,"key",file);fail("expected invalid response")}catch(_:TextFailure){}
                assertEquals(digest,fileDigest(file))
            }
            val before=hits.get();java.nio.file.Files.write(file,ByteArray(64))
            try{VoiceClient().transcribe(settings,"key",file);fail("expected invalid WAV")}catch(_:TextFailure){}
            assertEquals(before,hits.get())
        }finally{server.stop(0)}
    }
}
