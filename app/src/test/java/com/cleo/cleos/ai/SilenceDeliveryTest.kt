package com.cleo.cleos.ai

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SilenceDeliveryTest {
    private val controls = listOf("SKIP", "skip", "[SKIP]", "（SKIP）", "**SKIP**", "`SKIP`",
        "\uFEFFS\u200BK\u2060I\u200DP", "ＳＫＩＰ", "S K I P", "- SKIP", "> SKIP", "• SKIP",
        "1. SKIP", "[1. SKIP]", "1. SKIP\n2. SKIP", "SKIP。 SKIP。".repeat(30), "SKIP🙂", "```text\nSKIP\n```",
        "```text\nSKIP", "SKIP\nSKIP")

    @Test fun decoratedAndInvisibleControlOnlyOutputsAreBlockedInBothChatModes() {
        for (raw in controls) for (proactive in listOf(false, true))
            assertNull(raw, WakeOutput.message(raw, proactive))
    }

    @Test fun everyControlPrefixStaysOutOfLivePreviewAndSpeech() {
        for (raw in controls) {
            val speech = WakeOutput.Speech(proactive = false)
            var assembled = ""
            for (piece in raw.map(Char::toString)) {
                assembled += piece
                assertEquals("preview: $assembled", "", WakeOutput.preview(assembled))
                assertEquals("speech: $assembled", "", speech.append(piece))
            }
            assertEquals("", speech.finish())
            assertEquals("", speech.text())
        }
    }

    @Test fun actualWordsNumbersLinksCodeAndExplanationsSurviveStreamingUnchanged() {
        for (raw in listOf("SKIPPER 是船长。", "这个单词叫 SKIP。", "SKIP 是什么意思？", "任务 #56 完成。",
            "https://example.org/skip#56", "123", "S", "SK", "SKIP𐐀", "1. Sky is blue", "```text\nhello\n```",
            "```kotlin\nval skipped = 2\n```", "  晚安。  ")) {
            val speech = WakeOutput.Speech(proactive = false)
            val delivered = raw.map { speech.append(it.toString()) }.joinToString("") + speech.finish()
            assertEquals(raw, delivered)
            assertEquals(raw, speech.text())
            assertEquals(raw, WakeOutput.message(raw, proactive = false))
        }
    }

    @Test fun intentionalLiteralQuotesRequireThePersonsActualRequest() {
        for (query in listOf("请只输出SKIP", "照着念 ‘SKIP’", "SKIP 是什么意思？", "Repeat exactly SKIP", "ＳＫＩＰ")) {
            assertTrue(query, WakeOutput.allowsLiteral(query))
            assertEquals("SKIP", WakeOutput.message("SKIP", proactive = false, literalAllowed = WakeOutput.allowsLiteral(query)))
        }
        for (query in listOf("不要再回复 SKIP", "怎么又发SKIP了", "帮我念 SKIPPER", "你好", "stop saying SKIP"))
            assertFalse(query, WakeOutput.allowsLiteral(query))
        assertNull(WakeOutput.message("SKIP", proactive = true, literalAllowed = true))
    }

    @Test fun literalSpeechStreamsNormallyWhenRequested() {
        val speech = WakeOutput.Speech(proactive = false, literalAllowed = true)
        assertEquals("SK", speech.append("SK"))
        assertEquals("IP", speech.append("IP"))
        assertEquals("", speech.finish())
        assertEquals("SKIP", speech.text())
    }

    @Test fun autonomousSpeechWaitsForTheWholeDecisionAndNeverSpeaksPrivateEchoes() {
        for (raw in controls + listOf("- SKIP：现在不想说", FreeTopicRules.instruction,
            "晚安。\nSKIP", "（这条不是对方发的，对方看不到。）今晚下雨了。")) {
            val speech = WakeOutput.Speech(proactive = true)
            for (piece in raw.chunked(3)) assertEquals("", speech.append(piece))
            assertEquals("", speech.finish())
            assertEquals("", speech.text())
        }
        val speech = WakeOutput.Speech(proactive = true)
        assertEquals("", speech.append("晚安，"))
        assertEquals("", speech.append("明天见。"))
        assertEquals("晚安，明天见。", speech.finish())
        assertEquals("", speech.finish())
    }

    @Test fun backgroundPromptsNoLongerTeachALiteralSilenceToken() {
        assertFalse(WakeOutput.CONTROL_RULE.contains("SKIP"))
        assertFalse(WakeOutput.BLOCKED_TOOL.contains("SKIP"))
        assertFalse(FreeTopicRules.instruction.contains("SKIP"))
        assertFalse(FollowUpRules.instruction.contains("SKIP"))
    }

    private val initial = listOf(ApiMessage("system", "synthetic group"), ApiMessage("user", "hello"))

    @Test fun groupControlOnlyReplyNeverFlashesAndEndsAsARetryableFailure() = runBlocking {
        val previews = mutableListOf<String>()
        val failure = runCatching {
            GroupToolReply.reply(initial, emptyList(), false,
                { _, _, _ -> flowOf(ChatEvent.Delta("SK"), ChatEvent.Delta("IP")) }, { error("no tool") }) { previews += it }
        }.exceptionOrNull()
        assertEquals(WakeOutput.BLOCKED_REPLY, failure?.message)
        assertTrue(previews.all { it.isEmpty() })
    }

    @Test fun groupVoiceControlIsRejectedBeforeSynthesisThenAllowsACorrectedReply() = runBlocking {
        var requests = 0
        var executed = 0
        var trace = GroupToolTrace()
        val result = GroupToolReply.reply(initial, listOf(ToolSpecs.sendVoice), false,
            { messages, _, _ ->
                if (++requests == 1) flowOf(ChatEvent.ToolCalls(listOf(ToolCall("voice", "send_voice", """{"text":"[SKIP]"}"""))))
                else {
                    assertEquals(WakeOutput.BLOCKED_TOOL, messages.last().content)
                    flowOf(ChatEvent.Delta("你好呀。"))
                }
            }, { executed++; error("must not synthesize") }, onTrace = { trace = it }) {}
        assertEquals("你好呀。", result)
        assertEquals(0, executed)
        assertEquals("rejected", trace.records.single().state)
    }

    @Test fun groupLiteralVoiceIsAllowedOnlyWithCurrentUserOptIn() = runBlocking {
        var requests = 0
        var executed = 0
        val result = GroupToolReply.reply(initial, listOf(ToolSpecs.sendVoice), false,
            { _, _, _ -> if (++requests == 1)
                flowOf(ChatEvent.ToolCalls(listOf(ToolCall("voice", "send_voice", """{"text":"SKIP"}"""))))
                else emptyFlow() }, { executed++; ToolOutcome("sent", "") }, hasSpeech = { executed == 1 }, literalAllowed = true) {}
        assertEquals("", result)
        assertEquals(1, executed)
    }

    @Test fun groupBlockedPreludeCannotContaminateTheNextToolRequest() = runBlocking {
        var requests = 0
        val result = GroupToolReply.reply(initial, listOf(ToolSpecs.getWeather), false,
            { messages, _, _ -> if (++requests == 1)
                flowOf(ChatEvent.Delta("SKIP"), ChatEvent.ToolCalls(listOf(ToolCall("weather", "get_weather", "{}"))))
                else {
                    assertEquals("", messages[messages.lastIndex - 1].content)
                    flowOf(ChatEvent.Delta("晴天。"))
                } }, { ToolOutcome("sunny", "") }) {}
        assertEquals("晴天。", result)
    }

    @Test fun finalControlDoesNotEraseEarlierValidTextOrDeliveredMedia() = runBlocking {
        for (withText in listOf(true, false)) {
            var requests = 0
            var hasPhoto = false
            val result = GroupToolReply.reply(initial, listOf(ToolSpecs.getWeather), false,
                { _, _, _ -> if (++requests == 1) flow {
                    if (withText) emit(ChatEvent.Delta("我去看看。"))
                    emit(ChatEvent.ToolCalls(listOf(ToolCall("weather", "get_weather", "{}"))))
                } else flowOf(ChatEvent.Delta("SK"), ChatEvent.Delta("IP")) },
                { hasPhoto = true; ToolOutcome("fixture result", "") }, hasPhotos = { hasPhoto }) {}
            assertEquals(if (withText) "我去看看。" else "", result)
            assertTrue(hasPhoto)
        }
    }
}
