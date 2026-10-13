package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class ChatCompatibilityTest {
    @Test fun requestAndParsedResponseHaveCorrelatedMetadataWithoutPayloads() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val logs = mutableListOf<String>()
        val reply = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"fixture","function":{"name":"add_todo","arguments":"{\"title\":\"private-tool-argument\"}"}}]},"finish_reason":"tool_calls"}]}""" + "\n\ndata: [DONE]\n\n"
        val c = client(mutableListOf(200 to reply), bodies, logs)
        val route = ApiEndpoint("https://relay.invalid/v1?token=private-url-token", "private-key", "claude-fixture")
        c.stream(route, listOf(ApiMessage("user", "private-conversation")), listOf(ToolSpecs.addTodo)).toList()
        val ids = logs.map { it.substringAfter("requestId=").substringBefore(' ') }.distinct()
        assertEquals(1, ids.size)
        assertTrue(logs.any { "route=" in it && "toolsPresent=true tools=1" in it })
        assertTrue(logs.any { "event=response status=200 calls=1" in it && "names=add_todo" in it })
        for (secret in listOf("private-key", "private-url-token", "private-conversation", "private-tool-argument", "https://relay")) assertFalse(secret, logs.any { secret in it })
    }
    private val endpoint = ApiEndpoint("https://api.deepseek.com", "synthetic-key", "deepseek-flash")
    private val specs = listOf(ToolSpecs.sendMessage, ToolSpecs.addTodo,
        ToolSpecs.addTodo.copy(name = "mcp_test_menu", groups = emptySet()))

    private fun error(message: String, param: String? = null): String = buildJsonObject {
        putJsonObject("error") {
            put("message", message)
            put("type", "invalid_request_error")
            put("param", param?.let(::JsonPrimitive) ?: JsonNull)
        }
    }.toString()

    private val textOnlyRefusal = error("messages.content.type 参数非法，取值范围 ['text']")

    @Test fun textOnlyContentEnumRefusalIsRecognizedForMessagePartsOnly() {
        assertEquals(RequestFeature.Images, ChatCompatibility.rejectedFeature(textOnlyRefusal))
        for (path in listOf("messages.content.type", "messages[0].content[1].type", "messages.0.content.1.type")) {
            assertEquals(path, RequestFeature.Images, ChatCompatibility.rejectedFeature(error("$path 参数非法，取值范围 [\"text\"]")))
            assertEquals(path, RequestFeature.Images, ChatCompatibility.rejectedFeature(error("Invalid value; allowed values: ['text']", path)))
        }
        for (message in listOf(
            "messages.content.type 参数非法，取值范围 ['text', 'image_url']",
            "messages.content.type 参数非法，取值范围 ['audio']",
            "tools.type 参数非法，取值范围 ['text']",
            "messages.content 参数非法，取值范围 ['text']",
            "messages.content.image_url 参数非法，取值范围 ['text']",
            "Invalid image_url: URL cannot be read",
            "Invalid messages.content.type; expected a valid image_url",
        )) assertNull(message, ChatCompatibility.rejectedFeature(error(message)))
    }

    @Test fun textOnlyEnumRefusalRequiresActualImagesAndAnEligibleStatus() {
        val refusal = ChatException("fixture", 400, rejectedFeature = ChatCompatibility.rejectedFeature(textOnlyRefusal))
        assertEquals(RequestFeature.Images, ChatCompatibility.fallback(refusal, true, true, false))
        assertNull(ChatCompatibility.fallback(refusal, true, false, false))
        for (status in listOf(401, 403, 429, 500)) {
            assertNull(ChatCompatibility.fallback(ChatException("fixture", status, rejectedFeature = RequestFeature.Images), true, true, false))
        }
    }

    @Test fun textOnlyRetryRemovesHistoricalImagesButPreservesWordsToolsAndAvatarReferences() = runBlocking {
        val history = listOf(
            MessageEntity(id = 1, conversationId = 1, role = "user", content = "这是新头像", createdAt = 1,
                images = MessageImages.encode(listOf(MessageImage("fixture.jpg", 20, 20)))),
            MessageEntity(id = 2, conversationId = 1, role = "assistant", content = "收到", createdAt = 2),
            MessageEntity(id = 3, conversationId = 1, role = "user", content = "记一下明天的待办", createdAt = 3),
        )
        val ta = CompanionEntity(id = 1, name = "fixture", apiBaseUrl = "", apiModel = "", createdAt = 0)
        val now = ZonedDateTime.now()
        fun messages(images: Boolean) = Prompt.messages(AppSettings(), ta, history, now, tools = setOf(ToolGroup.Todos), images = images)
            .map { m -> m.copy(images = m.images.map { "data:image/jpeg;base64,private-fixture" }) }
        val bodies = mutableListOf<JsonObject>()
        val logs = mutableListOf<String>()
        val c = client(mutableListOf(400 to textOnlyRefusal, 200 to "data: [DONE]\n\n"), bodies, logs)
        val first = messages(true)
        val failure = runCatching { c.stream(endpoint, first, specs).toList() }.exceptionOrNull() as ChatException
        assertEquals(RequestFeature.Images, ChatCompatibility.fallback(failure, specs.isNotEmpty(), first.any { it.images.isNotEmpty() }, false))
        val retry = messages(false)
        c.stream(endpoint, retry, specs).toList()
        assertEquals(2, bodies.size)
        assertTrue(bodies[0]["messages"]!!.jsonArray.any { it.jsonObject["content"] is kotlinx.serialization.json.JsonArray })
        assertTrue(bodies[1]["messages"]!!.jsonArray.all { it.jsonObject["content"] is JsonPrimitive })
        assertTrue(bodies.all { it["tools"]!!.jsonArray.size == specs.size })
        assertTrue(retry.any { "这是新头像" in it.content && "#1-1" in it.content && "你这边看不到图片" in it.content })
        assertTrue(retry.any { "记一下明天的待办" in it.content })
        assertEquals("fixture.jpg", MessageImages.decode(history[0].images).single().file)
        assertTrue(logs.any { "imageParts=1" in it && "category=unsupported_images" in it })
        assertTrue(logs.any { "imageParts=0" in it && "category=request" in it })
        assertFalse(logs.any { "private-fixture" in it || "这是新头像" in it || "记一下明天的待办" in it })
    }

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
        assertTrue(logs.single { "category=request" in it }.contains("tools=3"))
        assertTrue(logs.single { "category=request" in it }.contains("mcp=1"))
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

    @Test
    fun aClaudeRelayCanAcceptToolsAfterARejectedRequest() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val logs = mutableListOf<String>()
        val reply = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"fixture_call","type":"function","function":{"name":"add_todo","arguments":"{\"title\":\"fixture\"}"}}]},"finish_reason":"tool_calls"}]}""" + "\n\ndata: [DONE]\n\n"
        val c = client(mutableListOf(400 to error("No endpoints found that support tool use"), 200 to reply), bodies, logs)
        val relay = ApiEndpoint("https://relay.invalid/v1", "synthetic-key", "claude-fixture")
        val failure = runCatching { c.stream(relay, listOf(ApiMessage("user", "fixture")), specs).toList() }.exceptionOrNull() as ChatException
        assertEquals(RequestFeature.Tools, failure.rejectedFeature)
        val calls = c.stream(relay, listOf(ApiMessage("user", "fixture")), specs).toList().filterIsInstance<ChatEvent.ToolCalls>()
        assertEquals("add_todo", calls.single().calls.single().name)
        assertEquals("{\"title\":\"fixture\"}", calls.single().calls.single().arguments)
        assertEquals(2, bodies.size)
        assertTrue(bodies.all { it["tools"]!!.jsonArray.size == 3 })
    }

    private val schemaRefusal = """{"error":{"message":"Invalid JSON payload received. Unknown name \"dependentRequired\" at '***.tools[0].function_declarations[48].parameters': Cannot find field."}}"""
    private val dependentTool = ToolSpecs.addTodo.copy(name = "mcp_fixture", parameters = Json.parseToJsonElement(
        """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"string"}},"required":["a"],"dependentRequired":{"a":["b"]}}""").jsonObject)

    @Test fun explicitSchemaRejectionRetriesWithAllToolsStillPresent() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val logs = mutableListOf<String>()
        val response = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"fixture","function":{"name":"mcp_fixture","arguments":"{\"a\":\"yes\",\"b\":\"yes\"}"}}]},"finish_reason":"tool_calls"}]}""" + "\n\ndata: [DONE]\n\n"
        val c = client(mutableListOf(400 to schemaRefusal, 200 to response), bodies, logs)
        val toolList = listOf(ToolSpecs.sendMessage, dependentTool)
        val relay = ApiEndpoint("https://relay.invalid/v1", "synthetic-key", "Claude-opus-4-6")
        val events = c.stream(relay, listOf(ApiMessage("user", "fixture")), toolList).toList()
        assertEquals(2, bodies.size)
        assertTrue(bodies.all { it["tools"]!!.jsonArray.size == 2 })
        fun schema(body: JsonObject) = body["tools"]!!.jsonArray[1].jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertTrue("dependentRequired" in schema(bodies[0]))
        assertFalse("dependentRequired" in schema(bodies[1]))
        assertEquals(schema(bodies[0])["required"], schema(bodies[1])["required"])
        assertEquals(schema(bodies[0])["properties"], schema(bodies[1])["properties"])
        assertEquals("mcp_fixture", events.filterIsInstance<ChatEvent.ToolCalls>().single().calls.single().name)
        assertTrue("dependentRequired" in dependentTool.parameters)
        assertTrue(logs.any { "schemaRetry=dependentRequired tools=2" in it })
    }

    @Test fun aRepeatedSchemaRefusalStopsAfterOneRetry() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val c = client(mutableListOf(400 to schemaRefusal, 400 to schemaRefusal), bodies, mutableListOf())
        val e = runCatching { c.stream(endpoint, listOf(ApiMessage("user", "fixture")), listOf(dependentTool)).toList() }.exceptionOrNull() as ChatException
        assertEquals(2, bodies.size)
        assertNull(e.rejectedFeature)
        assertNull(ChatCompatibility.fallback(e, true, false, false))
    }

    @Test fun authFailuresAndErrorsWithoutAnAdaptableSchemaDoNotRetry() = runBlocking {
        for ((status, offered) in listOf(401 to listOf(dependentTool), 400 to specs)) {
            val bodies = mutableListOf<JsonObject>()
            val c = client(mutableListOf(status to schemaRefusal), bodies, mutableListOf())
            assertTrue(runCatching { c.stream(endpoint, listOf(ApiMessage("user", "fixture")), offered).toList() }.isFailure)
            assertEquals(1, bodies.size)
        }
    }
}
