package com.cleo.cleos.ui.chat

import org.junit.Assert.*
import org.junit.Test

class MessageNarrationTest {
    @Test fun separateAsideCanMixWithSpeechWithoutChangingSource() {
        val raw = "走吧。\n\n  （保持安静，不发出消息）  \n到了吱一声\n(stays silent)\n"
        val lines = MessageNarration.lines(raw)
        assertEquals(raw, lines.joinToString("\n") { it.text })
        assertEquals(listOf(false, false, true, false, true, false), lines.map { it.aside })
    }

    @Test fun ordinaryParenthesesReferencesAndMathRemainSpeech() {
        listOf("今天（周日）去散步", "（北京）是首都", "(1)", "（１２）", "(x)", "(x + y)",
            "（消息编号 #31）", "(https://example.com)", "（）", "(   )").forEach {
            assertFalse(it, MessageNarration.lines(it).single().aside)
        }
    }

    @Test fun onlyBalancedWholeLinesAreAsidesIncludingNestedParentheses() {
        assertTrue(MessageNarration.lines("（想了想（还是不说了））").single().aside)
        listOf("（还没写完", "（混用了括号)", "(闭合错了）", "（第一句）（第二句）", "（话）还有后文")
            .forEach { assertFalse(it, MessageNarration.lines(it).single().aside) }
    }

    @Test fun fencedCodeIsNotNarrationAndFenceTypeMustMatch() {
        val raw = "```text\n(stays silent)\n~~~\n（代码）\n```\n（旁白）\n~~~\n（代码）\n~~~"
        assertEquals(listOf(false, false, false, false, false, true, false, false, false),
            MessageNarration.lines(raw).map { it.aside })
    }

    @Test fun carriageReturnsAndEmptyLinesAreLossless() {
        val raw = "\r\n（轻声）\r\n\r\n正常 (备注)\r\n"
        assertEquals(raw, MessageNarration.lines(raw).joinToString("\n") { it.text })
        assertEquals(1, MessageNarration.lines(raw).count { it.aside })
    }
}
