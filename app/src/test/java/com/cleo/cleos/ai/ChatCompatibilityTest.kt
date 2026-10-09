package com.cleo.cleos.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ChatCompatibilityTest {
    private val endpoint = ApiEndpoint("https://api.deepseek.com", "synthetic-key", "deepseek-flash")
    private val specs = listOf(ToolSpecs.sendMessage, ToolSpecs.addTodo,
        ToolSpecs.addTodo.copy(name = "mcp_test_menu", groups = emptySet()))

    private fun error(message: String, param: String? = null): String = """{"error":{"message":"$message","type":"invalid_request_error","param":${param?.let { "\"$it\"" } ?: "null"}}}"""

    @Test
    fun onlyExplicitCapabilityErrorsAllowDowngrading() {
        assertEquals(RequestFeature.Tools, ChatCompatibility.rejectedFeature(error("This model does not support tool calling")))
        assertEquals(RequestFeature.Tools, ChatCompatibility.rejectedFeature(error("Unsupported parameter: tools", "tools")))
        assertEquals(RequestFeature.Thinking, ChatCompatibility.rejectedFeature(error("Unknown parameter: thinking", "thinking")))
        assertEquals(RequestFeature.Images, ChatCompatibility.rejectedFeature(error("This model does not support image input")))
        for (message in listOf(
            "Missing reasoning_content field in the assistant message",
            "Invalid tool schema: properties must be an object",
            "Invalid arguments for tools",
            "No matching tool_call_id",
            "Model not found", "Insufficient balance", "Maximum context length exceeded",
            "Tool calls are not supported in thinking mode",
            "This model does not support tools with images",
            "Unsupported parameter: temperature when tools are enabled",
        )) assertNull(message, ChatCompatibility.rejectedFeature(error(message)))
        assertNull(ChatCompatibility.rejectedFeature("<html>does not support tools</html>"))
    }

    @Test
    fun fallbackRequiresBothExplicitRefusalAndAnOfferedFeature() {
        assertNull(ChatCompatibility.fallback(ChatException("bad history", 400), true, false, false))
        val unsupported = ChatException("unsupported", 400, rejectedFeature = RequestFeature.Tools)
        assertEquals(RequestFeature.Tools, ChatCompatibility.fallback(unsupported, true, false, false))
        assertNull(ChatCompatibility.fallback(unsupported, false, false, false))
        assertNull(ChatCompatibility.fallback(ChatException("busy", 503, rejectedFeature = RequestFeature.Tools), true, false, false))
        assertNull(ChatCompatibility.fallback(ChatException("bad switch", 400, rejectedFeature = RequestFeature.Thinking), true, false, false))
    }

    private fun client(responses: MutableList<Pair<Int, String>>, bodies: MutableList<JsonObject>, logs: MutableList<String>) = ChatClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            bodies += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val (code, content) = responses.removeAt(0)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(content.toResponseBody()).build()
        }.build(),
        diagnostic = { logs += it },
    )

    @Test
    fun flashExplicitlyDisablesThinkingAndKeepsInternalAndMcpTools() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val logs = mutableListOf<String>()
        val c = client(mutableListOf(200 to "data: [DONE]\n\n"), bodies, logs)
        c.stream(endpoint, listOf(ApiMessage("user", "synthetic")), specs).toList()
        assertEquals("disabled", bodies.single()["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("send_message", "add_todo", "mcp_test_menu"), bodies.single()["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue(logs.single().contains("tools=3"))
        assertTrue(logs.single().contains("mcp=1"))
    }

    @Test
    fun aHistory400NeitherRetriesNorRemembersARefusedThinkingSwitch() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val logs = mutableListOf<String>()
        val c = client(mutableListOf(400 to error("Missing reasoning_content field"), 200 to "data: [DONE]\n\n"), bodies, logs)
        val failure = runCatching { c.stream(endpoint, listOf(ApiMessage("user", "synthetic")), specs).toList() }.exceptionOrNull() as ChatException
        assertEquals(400, failure.status)
        assertNull(failure.rejectedFeature)
        assertNull(ChatCompatibility.fallback(failure, true, false, false))
        assertEquals(1, bodies.size)
        c.stream(endpoint, listOf(ApiMessage("user", "synthetic")), specs).toList()
        assertEquals(2, bodies.size)
        assertTrue(bodies.all { it["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content == "disabled" })
        assertTrue(bodies.all { it["tools"]!!.jsonArray.size == 3 })
        assertTrue(logs.any { "status=400" in it && "category=missing_reasoning_content" in it })
    }

    @Test
    fun anExplicitSwitchRejectionRetriesWithoutRemovingTools() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val logs = mutableListOf<String>()
        val c = client(mutableListOf(400 to error("Unknown parameter: thinking", "thinking"), 200 to "data: [DONE]\n\n"), bodies, logs)
        c.stream(endpoint, listOf(ApiMessage("user", "synthetic")), specs).toList()
        assertEquals(2, bodies.size)
        assertTrue("thinking" in bodies[0])
        assertFalse("thinking" in bodies[1])
        assertTrue(bodies.all { it["tools"]!!.jsonArray.size == 3 })
    }

    @Test
    fun onlyAnExplicitToolRejectionReachesTheToolFallback() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val logs = mutableListOf<String>()
        val c = client(mutableListOf(400 to error("This model does not support tools")), bodies, logs)
        val failure = runCatching { c.stream(endpoint, listOf(ApiMessage("user", "synthetic")), specs).toList() }.exceptionOrNull() as ChatException
        assertEquals(RequestFeature.Tools, ChatCompatibility.fallback(failure, true, false, false))
        assertEquals(1, bodies.size)
        assertTrue(logs.any { "category=unsupported_tools" in it })
    }

    @Test
    fun diagnosticsDoNotEchoSecretsOrConversationOrToolArguments() {
        val body = requestBody("deepseek-flash", listOf(ApiMessage("user", "private chat text"), ApiMessage("assistant", "", reasoning = "private thought")),
            specs, thinking = true)
        val raw = """{"error":{"message":"Missing reasoning_content: Bearer sk-secret; private chat text; https://host/?token=private-token","type":"private-type","code":"private-code","param":"private-param"}}"""
        val log = ChatCompatibility.diagnostic(body, 400, raw)
        for (secret in listOf("sk-secret", "private-token", "private chat text", "private thought", "Bearer", "https://", "private-type", "private-code", "private-param")) assertFalse(secret, secret in log)
        assertTrue("category=missing_reasoning_content" in log)
        assertTrue("send_message=true" in log)
    }
}
