package com.cleo.cleos.ai

import org.junit.Assert.*
import org.junit.Test

class WorldReaderTest {
    @Test fun readerChoosesBoundedLengthAndOldProvidersKeepDefault() {
        for (size in listOf(1500, 6000, 12000, 18000)) {
            val choice = WorldReader.decision("""{"article_id":2,"read_characters":$size}""", setOf(2), false)
            assertEquals(2L, choice.articleId); assertEquals(size, choice.readCharacters)
        }
        assertEquals(3000, WorldReader.decision("""{"article_id":2}""", setOf(2), false).readCharacters)
        assertNull(WorldReader.decision("""{"article_id":null}""", setOf(2), false).articleId)
    }
    @Test fun invalidLengthCannotCauseUnboundedRequestsOrAcknowledgePause() {
        for (value in listOf("1499", "18001", "-1", "2147483648", "1.8e4", "1500.0", "\"6000\"", "null", "true", "[]")) {
            try { WorldReader.decision("""{"article_id":2,"read_characters":$value}""", setOf(2), false); fail(value) } catch (_: IllegalArgumentException) { }
        }
        for (raw in listOf("""{"article_id":null,"read_characters":6000}""", """{"article_id":3,"read_characters":6000}""", """{"article_id":2,"read_characters":6000,"extra":"leak"}""")) {
            try { WorldReader.decision(raw, setOf(2), false); fail(raw) } catch (_: IllegalArgumentException) { }
        }
        try { WorldReader.decision("""{"article_id":2,"read_characters":6000}""", setOf(2), false, allowSpan = false); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun selectionCannotCrossShelfAndMayChooseSilence() {
        assertNull(WorldReader.choice("""{"article_id":null}""", setOf(1, 2)))
        assertEquals(2L, WorldReader.choice("""{"article_id":2}""", setOf(1, 2)))
        assertEquals(2L, WorldReader.choice("""{"article_id":"2"}""", setOf(1, 2)))
        assertEquals(2L, WorldReader.choice("""{"id":"2"}""", setOf(1, 2)))
        for (raw in listOf("""{"article_id":3}""", """{"article_id":"3"}""", """{"article_id":2,"send_message":"leak"}""",
            """{"id":2,"article_id":1}""", """{"article_id":true}""", """{"article_id":2.0}""", """{"article_id":"null"}""",
            """{"article_id":[2]}""", """{"article_id":"2""", "{}")) {
            try { WorldReader.choice(raw, setOf(1, 2)); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun publicNotesCanBeFreeformButQuotesMustMatchActualSegment() {
        val result = WorldReader.result("""{"action":"read","note":"随手画了一阵风。","question":"风从哪来？","quote":"树"}""", "春天的树。")!!
        assertEquals(3, result.quoteOffset); assertEquals("树", result.quote)
        assertNotNull(WorldReader.result("""{"action":"read","note":"","question":"","quote":""}""", "只是读一段"))
        for (raw in listOf("""{"action":"read","note":"你好","question":"","quote":"不存在的引语"}""", """{"action":"pause","note":"内部思考","question":"","quote":""}""", """{"action":"read","note":"正常", "question":"","quote":"", "reasoning":"private"}""")) {
            try { WorldReader.result(raw, "春天的树。"); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun pauseDoesNotAcknowledgeReadingAndTruncatedOutputCannotBeSaved() {
        assertNull(WorldReader.result("""{"action":"pause","note":"","question":"","quote":""}""", "正文"))
        try { WorldReader.result("""{"action":"read","note":"half""", "正文"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun quoteValidationRetainsExactForeignPunctuationAndInternalWhitespace() {
        val source = "Он сказал: «да»;\nпотом  ушёл."
        fun payload(quote: String) = kotlinx.serialization.json.buildJsonObject {
            put("action", kotlinx.serialization.json.JsonPrimitive("read")); put("note", kotlinx.serialization.json.JsonPrimitive("公开笔记"))
            put("question", kotlinx.serialization.json.JsonPrimitive("")); put("quote", kotlinx.serialization.json.JsonPrimitive(quote))
        }.toString()
        assertEquals(0, WorldReader.result(payload(source), source)!!.quoteOffset)
        for (changed in listOf(source.replace("  ", " "), source.replace('«', '"').replace('»', '"'), "他说：是的")) {
            try { WorldReader.result(payload(changed), source); fail() }
            catch (e: WorldReplyException) { assertEquals("reading_quote", e.code) }
        }
    }
}
