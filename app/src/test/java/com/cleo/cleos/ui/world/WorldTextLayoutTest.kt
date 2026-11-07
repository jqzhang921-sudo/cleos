package com.cleo.cleos.ui.world

import com.cleo.cleos.data.WorldReading
import org.junit.Assert.*
import org.junit.Test

class WorldTextLayoutTest {
    private val legacy = "〔EPUB 阅读章节 2〕\n\n\n  \n目录\n\n\n\n扉页\n\n  \n第一部\n\n\n婆罗门之子\n\n" +
        "〔EPUB 阅读章节 3〕\n\n第二段\n\n\n\n这是一段真实原文😺。"
    @Test fun legacyTocIsCompactAndMarkersBecomeViewLabelsWithoutRewritingSource() {
        val fingerprint = WorldReading.fingerprint(legacy)
        val sections = WorldTextLayout.sections(legacy)
        assertEquals(listOf(2, 3), sections.map { it.number }); assertTrue(sections[0].toc); assertFalse(sections[1].toc)
        val parts = WorldTextLayout.parts(legacy, sections, false)
        assertEquals(listOf("阅读分段 2", "阅读分段 3"), parts.filter { it.heading }.map { it.text })
        assertEquals("目录\n扉页\n第一部\n婆罗门之子", parts.first { !it.heading }.text)
        assertFalse(parts.any { it.text.contains("〔EPUB") })
        assertEquals(fingerprint, WorldReading.fingerprint(legacy))
    }
    @Test fun sourceOffsetsStillLandOnTheQuotedTextAfterDisplayWhitespaceChanges() {
        val parts = WorldTextLayout.parts(legacy, WorldTextLayout.sections(legacy), false)
        val quoteAt = legacy.indexOf("真实原文")
        val part = parts[WorldTextLayout.indexAt(parts, quoteAt)]
        assertTrue(part.from <= quoteAt && part.to > quoteAt)
        assertTrue(part.text.contains("真实原文")); assertEquals("真实原文", legacy.substring(quoteAt, quoteAt + 4))
        val original = WorldTextLayout.parts(legacy, WorldTextLayout.sections(legacy), true)
        assertEquals(legacy, original.joinToString("") { it.text })
    }
    @Test fun plainTextAndUntrustedMarkerShapesStayLiteral() {
        for (body in listOf("普通文章\n〔EPUB 阅读章节 2〕\n原样文字", "〔EPUB 阅读章节 2000〕\ntext", "〔EPUB 阅读章节 2〕\none\n〔EPUB 阅读章节 1〕\ntwo")) {
            assertTrue(WorldTextLayout.sections(body).isEmpty())
            assertEquals(body, WorldTextLayout.parts(body, emptyList(), false).joinToString("") { it.text })
        }
    }
    @Test fun lineBreaksPoetryAndCodeIndentAreNotAllFlattenedToOneLine() {
        assertEquals("第一行\n第二行\n\n  code\n    nested", WorldTextLayout.compact("\n第一行\n第二行\n\n  \n  code\n    nested\n\n"))
        assertEquals("Line one\nLine two", WorldTextLayout.compact("Line one\r\nLine two\r\n"))
    }
    @Test fun longRawWhitespaceAndEmojiHaveBoundedChunksAndExactBacklinks() {
        val text = "〔EPUB 阅读章节 1〕\n" + "\n".repeat(5_000) + "x".repeat(2_999) + "😺" + "tail"
        val parts = WorldTextLayout.parts(text, WorldTextLayout.sections(text), true)
        assertEquals(text, parts.joinToString("") { it.text })
        for (p in parts) { assertTrue(p.text.count { it == '\n' } <= 80); assertTrue(WorldReading.boundary(text, p.from)); assertTrue(WorldReading.boundary(text, p.to)) }
        val preview = WorldTextLayout.sections("〔EPUB 阅读章节 1〕\n" + "x".repeat(79) + "😺").single().preview
        assertFalse(preview.last().isHighSurrogate())
    }
}
