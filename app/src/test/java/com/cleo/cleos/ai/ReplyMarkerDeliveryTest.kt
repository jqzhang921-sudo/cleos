package com.cleo.cleos.ai

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReplyMarkerDeliveryTest {
    private val quiet = listOf("（保持安静）", "(保持安静)", "【保持安静】", "**（保持安静）**",
        "（保\u200B持安静）", "```text\n(保持安静)\n```", "\n（保持安静）。\n")

    @Test fun screenshotSilenceIsNotAMessageInEitherMode() {
        for (raw in quiet) for (wake in listOf(false, true)) {
            assertNull(raw, WakeOutput.message(raw, wake))
            val speech = WakeOutput.Speech(wake)
            for (ch in raw) assertEquals(raw, "", speech.append(ch.toString()))
            assertEquals("", speech.finish())
        }
        assertNull(WakeOutput.inspect("保持安静").text)
        assertNull(WakeOutput.inspect("你好。\n（保持安静）").text)
        assertEquals("本次选择保持安静", WakeOutput.inspect("（保持安静）").reason)
    }

    @Test fun ordinaryAdviceDiscussionAndLongRepliesRemainIntact() {
        for (raw in listOf("保持安静", "车上保持安静，到了叫我。", "他写了（保持安静），是什么意思？",
            "任务 #31 完成了。", "请看消息编号 #31 的内容。", "https://example.org/book#31",
            "我喜欢脸上没写表情的。\n\n" + "这是认真写给你的长信息。".repeat(100))) {
            assertEquals(raw, WakeOutput.message(raw, false))
            val speech = WakeOutput.Speech(false)
            val delivered = raw.map { speech.append(it.toString()) }.joinToString("") + speech.finish()
            assertEquals(raw, delivered)
        }
        assertEquals("车上保持安静，到了叫我。", WakeOutput.inspect("车上保持安静，到了叫我。").text)
    }

    @Test fun explicitQuotationIsAllowedOnlyInTheCurrentDirectTurn() {
        for (request in listOf("请原样输出（保持安静）", "解释保持安静的意思", "请复述（消息编号 #31）")) {
            assertTrue(request, WakeOutput.allowsLiteral(request))
            for (text in listOf("（保持安静）", "（消息编号 #31）"))
                assertEquals(text, WakeOutput.message(text, false, WakeOutput.allowsLiteral(request)))
        }
        for (request in listOf("怎么又发保持安静了", "不要再输出（保持安静）", "你好"))
            assertFalse(request, WakeOutput.allowsLiteral(request))
        assertNull(WakeOutput.message("（保持安静）", true, literalAllowed = true))
    }

    @Test fun screenshotLocatorHeadersAreRemovedWithoutSplittingOrRewritingTheBody() {
        val raw = "（消息编号 #31）\n好，你赢了。\n\n（消息编号 #32）\n打够了没？"
        val expected = "好，你赢了。\n\n打够了没？"
        for (wake in listOf(false, true)) {
            assertEquals(expected, WakeOutput.message(raw, wake))
            val speech = WakeOutput.Speech(wake)
            val delivered = raw.map { speech.append(it.toString()) }.joinToString("") + speech.finish()
            assertEquals(expected, delivered)
        }
        assertNull(WakeOutput.message("（消息编号 #31）", false))
        assertEquals("你好。", WakeOutput.message("(消息编号 ＃３１)\r\n你好。", false))
    }

    @Test fun everyLocatorPrefixIsBufferedAndOrdinaryParenthesesAreReleased() {
        val marker = "（消息编号 #31）"
        for (length in 1..marker.length) assertEquals("", WakeOutput.preview(marker.take(length)))
        for (raw in listOf("（消息编号为用户提供定位）", "(hello)", "消息编号 #31", "（消息编号 #31）是什么意思？")) {
            val speech = WakeOutput.Speech(false)
            assertEquals(raw, raw.map { speech.append(it.toString()) }.joinToString("") + speech.finish())
        }
    }

    @Test fun chineseControlToolIsRejectedBeforeVoiceExecutionInARealGroupLoop() = runBlocking {
        var requests = 0
        var executed = 0
        val initial = listOf(ApiMessage("user", "你好"))
        val result = GroupToolReply.reply(initial, listOf(ToolSpecs.sendVoice), false,
            { context, _, _ -> if (++requests == 1)
                flowOf(ChatEvent.ToolCalls(listOf(ToolCall("quiet", "send_voice", """{"text":"（保持安静）"}"""))))
                else {
                    assertEquals(WakeOutput.BLOCKED_TOOL, context.last().content)
                    flowOf(ChatEvent.Delta("你好呀。"))
                } }, { executed++; error("quiet marker must not reach TTS") }) {}
        assertEquals(0, executed)
        assertEquals("你好呀。", result)
    }

    @Test fun groupPreviewAndFinalBodyBothRemoveLocators() = runBlocking {
        val raw = "（消息编号 #31）\n好，你赢了。\n\n（消息编号 #32）\n打够了没？"
        val previews = mutableListOf<String>()
        val result = GroupToolReply.reply(listOf(ApiMessage("user", "你好")), emptyList(), false,
            { _, _, _ -> raw.toList().asFlow().map { ChatEvent.Delta(it.toString()) } }, { error("no tools") }) { previews += it }
        assertEquals("好，你赢了。\n\n打够了没？", result)
        assertTrue(previews.none { it.contains("消息编号") })
    }

    @Test fun groupVoiceExecutorReceivesOnlyAcceptedWords() = runBlocking {
        var requests = 0
        var executed = false
        GroupToolReply.reply(listOf(ApiMessage("user", "你好")), listOf(ToolSpecs.sendVoice), false,
            { _, _, _ -> if (++requests == 1) flowOf(ChatEvent.ToolCalls(listOf(
                ToolCall("voice", "send_voice", """{"text":"（消息编号 #31）\n你好。"}""")))) else emptyFlow() },
            { call ->
                assertEquals("你好。", ToolArgs.text(ToolArgs.parse(call.arguments)!!, "text"))
                executed = true
                ToolOutcome("已发送", "")
            }, hasSpeech = { executed }) {}
        assertTrue(executed)
    }
}
