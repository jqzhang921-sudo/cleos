package com.cleo.cleos.data

import com.cleo.cleos.ai.MemoryKinds
import org.junit.Assert.assertEquals
import org.junit.Test

/** Third-party memory files: a JSON array, one wrapped in an object, or plain text / Markdown. */
class ForeignMemoryTest {
    @Test
    fun aJsonArrayOfStringsIsReadAsProfileTopics() {
        val out = ForeignMemory.parse("""["喜欢喝咖啡","住在上海"]""")
        assertEquals(2, out.size)
        assertEquals(listOf("profile", "profile"), out.map { it.kind })
        assertEquals("喜欢喝咖啡", out[0].name)
        assertEquals("住在上海", out[1].name)
    }

    @Test
    fun aJsonObjectWrappingTheArrayKeepsNameAndDetails() {
        val out = ForeignMemory.parse(
            """{"memories":[{"name":"称呼","summary":"喜欢被叫小名","details":["a","b"]}]}""",
        )
        assertEquals(1, out.size)
        assertEquals("称呼", out[0].name)
        assertEquals("喜欢被叫小名", out[0].summary)
        assertEquals(2, out[0].details.size)
    }

    @Test
    fun plainTextSplitsOnBlankLinesAndBullets() {
        val out = ForeignMemory.parse("称呼: 喜欢被叫小名\n\n- 喜欢喝咖啡\n- 住在上海")
        assertEquals(3, out.size)
        assertEquals("称呼", out[0].name)
        assertEquals("喜欢喝咖啡", out[1].name)
        assertEquals("住在上海", out[2].name)
    }

    @Test
    fun oneLineWithNothingToSplitIsStillAMemory() {
        // No JSON reader would call this JSON, but a bare line of words reads as a literal: it is
        // a text file with one thing in it, not a file with nothing in it.
        val out = ForeignMemory.parse("喜欢喝咖啡")
        assertEquals(1, out.size)
        assertEquals("喜欢喝咖啡", out[0].name)
        assertEquals("喜欢喝咖啡", out[0].summary)
    }

    @Test
    fun aFileCannotWriteLongerThanTheMemoryKeeps() {
        // The summary is the one line the TA reads with every message, so a paragraph that
        // doesn't fit keeps its head there and the whole of it as details.
        val paragraph = "甲".repeat(400)
        val one = ForeignMemory.parse(paragraph).single()
        assertEquals(MemoryKinds.SUMMARY_MAX, one.summary.length)
        assertEquals(paragraph, one.details.joinToString(""))
        // A long name is cut the same way, and a long detail with it.
        val long = ForeignMemory.parse("""[{"name":"${"乙".repeat(90)}","summary":"短","details":["${"丙".repeat(900)}"]}]""").single()
        assertEquals(MemoryKinds.NAME_MAX, long.name.length)
        assertEquals(MemoryKinds.DETAIL_MAX, long.details.single().length)
        // Past how many details a topic holds, the rest are left out.
        val items = (1..80).joinToString(",", "[", "]") { "\"第${it}条\"" }
        val many = ForeignMemory.parse("""[{"name":"一条","summary":"短","details":$items}]""").single()
        assertEquals(MemoryKinds.DETAILS, many.details.size)
    }
}
