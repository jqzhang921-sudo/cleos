package com.cleo.cleos.data

import com.cleo.cleos.data.db.WorldArticleEntity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException

class WorldDocumentImportTest {
    private fun epub(extra: Map<String, String> = emptyMap(), raw: Map<String, ByteArray> = emptyMap(), block: (File) -> Unit) {
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""",
            // Deliberately opposite ZIP/spine order, plus a non-content resource.
            "OEBPS/two.xhtml" to "<html xmlns='http://www.w3.org/1999/xhtml'><body><h1>第二章</h1><p>第二段</p><script>do not execute or read</script></body></html>",
            "OEBPS/one.xhtml" to "<html xmlns='http://www.w3.org/1999/xhtml'><body><p>第一段😺</p></body></html>",
            "OEBPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>合成书</dc:title><dc:creator>合成作者</dc:creator></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""",
        ).apply { putAll(extra) }
        val file = File.createTempFile("world-book-test", ".epub")
        try {
            ZipOutputStream(file.outputStream()).use { out -> entries.forEach { (name, body) -> out.putNextEntry(ZipEntry(name)); out.write(raw[name] ?: body.toByteArray()); out.closeEntry() } }
            block(file)
        } finally { file.delete() }
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Invalid book accepted") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }
    @Test fun textAcceptsUtf8AndBomUtf16WithoutGuessingOrTruncation() {
        val raw = "书页😺\r\n第二行"
        for (bytes in listOf(raw.toByteArray(), byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + raw.toByteArray(), byteArrayOf(0xff.toByte(), 0xfe.toByte()) + raw.toByteArray(Charsets.UTF_16LE))) {
            val parsed = WorldDocumentImport.text(bytes, "自己选的书.txt")
            assertEquals("自己选的书", parsed.title); assertEquals("书页😺\n第二行", parsed.content)
        }
        rejects { WorldDocumentImport.text(byteArrayOf(0xff.toByte()), "bad.txt") }
        rejects { WorldDocumentImport.text("\u0000".toByteArray(), "binary.txt") }
        rejects { WorldDocumentImport.text(" ".toByteArray(), "empty.txt") }
        rejects { WorldDocumentImport.text("字".repeat(WorldReading.MAX_TEXT + 1).toByteArray(), "large.txt") }
    }
    @Test fun epubUsesSpineOrderMetadataAndOnlyReadableBody() = epub { file ->
        val parsed = WorldDocumentImport.epub(file, "book.epub")
        assertEquals("合成书", parsed.title); assertTrue(parsed.source.contains("合成作者"))
        assertTrue(parsed.content.indexOf("第一段") < parsed.content.indexOf("第二段"))
        assertFalse(parsed.content.contains("do not execute")); assertTrue(parsed.content.contains("阅读章节 2"))
    }
    @Test fun epubCollapsesHtmlIndentationAndNestedBlockGapsButKeepsParagraphsAndInlineSpaces() {
        val html = """<html xmlns='http://www.w3.org/1999/xhtml'><body>
            <div>
                <section>
                    <h1>目录</h1>
                    <p>扉页</p>
                    <p>第一部</p>
                </section>
                <p>甲<span>乙</span>丙</p>
                <p>A <em>small</em>  world</p>
            </div>
        </body></html>"""
        epub(mapOf("OEBPS/one.xhtml" to html)) { file ->
            val parsed = WorldDocumentImport.epub(file, "book.epub")
            assertTrue(parsed.content.contains("目录\n\n扉页\n\n第一部"))
            assertTrue(parsed.content.contains("甲乙丙\n\nA small world"))
            assertFalse(parsed.content.contains("\n\n\n"))
            assertFalse(Regex("\n[ \\t]+\n").containsMatchIn(parsed.content))
        }
    }
    @Test fun epubPreservesPreformattedIndentAndBlankLinesWithoutFlatteningAllContent() {
        val html = "<html><body><p>before</p><pre>  code\n    nested\n\n\n  last</pre><p>after<br/><br/>last</p></body></html>"
        epub(mapOf("OEBPS/one.xhtml" to html)) { file ->
            val parsed = WorldDocumentImport.epub(file, "book.epub")
            assertTrue(parsed.content.contains("  code\n    nested\n\n\n  last"))
            assertTrue(parsed.content.contains("after\n\nlast"))
        }
    }
    @Test fun epubIndentationIsNotCountedAsBookTextButVisibleTextStillHasACompleteLimit() {
        val indent = " \n".repeat(300_000)
        epub(mapOf("OEBPS/one.xhtml" to "<html><body>$indent<p>只有正文</p>$indent</body></html>")) { file ->
            val parsed = WorldDocumentImport.epub(file, "book.epub")
            assertTrue(parsed.content.length < 150); assertTrue(parsed.content.contains("只有正文"))
        }
        epub(mapOf("OEBPS/one.xhtml" to "<html><body><p>${"字".repeat(500_001)}</p></body></html>")) { file ->
            rejects { WorldDocumentImport.epub(file, "book.epub") }
        }
    }
    @Test fun epubRejectsMissingChaptersEncryptionTraversalAndNonBooks() {
        epub(mapOf("META-INF/encryption.xml" to "<encryption/>")) { rejects { WorldDocumentImport.epub(it, "locked.epub") } }
        epub(mapOf("../escape.txt" to "bad")) { rejects { WorldDocumentImport.epub(it, "bad.epub") } }
        epub(mapOf("mimetype" to "application/zip")) { rejects { WorldDocumentImport.epub(it, "bad.epub") } }
        epub(mapOf("OEBPS/book.opf" to "<package><manifest/><spine><itemref idref='missing'/></spine></package>")) {
            try { WorldDocumentImport.epub(it, "bad.epub"); fail() } catch (_: IllegalStateException) {}
        }
    }
    @Test fun epubNeverLoadsExternalEntityAndHonorsCancellation() {
        val xml = """<!DOCTYPE html [<!ENTITY secret SYSTEM "file:///nonexistent/private-secret">]><html xmlns="http://www.w3.org/1999/xhtml"><body><p>正常内容</p>&secret;</body></html>"""
        epub(mapOf("OEBPS/one.xhtml" to xml)) { rejects { WorldDocumentImport.epub(it, "book.epub") } }
        epub(raw = mapOf("OEBPS/one.xhtml" to byteArrayOf(0xff.toByte(), 0xfe.toByte()) + xml.toByteArray(Charsets.UTF_16LE))) {
            rejects { WorldDocumentImport.epub(it, "utf16.epub") }
        }
        epub(mapOf("OEBPS/one.xhtml" to "<!DOCTYPE html PUBLIC 'xhtml' 'https://invalid.example/external.dtd'><html><body><p>本地文字</p></body></html>")) {
            assertTrue(WorldDocumentImport.epub(it, "book.epub").content.contains("本地文字"))
        }
        epub { file -> try { WorldDocumentImport.epub(file, "book.epub") { throw CancellationException() }; fail() } catch (_: CancellationException) {} }
    }
    @Test fun bookLengthAndRealPositionsSurviveBackupValidation() {
        val text = "字".repeat(420_000)
        val a = WorldArticleEntity(1, 10, "长书", "TXT", text, WorldReading.fingerprint(text), 1, 30_000, 2)
        WorldReading.validateBackup(setOf(10), listOf(a), emptyList())
        val decoded = kotlinx.serialization.json.Json.decodeFromString<WorldArticleEntity>(kotlinx.serialization.json.Json.encodeToString(a))
        assertEquals(a, decoded)
    }
}
