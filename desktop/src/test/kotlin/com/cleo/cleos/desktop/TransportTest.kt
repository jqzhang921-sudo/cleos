package com.cleo.cleos.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TransportTest {
    private fun withServer(block: (HttpServer, Endpoint) -> Unit) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        try { block(server,Endpoint("http://127.0.0.1:${server.address.port}/v1", "fixture-model",true)) } finally { server.stop(0) }
    }
    @Test fun `real SSE request separates reasoning and body and never sends a key in json`() = withServer { server,endpoint ->
        var body="";var auth=""
        server.createContext("/v1/chat/completions") { exchange ->
            body=exchange.requestBody.readBytes().toString(Charsets.UTF_8);auth=exchange.requestHeaders.getFirst("Authorization")
            exchange.responseHeaders.add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0)
            exchange.responseBody.use { it.write(("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"想一下\"}}]}\n\n"+
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"+"data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n").toByteArray()) }
        };server.start()
        val events=runBlocking { TextClient().stream(endpoint,"synthetic-key",listOf(WireMessage("user","hello"))).toList() }
        assertEquals(listOf(TextEvent.Thought("想一下"),TextEvent.Text("你好")),events)
        assertEquals("Bearer synthetic-key",auth);assertTrue(body.contains("fixture-model"));assertFalse(body.contains("synthetic-key"))
    }
    @Test fun `aborted SSE reports failure after retaining a partial answer`() = withServer { server,endpoint ->
        server.createContext("/v1/chat/completions") { e -> e.responseHeaders.add("Content-Type","text/event-stream");e.sendResponseHeaders(200,0);e.responseBody.use { it.write("data: {\"choices\":[{\"delta\":{\"content\":\"半条\"}}]}\n\n".toByteArray()) } };server.start()
        val events=mutableListOf<TextEvent>()
        val error=runBlocking { runCatching { TextClient().stream(endpoint,"fixture",emptyList()).toList(events) }.exceptionOrNull() }
        assertEquals(listOf(TextEvent.Text("半条")),events);assertTrue(error is TextFailure);assertTrue(error!!.message!!.contains("提前结束"))
    }
    @Test fun `stop cancels an actual blocked connection promptly`() = withServer { server,endpoint ->
        val began=CountDownLatch(1);val release=CountDownLatch(1)
        server.createContext("/v1/chat/completions") { e ->
            e.responseHeaders.add("Content-Type","text/event-stream");e.sendResponseHeaders(200,0)
            try { e.responseBody.write(": ready\n\n".toByteArray());e.responseBody.flush();began.countDown();release.await(5,TimeUnit.SECONDS) } finally { e.close() }
        };server.start()
        try { runBlocking {
            val job=launch(Dispatchers.Default) { TextClient().stream(endpoint,"fixture",emptyList()).toList() }
            assertTrue(began.await(3,TimeUnit.SECONDS));withTimeout(1500) { job.cancelAndJoin() };assertTrue(job.isCancelled)
        } } finally { release.countDown() }
    }
    @Test fun `redirect never forwards a bearer key`() = withServer { server,endpoint ->
        var forwarded=false
        server.createContext("/v1/chat/completions") { e -> e.responseHeaders.add("Location","/destination");e.sendResponseHeaders(302,-1);e.close() }
        server.createContext("/destination") { e -> forwarded=true;e.sendResponseHeaders(200,-1);e.close() };server.start()
        val error=runBlocking { runCatching { TextClient().stream(endpoint,"fixture",emptyList()).toList() }.exceptionOrNull() }
        assertTrue(error is TextFailure);assertFalse(forwarded)
    }
    @Test fun `json response works for compatible non streaming relays`() = withServer { server,endpoint ->
        server.createContext("/v1/chat/completions") { e ->
            val body="""{"choices":[{"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}]}""".toByteArray()
            e.responseHeaders.add("Content-Type","application/json");e.sendResponseHeaders(200,body.size.toLong());e.responseBody.use { it.write(body) }
        };server.start()
        val events=runBlocking { TextClient().stream(endpoint,"fixture",emptyList()).toList() };assertEquals(listOf(TextEvent.Text("你好")),events)
    }
}
