package com.cleo.cleos.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLinksTest {
    private fun urls(text: String) = ChatLinks.parts(text).mapNotNull { it.url }
    private fun shown(text: String) = ChatLinks.parts(text).joinToString("") { it.text }

    @Test fun orderQueriesAndFragmentsAreKeptExactly() {
        val link = "https://order.example.com/pay?order=42&sign=a%2Bb%3D&return=https%3A%2F%2Fexample.com#confirm"
        val text = "点这里：$link。"
        assertEquals(listOf(link), urls(text))
        assertEquals(text, shown(text))
    }

    @Test fun markdownLabelsOpenTheirTargetIncludingBalancedParentheses() {
        val url = "https://example.com/menu(a)?order=42"
        val text = "打开[查看订单]($url)，再看看[菜单](http://example.com/menu)。"
        assertEquals(listOf(url, "http://example.com/menu"), urls(text))
        assertEquals("打开查看订单，再看看菜单。", shown(text))
    }

    @Test fun enclosingAndSentencePunctuationIsNotPartOfTheUrl() {
        val text = "(https://example.com/a(b)), https://example.com/next!\n<https://example.com/final>"
        assertEquals(listOf("https://example.com/a(b)", "https://example.com/next", "https://example.com/final"), urls(text))
        assertEquals(text, shown(text))
    }

    @Test fun urlsInToolJsonDoNotIncludeQuotesOrBraces() {
        val text = """{"url":"https://example.com/order?id=42&token=a-b_c", "status":"ok"}"""
        assertEquals(listOf("https://example.com/order?id=42&token=a-b_c"), urls(text))
        assertEquals(text, shown(text))
    }

    @Test fun incompleteMarkdownStillLeavesTheActualUrlClickable() {
        val text = "[订单](https://example.com/order"
        assertEquals(listOf("https://example.com/order"), urls(text))
        assertEquals(text, shown(text))
    }

    @Test fun malformedUrlsAndExecutableSchemesStayPlain() {
        val text = "https:// https:///path [点我](javascript:alert(1)) file:///private https://example.com/%ZZ"
        assertTrue(urls(text).isEmpty())
        assertEquals(text, shown(text))
    }

    @Test fun ordinaryTextAndEmptyMessagesStayUnchanged() {
        listOf("", "只复制这句话\n其他内容不变", "【表情】今天吃什么？").forEach {
            assertEquals(it, shown(it))
            assertTrue(urls(it).isEmpty())
        }
    }
}
