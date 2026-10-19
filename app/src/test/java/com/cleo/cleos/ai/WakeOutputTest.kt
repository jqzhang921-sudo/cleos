package com.cleo.cleos.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WakeOutputTest {
    @Test fun plainDecoratedAndRepeatedControlResultsStaySilent() {
        for (raw in listOf("SKIP", "skip：已经聊过了", " [SKIP] ", "（SKIP）", "**SKIP**",
            "`SKIP`", "\uFEFFSKIP", "SKIP（已经聊过）", "```text\nSKIP\n```", "SKIP。 SKIP。".repeat(200))) {
            assertNull(raw.take(40), WakeOutput.message(raw, proactive = true))
        }
    }

    @Test fun aControlLineCannotHideAfterAnOtherwiseNormalGreeting() {
        assertNull(WakeOutput.message("晚安。\nSKIP\nSKIP。", proactive = true))
    }

    @Test fun theScreenshotBoilerplateIsBlockedEvenWithAChangedLayout() {
        val echo = FreeTopicRules.instruction.lineSequence().take(5).joinToString("\n\n")
        assertNull(WakeOutput.inspect(echo).text)
        assertNull(WakeOutput.inspect("我想了一下：" + echo.replace("，", "、").replace("。", "！")).text)
        assertNull(WakeOutput.inspect(FollowUpRules.instruction).text)
        assertNull(WakeOutput.inspect(WakeOutput.CONTROL_RULE).text)
    }

    @Test fun aShortCopiedBackgroundMarkerIsAlsoPrivate() {
        assertNull(WakeOutput.inspect("（这条不是对方发的，对方看不到。）\n今天下雨了。").text)
    }

    @Test fun blockedReasonsNeverPersistTheModelsPrivateSuffix() {
        val result = WakeOutput.inspect("SKIP：合成的私密内容，不应进入状态记录")
        assertNull(result.text)
        assertEquals("本次选择保持安静", result.reason)
        assertFalse(result.reason.contains("合成的私密内容"))
    }

    @Test fun realMessagesIdsLinksAndDiscussionOfTheWordArePreserved() {
        for (text in listOf("今天看到一朵很好看的云，想给你看看。", "任务 #56 完成了。",
            "看看 https://example.org/book#56", "这个单词叫 SKIP，意思是跳过。", "SKIPPER 是船长。")) {
            assertEquals(text, WakeOutput.message(text, proactive = true))
        }
    }

    @Test fun ordinaryChatCanLiterallyDiscussOrQuoteSkipAndInstructions() {
        for (text in listOf("SKIP", "SKIP 是什么意思？", "#56", FreeTopicRules.instruction)) {
            assertEquals(text, WakeOutput.message(text, proactive = false, literalAllowed = true))
        }
    }

    @Test fun emptyDecisionsAreSilent() {
        assertNull(WakeOutput.inspect(" \n ").text)
        assertNull(WakeOutput.message("", proactive = true))
        assertEquals("", WakeOutput.message("", proactive = false))
    }

    @Test fun fragmentedSseReasoningAndBodyDoNotBecomeABackgroundMessage() {
        val parser = StreamParser()
        val privateThought = FreeTopicRules.instruction + "\n" + "SKIP。".repeat(150)
        val events = parser.feed("""{"choices":[{"delta":{"reasoning_content":${Json.encodeToString(privateThought)}}}]}""") +
            parser.feed("""{"choices":[{"delta":{"content":"SK"}}]}""") +
            parser.feed("""{"choices":[{"delta":{"content":"IP"},"finish_reason":"stop"}]}""") + parser.finish()
        val body = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertEquals("SKIP", body)
        assertEquals(privateThought, events.filterIsInstance<ChatEvent.Reasoning>().single().text)
        assertNull(WakeOutput.message(body, proactive = true))
        assertNull(WakeOutput.message(body, proactive = false))
        assertEquals("SKIP", WakeOutput.message(body, proactive = false, literalAllowed = true))
    }

    @Test fun messageAndVoiceArgumentsUseTheSameGateAfterSseToolAssembly() {
        for (name in listOf("send_message", "send_voice")) {
            val parser = StreamParser()
            parser.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"fixture","function":{"name":"$name","arguments":"{\"text\":\"SK"}}]}}]}""")
            parser.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"IP\"}"}}]},"finish_reason":"tool_calls"}]}""")
            val call = parser.toolCalls().single()
            assertEquals(name, call.name)
            val words = ToolArgs.text(ToolArgs.parse(call.arguments)!!, "text")!!
            assertNull(WakeOutput.message(words, proactive = true))
            assertNull(WakeOutput.message(words, proactive = false))
        }
    }
}
