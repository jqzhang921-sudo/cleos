package com.cleo.cleos.data

import com.cleo.cleos.data.db.WorldArticleEntity
import org.junit.Assert.*
import org.junit.Test

class WorldPastedTextTest {
    @Test fun pastedTextKeepsProvenanceAndNormalizesOnlyLineEndingsAndOuterWhitespace() {
        val result = WorldPastedText.prepare("  A place  ", " https://example.org/place ", "\r\nFirst 🗺️\r\n\nSecond\tline\r\n")
        assertEquals("A place", result.title)
        assertEquals("用户粘贴文字 · https://example.org/place", result.source)
        assertEquals("First 🗺️\n\nSecond\tline", result.body)
    }
    @Test fun linkAloneCannotBeMistakenForFetchedBody() {
        for (link in listOf("https://example.org/a", "HTTP://example.org/?a=1&b=2")) {
            rejected { WorldPastedText.prepare("Title", "", link) }
        }
        assertEquals("A text with https://example.org/a", WorldPastedText.prepare("Title", "", "A text with https://example.org/a").body)
    }
    @Test fun invalidMetadataAndBrokenUnicodeAreRejectedWithoutTruncatingBody() {
        rejected { WorldPastedText.prepare(" ", "", "Body") }
        rejected { WorldPastedText.prepare("x".repeat(121), "", "Body") }
        rejected { WorldPastedText.prepare("Title", "x".repeat(481), "Body") }
        rejected { WorldPastedText.prepare("Title\nline", "", "Body") }
        for (bad in listOf("\uD800", "\uDC00", "a\u0000b", "a\u0007b")) rejected { WorldPastedText.prepare("Title", "", bad) }
        rejected { WorldPastedText.prepare("Title", "", "x".repeat(WorldReading.MAX_TEXT + 1)) }
        assertEquals(WorldReading.MAX_TEXT, WorldPastedText.prepare("Title", "", "x".repeat(WorldReading.MAX_TEXT)).body.length)
    }
    @Test fun pastedArticleUsesExistingStudyBackupRulesAndCannotImpersonateEpubMetadata() {
        val text = WorldPastedText.prepare("Title", "EPUB 提取文字 · false.epub", "A small piece of text.")
        val a = WorldArticleEntity(1, 7, text.title, text.source, text.body, WorldReading.fingerprint(text.body), 1)
        assertFalse(a.source.startsWith("EPUB 提取文字"))
        assertEquals("study", a.space); assertTrue(a.originUrl.isEmpty()); assertTrue(a.searchQuery.isEmpty())
        WorldReading.validateBackup(setOf(7), listOf(a), emptyList())
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid pasted input was accepted") } catch (_: IllegalArgumentException) { }
    }
}
