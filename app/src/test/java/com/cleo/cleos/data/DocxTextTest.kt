package com.cleo.cleos.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxTextTest {
    private val namespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun document(body: String, ns: String = namespace): String =
        """<w:document xmlns:w="$ns"><w:body>$body</w:body></w:document>"""

    private fun paragraph(text: String): String = "<w:p><w:r><w:t>$text</w:t></w:r></w:p>"

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun docx(body: String): ByteArray = archive("word/document.xml" to document(body).toByteArray())

    @Test
    fun splitRunsKeepChineseEntitiesAndEmojiAndImportAsSeparateMemories() {
        val bytes = docx("""
            <w:p><w:r><w:t>喜好：</w:t></w:r><w:r><w:t>猫 &amp; 茶🐈</w:t></w:r></w:p>
            ${paragraph("约定：周末散步")}
        """.trimIndent())
        assertEquals("喜好：猫 & 茶🐈\n\n约定：周末散步", DocxText.read(bytes))
        val out = ForeignFile.read(bytes)
        assertEquals(listOf("喜好", "约定"), out.memories.map { it.name })
        assertEquals(listOf("猫 & 茶🐈", "周末散步"), out.memories.map { it.summary })
        assertTrue(out.cards.isEmpty())
        assertTrue(out.chats.isEmpty())
    }

    @Test
    fun listParagraphsTabsAndManualLineBreaksRemainReadable() {
        val bytes = docx("""
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
                <w:r><w:t>第一项</w:t><w:tab/><w:t>说明</w:t><w:br/><w:t>下一行</w:t><w:cr/><w:t>末行</w:t></w:r></w:p>
            ${paragraph("第二项")}
        """.trimIndent())
        assertEquals("第一项\t说明\n下一行\n末行\n\n第二项", DocxText.read(bytes))
    }

    @Test
    fun tablesAndNestedTablesPreserveRowAndCellReadingOrder() {
        val bytes = docx("""
            ${paragraph("表格之前")}
            <w:tbl>
              <w:tr><w:tc>${paragraph("称呼")}</w:tc><w:tc>${paragraph("小猫")}${paragraph("咪咪")}</w:tc></w:tr>
              <w:tr><w:tc>${paragraph("地点")}</w:tc><w:tc>${paragraph("外层")}
                <w:tbl><w:tr><w:tc>${paragraph("内层")}</w:tc><w:tc>${paragraph("公园")}</w:tc></w:tr></w:tbl>
                ${paragraph("外层结束")}</w:tc></w:tr>
            </w:tbl>
            ${paragraph("表格之后")}
        """.trimIndent())
        assertEquals("表格之前\n\n称呼\t小猫\n咪咪\n\n地点\t外层\n内层\t公园\n外层结束\n\n表格之后", DocxText.read(bytes))
    }

    @Test
    fun onlyVisibleMainBodyTextIsImportedWithoutFollowingRelationships() {
        val body = """
            <w:p><w:hyperlink xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" r:id="external">
              <w:r><w:t>链接标题</w:t></w:r></w:hyperlink>
              <w:del><w:r><w:delText>删掉的字</w:delText><w:t>也不应该出现</w:t></w:r></w:del>
              <w:moveFrom>${paragraph("移动前")}</w:moveFrom>
              <w:r><w:instrText>INCLUDETEXT external</w:instrText></w:r>
              <w:r><w:drawing>${paragraph("图片里的字")}</w:drawing></w:r>
              <w:r><w:pict>${paragraph("文本框里的字")}</w:pict></w:r>
              <w:ins><w:r><w:t>新增的字</w:t></w:r></w:ins>
            </w:p>
        """.trimIndent()
        val bytes = archive(
            "word/header1.xml" to document(paragraph("页眉不导入")).toByteArray(),
            "word/comments.xml" to document(paragraph("批注不导入")).toByteArray(),
            "word/document.xml" to document(body).toByteArray(),
        )
        assertEquals("链接标题新增的字", DocxText.read(bytes))
    }

    @Test
    fun strictNamespaceAndDifferentPrefixAreSupported() {
        val xml = document(paragraph("严格格式"), "http://purl.oclc.org/ooxml/wordprocessingml/main")
            .replace("w:", "word:").replace("xmlns:w=", "xmlns:word=")
        assertEquals("严格格式", DocxText.read(archive("word/document.xml" to xml.toByteArray())))
    }

    @Test
    fun xmlEncodingDeclarationAndBomAreRespected() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>" + document(paragraph("中文与🐈"))
        assertEquals("中文与🐈", DocxText.read(archive("word/document.xml" to xml.toByteArray(Charsets.UTF_16))))
    }

    @Test
    fun ordinaryZipAndCorruptXmlFailWithActionableErrors() {
        val nonWord = archive("notes.txt" to "普通压缩包".toByteArray())
        assertTrue(assertThrows(ImportException::class.java) { ForeignFile.read(nonWord) }.message.orEmpty().contains("不是"))
        val corrupt = archive("word/document.xml" to document(paragraph("内容")).dropLast(5).toByteArray())
        assertTrue(assertThrows(ImportException::class.java) { ForeignFile.read(corrupt) }.message.orEmpty().contains("损坏"))
        val wrongNamespace = archive("word/document.xml" to document(paragraph("内容"), "urn:wrong").toByteArray())
        assertTrue(assertThrows(ImportException::class.java) { ForeignFile.read(wrongNamespace) }.message.orEmpty().contains("正文"))
    }

    @Test
    fun blankOrImageOnlyDocumentDoesNotBecomeGibberishMemories() {
        listOf("<w:p/>", "<w:p><w:r><w:drawing/></w:r></w:p>").forEach { body ->
            val error = assertThrows(ImportException::class.java) { ForeignFile.read(docx(body)) }
            assertTrue(error.message.orEmpty().contains("没有可导入"))
        }
    }

    @Test
    fun dtdIsRejectedForBothInternalAndExternalEntities() {
        listOf(
            "<!DOCTYPE w:document [<!ENTITY test '不应该导入'>]>",
            "<!DOCTYPE w:document [<!ENTITY test SYSTEM 'file:///nonexistent-cleos-test'>]>",
        ).forEach { dtd ->
            val bytes = archive("word/document.xml" to (dtd + document(paragraph("&test;"))).toByteArray())
            assertThrows(ImportException::class.java) { ForeignFile.read(bytes) }
        }
    }

    @Test
    fun oversizedXmlAndTextAndExcessiveNestingAreBounded() {
        val hugeXml = docx("<!--" + "x".repeat(8 * 1024 * 1024) + "-->" + paragraph("内容"))
        assertTrue(assertThrows(ImportException::class.java) { ForeignFile.read(hugeXml) }.message.orEmpty().contains("太大"))
        val hugeText = docx(paragraph("x".repeat(1_000_001)))
        val textError = assertThrows(ImportException::class.java) { ForeignFile.read(hugeText) }
        assertTrue(textError.message, textError.message.orEmpty().contains("太长"))
        val nested = docx("<w:sdt>".repeat(260) + paragraph("内容") + "</w:sdt>".repeat(260))
        assertTrue(assertThrows(ImportException::class.java) { ForeignFile.read(nested) }.message.orEmpty().contains("太复杂"))
    }

    @Test
    fun oldDocOrEncryptedOfficeFilesHaveExplicitConversionAdvice() {
        val ole = byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())
        assertTrue(assertThrows(ImportException::class.java) { ForeignFile.read(ole) }.message.orEmpty().contains("未加密"))
    }

    @Test
    fun textAndJsonByteInputsKeepTheirExistingBehavior() {
        assertEquals("喜欢茶", ForeignFile.read("喜好：喜欢茶".toByteArray()).memories.single().summary)
        assertEquals("林夏", ForeignFile.read("""{"name":"林夏","personality":"安静"}""".toByteArray()).cards.single().name)
    }

    @Test
    fun jsonWrittenInWordDoesNotCreateAnUnintendedCompanion() {
        val out = ForeignFile.read(docx(paragraph("""{"name":"林夏","personality":"安静"}""")))
        assertTrue(out.cards.isEmpty())
        assertTrue(out.memories.isNotEmpty())
    }
}
