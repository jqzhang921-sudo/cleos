package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.ZonedDateTime

class GroupToolsTest {
    private val initial = listOf(ApiMessage("system", "group fixture"), ApiMessage("user", "weather please"))
    private val specs = listOf(ToolSpecs.getWeather)
    private val call = ToolCall("weather1", "get_weather", """{"city":"Tokyo"}""")
    @Test fun onlyGroupReadToolsAndGalleryShareAreOfferedEvenWhenPrivateAndMcpToolsAreEnabled() {
        val names = GroupToolRules.offered(AppSettings(tools = ToolGroup.entries.toSet())).map { it.name }
        assertEquals(listOf("get_weather", "read_feed", "search_photos", "share_photo"), names)
        assertTrue(GroupToolRules.offered(AppSettings(tools = emptySet())).isEmpty())
        assertEquals(listOf("search_photos", "share_photo"), GroupToolRules.offered(AppSettings(tools = setOf(ToolGroup.Photos))).map { it.name })
    }
    @Test fun roundTripPairsToolIdsAndReasoningAndKeepsPreludeSeparateFromHiddenThought() = runBlocking {
        val requests = mutableListOf<List<ApiMessage>>()
        val traces = mutableListOf<GroupToolTrace>()
        val stages = mutableListOf<GroupReplyStage>()
        var shown = ""
        val result = GroupToolReply.reply(initial, specs, true, { messages, offered, thinking ->
            assertTrue(thinking); assertEquals(specs, offered); requests += messages
            if (requests.size == 1) flowOf(ChatEvent.Reasoning("private reasoning"), ChatEvent.Delta("Let me check"), ChatEvent.ToolCalls(listOf(call)))
            else flowOf(ChatEvent.Delta("Sunny"))
        }, { assertEquals(call, it); ToolOutcome("sunny fixture", "weather returned") }, onTrace = { traces += it }, onStage = { stages += it }) { shown = it }
        assertEquals("Let me check\n\nSunny", result); assertEquals(result, shown)
        assertEquals("private reasoning", requests[1][2].reasoning)
        assertEquals(listOf(call), requests[1][2].toolCalls)
        assertEquals(ApiMessage("tool", "sunny fixture", toolCallId = call.id), requests[1].last())
        assertEquals(listOf("running", "returned"), traces.map { it.records.single().state })
        assertTrue(GroupReplyStage.TOOLS in stages); assertFalse(shown.contains("private reasoning"))
    }
    @Test fun refusedToolsAffectOnlyThisReplyAndNeverPermanentlyDisableTheNextReply() = runBlocking {
        val offeredNames = mutableListOf<List<String>>()
        var attempts = 0
        var trace = GroupToolTrace()
        val stream: (List<ApiMessage>, List<ToolSpec>, Boolean) -> Flow<ChatEvent> = { messages, tools, _ -> flow {
            offeredNames += tools.map { it.name }
            if (++attempts == 1) throw ChatException("refused", 400, rejectedFeature = RequestFeature.Tools)
            if (attempts == 2) assertTrue(messages.first().content.contains("没有可用工具"))
            emit(ChatEvent.Delta("text only"))
        } }
        repeat(2) { GroupToolReply.reply(initial, specs, false, stream, { error("must not execute") }, onTrace = { trace = it }) {} }
        assertEquals(listOf(listOf("get_weather"), emptyList<String>(), listOf("get_weather")), offeredNames)
        assertTrue(trace.notice.contains("下一轮"))
    }
    @Test fun schemaOrNetworkErrorsAndPartialRefusalsAreNotMislabeledAsNoTools() = runBlocking {
        for ((code, feature, partial) in listOf(Triple(400, null, false), Triple(503, RequestFeature.Tools, false), Triple(400, RequestFeature.Tools, true))) {
            var requests = 0
            try {
                GroupToolReply.reply(initial, specs, false, { _, _, _ -> flow {
                    requests++; if (partial) emit(ChatEvent.Delta("partial"))
                    throw ChatException("synthetic failure", code, rejectedFeature = feature)
                } }, { error("must not execute") }) {}; fail()
            } catch (_: ChatException) { }
            assertEquals(1, requests)
        }
    }
    @Test fun writesUnknownAndBadJsonAreReturnedAsRejectionsWithoutCallingExecutor() = runBlocking {
        val forbidden = listOf(ToolCall("a", "memory", "{}"), ToolCall("b", "share_photo", "{}"), ToolCall("c", "get_weather", "[]"))
        var requests = 0
        var trace = GroupToolTrace()
        GroupToolReply.reply(initial, specs, false, { messages, _, _ ->
            if (++requests == 1) flowOf(ChatEvent.ToolCalls(forbidden)) else {
                assertEquals(3, messages.count { it.role == "tool" }); flowOf(ChatEvent.Delta("No operation"))
            }
        }, { error("forbidden executor") }, onTrace = { trace = it }) {}
        assertEquals(listOf("rejected", "rejected", "rejected"), trace.records.map { it.state })
        assertTrue(GroupToolRules.validate(GroupToolRules.encode(trace)))
    }
    @Test fun repeatedIdOrOversizedBatchIsRejectedBeforeAnotherExecution() = runBlocking {
        var executions = 0
        var requests = 0
        try {
            GroupToolReply.reply(initial, specs, false, { _, _, _ -> requests++; flowOf(ChatEvent.ToolCalls(listOf(call))) },
                { executions++; ToolOutcome("ok", "") }) {}; fail()
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("编号重复")) }
        assertEquals(1, executions); assertEquals(2, requests)
        for (calls in listOf(List(7) { call.copy(id = "c$it") }, listOf(call, call), listOf(call.copy(arguments = "x".repeat(4097))))) {
            try { GroupToolReply.reply(initial, specs, false, { _, _, _ -> flowOf(ChatEvent.ToolCalls(calls)) }, { error("batch must not execute") }) {}; fail() }
            catch (_: IllegalStateException) { }
        }
    }
    @Test fun roundLimitBoundsRequestsAndHugeResultsAreClippedBeforeReturningToModel() = runBlocking {
        var requests = 0
        var executions = 0
        try {
            GroupToolReply.reply(initial, specs, false, { messages, _, _ ->
                if (requests > 0) assertTrue(messages.last().content.length <= GroupToolRules.MAX_RESULT)
                flowOf(ChatEvent.ToolCalls(listOf(call.copy(id = "c${++requests}"))))
            }, { executions++; ToolOutcome("x".repeat(20_000), "") }) {}; fail()
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("次数上限")) }
        assertEquals(3, executions); assertEquals(4, requests)
    }
    @Test fun executorErrorIsKeptAsFailureAndReturnedToModelWithoutSecretExceptionProse() = runBlocking {
        var trace = GroupToolTrace()
        var requests = 0
        GroupToolReply.reply(initial, specs, false, { messages, _, _ ->
            if (++requests == 1) flowOf(ChatEvent.ToolCalls(listOf(call))) else {
                assertTrue(messages.last().content.contains("没有取得结果")); assertFalse(messages.last().content.contains("fixture-secret"))
                flowOf(ChatEvent.Delta("Sorry, query failed"))
            }
        }, { throw Exception("fixture-secret") }, onTrace = { trace = it }) {}
        assertEquals("failed", trace.records.single().state)
    }
    @Test fun cancellationDuringToolDoesNotRunLaterToolsOrFollowupRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var executions = 0
        var requests = 0
        var trace = GroupToolTrace()
        val job = launch {
            GroupToolReply.reply(initial, specs, false, { _, _, _ -> requests++; flowOf(ChatEvent.ToolCalls(listOf(call, call.copy(id = "b")))) },
                { executions++; started.complete(Unit); awaitCancellation() }, onTrace = { trace = it }) {}
        }
        started.await(); job.cancelAndJoin()
        assertEquals(1, executions); assertEquals(1, requests); assertEquals("running", trace.records.single().state)
    }
    @Test fun totalDeadlineIncludesToolAndNextStreamAndRetainsSpokenPartialText() = runBlocking {
        for (hangInTool in listOf(true, false)) {
            var requests = 0
            var shown = ""
            try {
                GroupToolReply.reply(initial, specs, false, { _, _, _ ->
                    if (++requests == 1) flowOf(ChatEvent.Delta("checking"), ChatEvent.ToolCalls(listOf(call)))
                    else flow { awaitCancellation() }
                }, { if (hangInTool) awaitCancellation() else ToolOutcome("ok", "") }, timeoutMillis = 60) { shown = it }; fail()
            } catch (_: GroupReplyTimeoutException) { }
            assertEquals("checking", shown)
        }
    }
    @Test fun anotherMembersResultsKeepTheAuthorAndNeverBecomeCurrentMembersToolHistory() {
        val ta = CompanionEntity(id = 10, name = "A", apiBaseUrl = "https://example.invalid", apiModel = "fixture", createdAt = 0)
        val members = listOf(GroupMemberEntity(1, 10, 0, "A"), GroupMemberEntity(1, 20, 1, "B"))
        val trace = GroupToolRules.encode(GroupToolTrace(listOf(GroupToolRecord("c", "get_weather", "{}", "returned", "sunny"))))
        val row = GroupMessageEntity(2, 1, "assistant", "B answered", 0, speakerId = 20, speakerName = "B", toolTrace = trace)
        val request = GroupChatRules.request(ChatGroupEntity(1, "group", 0, 0), members, ta, listOf(row), "user", emptyList(), ZonedDateTime.now(), tools = specs)
        assertEquals("user", request.last().role); assertTrue(request.last().content.contains("成员 20"))
        assertTrue(request.last().content.contains("sunny")); assertTrue(request.all { it.toolCalls.isEmpty() && it.toolCallId == null })
        assertTrue(request.first().content.contains("get_weather")); assertTrue(request.first().content.contains("没有私人记忆"))
    }
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("private DAO must not run: ${method.name}")
    } as T
    @Test fun wrapperRechecksSettingsAndDeniesPrivateWritesBeforeTouchingAnyDao() = runBlocking {
        var weatherCalls = 0
        val box = ToolBox(unused<TodoDao>(), unused<DiaryDao>(), object : WeatherSource {
            override suspend fun report(city: String, days: Int): WeatherReport { weatherCalls++; return WeatherReport(city, "sunny") }
        })
        var settings = AppSettings(tools = ToolGroup.entries.toSet())
        val tools = GroupTools(box) { settings }
        assertTrue(tools.run(ToolCall("private", "write_diary", "{}"), 1, 20).result.contains("没有执行"))
        assertEquals("sunny", tools.run(call, 1, 20).result)
        settings = settings.copy(tools = emptySet())
        assertTrue(tools.run(call, 1, 20).result.contains("没有执行")); assertEquals(1, weatherCalls)
    }
    @Test fun groupPhotoSearchNeverOffersAContradictoryPrivateShareInstruction() = runBlocking {
        val source = object : PhotoSource {
            override suspend fun search(query: String): List<PhotoCandidate> = emptyList()
            override suspend fun download(candidate: PhotoCandidate): DownloadedPhoto = error("never download")
        }
        val box = ToolBox(unused<TodoDao>(), unused<DiaryDao>(), object : WeatherSource {
            override suspend fun report(city: String, days: Int): WeatherReport = error("never weather")
        }, photos = PhotoBook(source))
        val tools = GroupTools(box) { AppSettings(tools = setOf(ToolGroup.Photos)) }
        val result = tools.run(ToolCall("photo", "search_photos", """{"query":"cat"}"""), 1, 20)
        assertTrue(result.result.contains("本群真实候选")); assertFalse(result.result.contains("没有发送图片工具"))
        assertNull(result.photo)
    }
}
