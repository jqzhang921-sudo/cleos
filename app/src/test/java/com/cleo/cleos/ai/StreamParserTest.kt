package com.cleo.cleos.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamParserTest {
    private fun run(vararg payloads: String): Pair<List<ChatEvent>, List<ToolCall>> {
        val p = StreamParser()
        val events = payloads.flatMap { p.feed(it) } + p.finish()
        return events to p.toolCalls()
    }

    /** The events for a reply whose content comes in these pieces. */
    private fun content(vararg pieces: String): List<ChatEvent> =
        run(*pieces.map { """{"choices":[{"delta":{"content":${Json.encodeToString(it)}}}]}""" }.toTypedArray()).first

    @Test
    fun openAiStyleFragmentsAreJoinedByIndex() {
        val (events, calls) = run(
            """{"choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"add_todo","arguments":""}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"title\":"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"交报告\"}"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        assertTrue(events.isEmpty())
        assertEquals(listOf(ToolCall("call_1", "add_todo", """{"title":"交报告"}""")), calls)
    }

    @Test
    fun parallelCallsInterleavedStayApart() {
        val (_, calls) = run(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"list_todos","arguments":"{"}},{"index":1,"id":"b","function":{"name":"get_weather","arguments":"{\"ci"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"ty\":\"杭州\"}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}""",
        )
        assertEquals(
            listOf(ToolCall("a", "list_todos", "{}"), ToolCall("b", "get_weather", """{"city":"杭州"}""")),
            calls,
        )
    }

    @Test
    fun wholeCallsWithoutIndexAreTwoCallsWhenTheIdsDiffer() {
        val (_, calls) = run(
            """{"choices":[{"delta":{"tool_calls":[{"id":"x1","type":"function","function":{"name":"list_todos","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"id":"x2","type":"function","function":{"name":"get_weather","arguments":"{}"}}]}}]}""",
        )
        assertEquals(listOf("list_todos", "get_weather"), calls.map { it.name })
    }

    @Test
    fun aPieceWithoutIndexOrIdContinuesTheLastCall() {
        val (_, calls) = run(
            """{"choices":[{"delta":{"tool_calls":[{"id":"x1","function":{"name":"add_todo","arguments":"{\"title\""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":":\"买牛奶\"}"}}]}}]}""",
        )
        assertEquals(listOf(ToolCall("x1", "add_todo", """{"title":"买牛奶"}""")), calls)
    }

    @Test
    fun argumentsSentAsAnObjectBecomeText() {
        val (_, calls) = run(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"add_todo","arguments":{"title":"买菜"}}}]}}]}""",
        )
        assertEquals("""{"title":"买菜"}""", calls.single().arguments)
    }

    @Test
    fun aCallWithoutAnIdGetsOne() {
        val (_, calls) = run("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"list_todos","arguments":"{}"}}]}}]}""")
        assertEquals("call_0", calls.single().id)
    }

    @Test
    fun reasoningTextAndCallsInOneReply() {
        val (events, calls) = run(
            """{"choices":[{"delta":{"reasoning_content":"对方要记"}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"一件事","content":null}}]}""",
            """{"choices":[{"delta":{"content":"好，我记一下"}}]}""",
            """{"choices":[{"delta":{"content":"","tool_calls":[{"index":0,"id":"c","function":{"name":"add_todo","arguments":"{\"title\":\"a\"}"}}]}}]}""",
        )
        assertEquals(
            listOf(ChatEvent.Reasoning("对方要记"), ChatEvent.Reasoning("一件事"), ChatEvent.Delta("好，我记一下")),
            events,
        )
        assertEquals(1, calls.size)
    }

    @Test
    fun openRoutersReasoningIsShownButNotSentBack() {
        val (events, _) = run(
            """{"choices":[{"delta":{"reasoning":"先想想"}}]}""",
            // Both at once, with the same words: counted once.
            """{"choices":[{"delta":{"reasoning_content":"再想想","reasoning":"再想想"}}]}""",
            """{"choices":[{"delta":{"content":"好"}}]}""",
        )
        assertEquals(
            listOf(ChatEvent.Reasoning("先想想", sendBack = false), ChatEvent.Reasoning("再想想"), ChatEvent.Delta("好")),
            events,
        )
    }

    @Test
    fun aThinkBlockTheReplyOpensWithIsThinking() {
        // Both tags cut across pieces, and the blank lines before the answer dropped.
        assertEquals(
            listOf(ChatEvent.Reasoning("我想想", sendBack = false), ChatEvent.Reasoning("，好的", sendBack = false), ChatEvent.Delta("你好")),
            content("<th", "ink>我想想", "，好的</th", "ink>\n\n", "你好"),
        )
        assertEquals(
            listOf(ChatEvent.Reasoning("嗯", sendBack = false), ChatEvent.Delta("好")),
            content("\n<thinking>嗯</thinking>好"),
        )
        // Never closed: all of it was thinking, down to a last "<" held back for the closing tag.
        assertEquals(
            listOf(ChatEvent.Reasoning("想到一半", sendBack = false), ChatEvent.Reasoning("<", sendBack = false)),
            content("<think>想到一半<"),
        )
    }

    @Test
    fun textThatOnlyLooksLikeATagStaysText() {
        assertEquals(listOf(ChatEvent.Delta("<3 爱你")), content("<", "3 爱你"))
        assertEquals(listOf(ChatEvent.Delta("\n你好")), content("\n", "你好"))
        // Only a block at the very start counts.
        assertEquals(listOf(ChatEvent.Delta("你好<think>x</think>")), content("你好<think>x</think>"))
        // Held back to the end, whitespace is still what was said.
        assertEquals(listOf(ChatEvent.Delta("  ")), content("  "))
    }

    @Test
    fun nullToolCallsAndEmptyChunksAreHarmless() {
        val (events, calls) = run(
            """{"choices":[{"delta":{"content":"嗯","tool_calls":null}}]}""",
            """{"choices":[]}""",
            """not json at all""",
        )
        assertEquals(listOf(ChatEvent.Delta("嗯")), events)
        assertTrue(calls.isEmpty())
    }

    @Test(expected = ChatException::class)
    fun anErrorInTheStreamThrows() {
        StreamParser().feed("""{"error":{"message":"context too long"}}""")
    }

    @Test
    fun aRelayThatIgnoresStreamingSendsTheWholeReplyAsOneJson() {
        val events = StreamParser().wholeReply(
            """{"id":"x","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"你好呀"},"finish_reason":"stop"}]}""",
        )
        assertEquals(listOf(ChatEvent.Delta("你好呀")), events)
        // Pretty-printed over several lines, as it arrives line by line.
        val p = StreamParser()
        assertEquals(listOf(ChatEvent.Delta("嗯")), p.wholeReply("{\n \"choices\": [\n {\"message\": {\"content\": \"嗯\"}}\n ]\n}\n"))
    }

    @Test
    fun aWholeReplyThatCallsAToolKeepsTheCall() {
        val p = StreamParser()
        val events = p.wholeReply(
            """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"add_todo","arguments":"{\"title\":\"x\"}"}}]}}]}""",
        )
        assertTrue(events.isEmpty())
        assertEquals(listOf(ToolCall("call_1", "add_todo", """{"title":"x"}""")), p.toolCalls())
    }

    @Test
    fun anAnswerThatIsNothingLikeAReplySaysSo() {
        val empty = runCatching { StreamParser().wholeReply("  \n") }.exceptionOrNull() as ChatException
        assertTrue(empty.message!!.contains("什么内容都没回"))
        val page = runCatching { StreamParser().wholeReply("<html><body>Just a moment...</body></html>") }.exceptionOrNull() as ChatException
        assertTrue(page.message!!.contains("Just a moment"))
        val error = runCatching { StreamParser().wholeReply("""{"error":{"message":"model not found"}}""") }.exceptionOrNull() as ChatException
        assertTrue(error.message!!.contains("model not found"))
    }
}

class RequestBodyTest {
    private val call = ToolCall("call_1", "add_todo", """{"title":"交报告"}""")

    private fun body(messages: List<ApiMessage>, tools: List<ToolSpec> = emptyList()): JsonObject =
        Json.parseToJsonElement(requestBody("m", messages, tools).toString()).jsonObject

    @Test
    fun aCallWithoutWordsGoesBackWithNullContentAndItsResultFollows() {
        val b = body(
            listOf(
                ApiMessage("user", "记一下"),
                ApiMessage("assistant", "", listOf(call), reasoning = "想了想"),
                ApiMessage("tool", "已添加", toolCallId = "call_1"),
            ),
        )
        val msgs = b["messages"]!!.jsonArray
        val assistant = msgs[1].jsonObject
        assertEquals(JsonNull, assistant["content"])
        assertEquals("想了想", assistant["reasoning_content"]!!.jsonPrimitive.content)
        val fn = assistant["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        assertEquals("add_todo", fn["name"]!!.jsonPrimitive.content)
        assertEquals("""{"title":"交报告"}""", fn["arguments"]!!.jsonPrimitive.content)
        assertEquals("call_1", msgs[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertFalse("no tools offered", "tools" in b)
    }

    @Test
    fun plainMessagesCarryNoToolFields() {
        val m = body(listOf(ApiMessage("assistant", "早", reasoning = "ignored without calls")))["messages"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("role", "content"), m.keys)
    }

    @Test
    fun brokenArgumentsAreNotEchoed() {
        val b = body(listOf(ApiMessage("assistant", "", listOf(call.copy(arguments = """{"title":"交"""))), ApiMessage("tool", "x", toolCallId = "call_1")))
        val args = b["messages"]!!.jsonArray[0].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!
            .jsonObject["arguments"]!!.jsonPrimitive.content
        assertEquals("{}", args)
    }

    @Test
    fun offeredToolsAreListed() {
        val b = body(listOf(ApiMessage("user", "hi")), listOf(ToolSpecs.addTodo, ToolSpecs.getWeather))
        val names = b["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("add_todo", "get_weather"), names)
        val params = b["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals("title", params["required"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun thinkingIsAskedForAndEveryReplyCarriesItsReasoning() {
        // An earlier turn, then a turn still under way with a call and its result.
        val messages = listOf(
            ApiMessage("system", "s"),
            ApiMessage("user", "早"),
            ApiMessage("assistant", "早呀"),
            ApiMessage("user", "记一下"),
            ApiMessage("assistant", "", listOf(call), reasoning = "想了想"),
            ApiMessage("tool", "已添加", toolCallId = "call_1"),
        )
        val on = Json.parseToJsonElement(requestBody("m", messages, emptyList(), thinking = true).toString()).jsonObject
        assertEquals("enabled", on["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val sent = on["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("", sent[2]["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("想了想", sent[4]["reasoning_content"]!!.jsonPrimitive.content)
        assertFalse("reasoning_content" in sent[1])
        assertFalse("reasoning_content" in sent[5])
        // Without it, only the call in the turn under way carries its reasoning.
        val off = body(messages)
        assertFalse("thinking" in off)
        val plain = off["messages"]!!.jsonArray.map { it.jsonObject }
        assertFalse("reasoning_content" in plain[2])
        assertEquals("想了想", plain[4]["reasoning_content"]!!.jsonPrimitive.content)
        // Told not to think: the switch off, and no reasoning anywhere.
        val disabled = Json.parseToJsonElement(requestBody("deepseek-v4-flash", messages, emptyList(), thinking = false).toString()).jsonObject
        assertEquals("disabled", disabled["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(disabled["messages"]!!.jsonArray.none { "reasoning_content" in it.jsonObject })
        // One that always thinks, told to think little: no switch, and its reasoning goes back as without one.
        val little = Json.parseToJsonElement(requestBody("glm-5.3-flash", messages, emptyList(), effort = Thinking.LITTLE).toString()).jsonObject
        assertFalse("thinking" in little)
        assertEquals("low", little["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("想了想", little["messages"]!!.jsonArray[4].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun modelsThatThinkUnlessToldNotTo() {
        assertTrue(Thinking.canSwitchOff("deepseek-flash"))
        assertTrue(Thinking.canSwitchOff(" DeepSeek/deepseek-flash "))
        assertTrue(Thinking.canSwitchOff("deepseek-v4-flash"))
        assertTrue(Thinking.canSwitchOff(" DeepSeek-V4-Pro "))
        assertTrue(Thinking.canSwitchOff("deepseek/deepseek-v4-flash"))
        assertTrue(Thinking.canSwitchOff("glm-4.6"))
        assertFalse(Thinking.canSwitchOff("deepseek-chat"))
        assertFalse(Thinking.canSwitchOff("gpt-4o-mini"))
        assertFalse(Thinking.canSwitchOff("glm-4-flash"))
        assertTrue(Thinking.canSwitchOff("glm-5.2"))
        assertTrue(Thinking.canSwitchOff("glm-4.7-flash"))
        // From GLM-5.3 on thinking can't be switched off; they are told to think little instead.
        assertFalse(Thinking.canSwitchOff("glm-5.3-flash"))
        assertTrue(Thinking.thinksAlways("glm-5.3"))
        assertTrue(Thinking.thinksAlways("zai/glm-5.3-flashx"))
        assertFalse(Thinking.thinksAlways("glm-5.2"))
        assertFalse(Thinking.canSwitchOff("mock"))
    }

    @Test
    fun deepSeekToolRequestsCarryReasoningEvenOnEarlierPlainReplies() {
        val messages = listOf(ApiMessage("assistant", "old", reasoning = "old reasoning"), ApiMessage("user", "new"),
            ApiMessage("assistant", "", listOf(call), reasoning = "new reasoning"))
        for (thinking in listOf<Boolean?>(null, true)) {
            val sent = requestBody("deepseek-flash", messages, listOf(ToolSpecs.addTodo), thinking)["messages"]!!.jsonArray
            assertEquals("old reasoning", sent[0].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
            assertEquals("new reasoning", sent[2].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        }
        val off = requestBody("deepseek-flash", messages, listOf(ToolSpecs.addTodo), thinking = false)["messages"]!!.jsonArray
        assertTrue(off.none { "reasoning_content" in it.jsonObject })
    }
}
