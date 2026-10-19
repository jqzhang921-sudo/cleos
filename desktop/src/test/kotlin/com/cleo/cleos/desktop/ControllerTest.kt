package com.cleo.cleos.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Files

internal class MemoryVault : KeyVault {
    private val keys = mutableMapOf<String,String>()
    override fun get(address: String) = keys[keyAddress(address)].orEmpty()
    override fun put(address: String,key: String) { keys[keyAddress(address)]=key }
}

class ControllerTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `actual group round sends distinct models and prior member response reaches next model`() {
        val requests=mutableListOf<JsonObject>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/v1/chat/completions") { e ->
            val request=Json.parseToJsonElement(e.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
            synchronized(requests) { requests.add(request) }
            val content=if(request["model"]!!.jsonPrimitive.content=="model-a") "来自 A 的消息" else "B 看到了 A"
            val response="""{"choices":[{"message":{"content":"$content"},"finish_reason":"stop"}]}""".toByteArray()
            e.responseHeaders.add("Content-Type","application/json");e.sendResponseHeaders(200,response.size.toLong());e.responseBody.use { it.write(response) }
        };server.start()
        val directory=temp.newFolder().toPath()
        try { runBlocking(Dispatchers.Swing) {
            val vault=MemoryVault();val vm=DesktopController(LocalStore(directory),vault)
            try {
                val a=vm.data.companions.first().copy(name="A",endpoint=Endpoint("http://127.0.0.1:${server.address.port}/v1","model-a",true))
                assertTrue(vm.configure(a,"fixture-a",false))
                val b=vm.addCompanion("B").copy(endpoint=a.endpoint.copy(model="model-b"))
                assertTrue(vm.configure(b,"fixture-a",false));assertTrue(vm.createGroup("测试群",listOf(a.id,b.id)))
                vm.draft("你好大家");vm.send()
                withTimeout(5000) { while(vm.busyThread!=null) delay(20) }
                assertEquals(3,vm.selected.messages.size)
                assertEquals(listOf("model-a","model-b"),requests.map { it["model"]!!.jsonPrimitive.content })
                val other=requests[1]["messages"]!!.jsonArray.map { it.jsonObject }.last()
                assertEquals("user",other["role"]!!.jsonPrimitive.content)
                assertEquals("[群消息 · A] 来自 A 的消息",other["content"]!!.jsonPrimitive.content)
                assertEquals("complete",vm.selected.messages.last().status)
            } finally { vm.close() }
        } } finally { server.stop(0) }
        LocalStore(directory).use { assertEquals("B 看到了 A",it.load().conversations.last().messages.last().text) }
        assertFalse(Files.readString(directory.resolve("library.json")).contains("fixture-a"))
    }
    @Test fun `no key leaves draft intact and no phantom sent message`() = runBlocking(Dispatchers.Swing) {
        val vm=DesktopController(LocalStore(temp.newFolder().toPath()),MemoryVault())
        try { vm.draft("还没配置");vm.send();assertEquals("还没配置",vm.selected.draft);assertTrue(vm.selected.messages.isEmpty());assertNull(vm.busyThread);assertTrue(vm.notice.contains("API Key")) }
        finally { vm.close() }
    }
    @Test fun `group membership enforces distinct existing members`() = runBlocking(Dispatchers.Swing) {
        val vm=DesktopController(LocalStore(temp.newFolder().toPath()),MemoryVault())
        try { val a=vm.data.companions.first();assertFalse(vm.createGroup("bad",listOf(a.id,a.id)));assertFalse(vm.createGroup("bad",listOf(a.id,"missing"))) }
        finally { vm.close() }
    }
}
