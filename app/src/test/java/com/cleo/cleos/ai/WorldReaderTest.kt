package com.cleo.cleos.ai

import org.junit.Assert.*
import org.junit.Test

class WorldReaderTest {
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
}
