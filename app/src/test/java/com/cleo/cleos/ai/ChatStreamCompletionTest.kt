package com.cleo.cleos.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ChatStreamCompletionTest {
    @Test fun completedStreamsAreNotCancelledAndImmediateAudioRequestsStayReadable() = runBlocking {
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));val running=AtomicBoolean(true)
        val audio=ByteArray(64044) { (it%251).toByte() };val cancelled=AtomicInteger()
        val worker=thread(isDaemon=true,name="stream-completion-fixture") {
            while(running.get()) try {
                server.accept().use { peer ->
                    peer.soTimeout=3000
                    val input=DataInputStream(peer.getInputStream())
                    fun line():String {
                        val s=StringBuilder()
                        while(true) { val c=input.read();if(c<0||c==10)break;if(c!=13)s.append(c.toChar()) }
                        return s.toString()
                    }
                    val first=line();var length=0
                    while(true) { val h=line();if(h.isEmpty())break;if(h.startsWith("Content-Length:",true))length=h.substringAfter(':').trim().toInt() }
                    require(length in 0..100000);input.readFully(ByteArray(length))
                    val isAudio=first.contains("/audio/speech")
                    val bytes=if(isAudio)audio else "data: {\"choices\":[{\"delta\":{\"content\":\"ready\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray()
                    val out=peer.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nContent-Type: ${if(isAudio)"audio/wav" else "text/event-stream"}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes);out.flush()
                }
            }catch(_:IOException){}
        }
        val http=OkHttpClient.Builder().eventListener(object:EventListener(){override fun canceled(call:Call){cancelled.incrementAndGet()}}).build()
        val base="http://127.0.0.1:${server.localPort}/v1"
        try {
            withTimeout(10_000) {
                repeat(8) {
                    val events=ChatClient(http,diagnostic={}).stream(ApiEndpoint(base,"synthetic-fixture-key","fixture"),listOf(ApiMessage("user","synthetic"))).toList()
                    assertEquals("ready",events.filterIsInstance<ChatEvent.Delta>().joinToString(""){it.text})
                    val received=http.fetch(Request.Builder().url("$base/audio/speech").post("fixture".toRequestBody()).build()){it.body.bytes()}
                    assertArrayEquals(audio,received)
                }
            }
            assertEquals("Successful stream completion must not emit cancellation",0,cancelled.get())
        }finally{
            running.set(false);server.close();worker.join(1000)
            http.dispatcher.executorService.shutdownNow();http.connectionPool.evictAll()
        }
    }
}
