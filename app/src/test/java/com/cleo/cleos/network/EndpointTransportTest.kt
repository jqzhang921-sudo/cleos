package com.cleo.cleos.network

import com.cleo.cleos.ai.*
import com.cleo.cleos.data.HttpPermissions
import com.cleo.cleos.data.McpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class EndpointTransportTest {
    @Test fun canonicalOriginsAndInvalidAddressesNeverRetainCredentials() {
        assertEquals("http://example.test:80", EndpointTransport.httpOrigin("HTTP://EXAMPLE.TEST/path?key=fixture"))
        assertEquals("http://[::1]:9000", EndpointTransport.httpOrigin("http://[::1]:9000/v1"))
        for (url in listOf("https://example.test", "file:///tmp", "http://name:password@example.test", "http://example.test/#fragment"))
            assertNull(EndpointTransport.httpOrigin(url))
        val req = Request.Builder().url("http://example.test/v1").build()
        try { EndpointTransport.prepare(req, "http://name:secret@example.test", true); fail() }
        catch (e: IOException) { assertFalse(e.message!!.contains("secret")) }
    }
    @Test fun untaggedTrafficAndUnapprovedModelsNeverSendAnyHeaders() = runBlocking {
        Fixture { _, _ -> Reply() }.use { server ->
            val http = EndpointTransport.clientBuilder().build()
            try { http.newCall(Request.Builder().url(server.base).header("Authorization", "Bearer fixture-key").build()).execute(); fail() }
            catch (e: EndpointTransportException) { assertTrue(e.message!!.contains("不允许")) }
            val client = ChatClient(http, diagnostic = {}, prepare = { r, url -> EndpointTransport.prepare(r, url, false) })
            try { client.probe(ApiEndpoint(server.base, "fixture-key", "fixture")); fail() }
            catch (e: ChatException) { assertTrue(e.message!!.contains("尚未授权")) }
            assertTrue(server.requests.isEmpty())
        }
    }
    @Test fun actualModelProbeListAndStreamUseApprovedOriginAndRevocationBlocksNextRequest() = runBlocking {
        Fixture { path, _ -> when {
            path.endsWith("/models") -> Reply(body = "{\"data\":[{\"id\":\"fixture\"}]}")
            else -> Reply(body = "{\"choices\":[{\"message\":{\"content\":\"OK\"},\"delta\":{\"content\":\"OK\"}}]}")
        } }.use { server ->
            val raw = MutableStateFlow<String?>(null)
            val grants = HttpPermissions({ raw }, { raw.value = it })
            grants.set(EndpointTransport.httpOrigin(server.base)!!, true)
            val client = ChatClient(EndpointTransport.clientBuilder().build(), diagnostic = {}, prepare = { r, url ->
                EndpointTransport.prepare(r, url, grants.allowed(url))
            })
            val endpoint = ApiEndpoint(server.base + "/v1", "fixture-key", "fixture")
            client.probe(endpoint)
            assertEquals(listOf("fixture"), client.models(endpoint))
            assertTrue(client.stream(endpoint, listOf(ApiMessage("user", "synthetic"))).toList().any { it is ChatEvent.Delta && it.text == "OK" })
            assertEquals(3, server.requests.size)
            assertTrue(server.requests.all { it.contains("Authorization: Bearer fixture-key", true) })
            grants.set(EndpointTransport.httpOrigin(server.base)!!, false)
            try { client.probe(endpoint); fail() } catch (_: ChatException) { }
            assertEquals(3, server.requests.size)
        }
    }
    @Test fun actualMcpInitializeListAndCallRequireTheirOwnServiceConsentEvenWithCachedSession() = runBlocking {
        Fixture { _, body ->
            val request = Json.parseToJsonElement(body).jsonObject
            val id = request["id"]
            if (id == null) Reply(code = 202, body = "") else {
                val result = when (request["method"]!!.jsonPrimitive.content) {
                    "initialize" -> "{\"protocolVersion\":\"2025-06-18\"}"
                    "tools/list" -> "{\"tools\":[{\"name\":\"hello\",\"inputSchema\":{\"type\":\"object\"}}]}"
                    else -> "{\"content\":[{\"type\":\"text\",\"text\":\"fixture result\"}]}"
                }
                Reply(body = "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}")
            }
        }.use { server ->
            val raw = MutableStateFlow<String?>(null); val grants = HttpPermissions({ raw }, { raw.value = it })
            val origin = EndpointTransport.httpOrigin(server.base)!!
            grants.set(origin, true)
            val client = McpClient(EndpointTransport.clientBuilder().build(), "fixture", prepare = { r, s ->
                EndpointTransport.prepare(r, s.url, grants.allowed(s.url, s.id))
            })
            val service = McpServer("first", url = server.base + "/mcp", token = "fixture-token", header = "X-Fixture: synthetic")
            try { client.listTools(service); fail() } catch (_: McpException) { }
            assertTrue(server.requests.isEmpty())
            grants.set(origin, true, service.id)
            assertEquals("hello", client.listTools(service).single().name)
            assertEquals("fixture result", client.callTool(service, "hello", JsonObject(emptyMap())))
            assertTrue(server.requests.all { it.contains("Authorization: Bearer fixture-token", true) && it.contains("X-Fixture: synthetic", true) })
            val sent = server.requests.size
            grants.set(origin, false, service.id)
            try { client.callTool(service, "hello", JsonObject(emptyMap())); fail() } catch (_: McpException) { }
            assertEquals(sent, server.requests.size)
        }
    }
    @Test fun redirectToAnotherPortCannotForwardBodyOrCustomCredentials() {
        Fixture { _, _ -> Reply() }.use { destination ->
            Fixture { _, _ -> Reply(307, "", "Location: ${destination.base}/stolen\r\n") }.use { source ->
                val request = EndpointTransport.prepare(Request.Builder().url(source.base + "/mcp")
                    .header("Authorization", "Bearer fixture-token").header("X-Api-Key", "synthetic-key")
                    .post(RequestBody.create(null, "synthetic-private-body")).build(), source.base, true)
                try { EndpointTransport.clientBuilder().build().newCall(request).execute(); fail() }
                catch (e: EndpointTransportException) { assertTrue(e.message!!.contains("跳转")) }
                assertEquals(1, source.requests.size)
                assertTrue(destination.requests.isEmpty())
                assertEquals(0, destination.connections.get())
            }
        }
    }
    @Test fun relativeSameOriginRedirectKeepsWorkingAndOrdinaryTrafficCannotReuseApproval() {
        Fixture { path, _ -> if (path == "/start") Reply(307, "", "Location: /final\r\n") else Reply() }.use { server ->
            val http = EndpointTransport.clientBuilder().build()
            assertFalse(http.followSslRedirects)
            val request = EndpointTransport.prepare(Request.Builder().url(server.base + "/start").build(), server.base, true)
            http.newCall(request).execute().use { assertEquals(200, it.code) }
            assertEquals(2, server.requests.size)
            try { http.newCall(Request.Builder().url(server.base + "/image").build()).execute(); fail() }
            catch (_: EndpointTransportException) { }
            assertEquals(2, server.requests.size)
        }
    }
    @Test fun staleOrDifferentOriginCannotReceiveAnEndpointPermit() {
        for (target in listOf("http://other.test/", "http://configured.test:8080/", "https://configured.test/")) {
            try { EndpointTransport.prepare(Request.Builder().url(target).build(), "http://configured.test/", true); fail() }
            catch (_: EndpointTransportException) { }
        }
        val secure = Request.Builder().url("https://configured.test/v1").build()
        assertEquals(secure.url, EndpointTransport.prepare(secure, "https://configured.test/", false).url)
    }

    private data class Reply(val code: Int = 200, val body: String = "{}", val headers: String = "")
    private class Fixture(val handler: (String, String) -> Reply) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val requests = CopyOnWriteArrayList<String>()
        val connections = AtomicInteger()
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    connections.incrementAndGet()
                    socket.soTimeout = 3000
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    fun line(): String? {
                        val bytes = ByteArrayOutputStream()
                        while (true) { val ch = input.read(); if (ch < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
                            if (ch == 10) return bytes.toString("UTF-8").trimEnd('\r'); bytes.write(ch) }
                    }
                    val first = line() ?: return@use
                    val headers = buildList { while (true) { val next = line() ?: break; if (next.isEmpty()) break; add(next) } }
                    val size = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val bytes = ByteArray(size); input.readFully(bytes)
                    requests += first + "\n" + headers.joinToString("\n")
                    val reply = handler(first.split(' ')[1], String(bytes, Charsets.UTF_8))
                    val data = reply.body.toByteArray()
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 ${reply.code} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${data.size}\r\n" +
                            reply.headers + "Connection: close\r\n\r\n").toByteArray()); write(data); flush()
                    }
                }
            } catch (e: Throwable) { if (!server.isClosed) failure.set(e) }
        }
        override fun close() { server.close(); worker.join(4000); check(!worker.isAlive); failure.get()?.let { throw AssertionError(it) } }
    }
}
