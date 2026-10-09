package com.cleo.cleos.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

/** Reads Word's visible main-body text locally. No media extraction or external relationships. */
internal object DocxText {
    private val wordNamespaces = setOf(
        "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
        "http://purl.oclc.org/ooxml/wordprocessingml/main",
    )
    private const val MAX_XML = 8 * 1024 * 1024
    private const val MAX_EXPANDED = 48 * 1024 * 1024
    private const val MAX_TEXT = 1_000_000
    private const val MAX_ENTRIES = 4096

    fun isZip(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
        ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) ||
            (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()) ||
            (bytes[2] == 7.toByte() && bytes[3] == 8.toByte()))

    fun read(bytes: ByteArray): String = try {
        var document: ByteArray? = null
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entries = 0
            var expanded = 0
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw ImportException("Word 文件内容太多，请拆成几个 DOCX 再导入")
                val body = if (entry.name == "word/document.xml") ByteArrayOutputStream() else null
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    expanded += count
                    if (expanded > MAX_EXPANDED || (body != null && body.size() + count > MAX_XML))
                        throw ImportException("Word 文件解压后内容太大，请拆成几个 DOCX 再导入")
                    body?.write(buffer, 0, count)
                }
                if (body != null) {
                    document = body.toByteArray()
                    break
                }
            }
        }
        val xml = document ?: throw ImportException("这个压缩文件不是可读取的 DOCX。Word 文档请另存为 .docx；Cleos 备份请用「恢复」")
        val handler = BodyText()
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        // Android Expat and JVM SAX both support this. Reject DTDs before entity expansion.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.entityResolver = handler
        reader.parse(InputSource(xml.inputStream()))
        if (!handler.document || !handler.body) throw ImportException("没找到 Word 文档正文，请重新另存为 DOCX")
        handler.blocks.joinToString("\n\n").trim().ifEmpty {
            throw ImportException("Word 文档里没有可导入的正文文字，只有图片的文档暂不支持")
        }
    } catch (e: ImportException) {
        throw e
    } catch (e: Exception) {
        // Some SAX implementations wrap exceptions thrown by content callbacks.
        // Keep our size/depth advice instead of turning these into "damaged file".
        var cause: Throwable? = e.cause
        repeat(8) {
            val current = cause
            if (current is ImportException) throw current
            cause = current?.cause
        }
        throw ImportException("Word 文件损坏或格式不支持，请重新另存为未加密的 DOCX 再试")
    }

    private class Table {
        val rows = mutableListOf<String>()
        var cells = mutableListOf<String>()
        var cell: MutableList<String>? = null
    }

    private class BodyText : DefaultHandler2() {
        val blocks = mutableListOf<String>()
        var document = false
        var body = false
        private var inBody = false
        private var inText = false
        private var ignored = 0
        private var depth = 0
        private var textSize = 0
        private val paragraphs = ArrayDeque<StringBuilder>()
        private val tables = ArrayDeque<Table>()

        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw SAXException("DTD is not supported")
        }

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            throw SAXException("External entities are not supported")

        override fun error(e: SAXParseException) { throw e }
        override fun fatalError(e: SAXParseException) { throw e }

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if (++depth > 256) throw ImportException("Word 文档结构太复杂，请另存为简化的 DOCX 再导入")
            if (ignored > 0) { ignored++; return }
            if (uri !in wordNamespaces) return
            if (localName == "document") document = true
            if (localName == "body") { body = true; inBody = true }
            if (!inBody) return
            when (localName) {
                "del", "moveFrom", "drawing", "pict" -> ignored = 1
                "p" -> paragraphs.addLast(StringBuilder())
                "t" -> inText = true
                "tab" -> paragraphs.lastOrNull()?.append('\t')
                "br", "cr" -> paragraphs.lastOrNull()?.append('\n')
                "tbl" -> tables.addLast(Table())
                "tr" -> tables.lastOrNull()?.cells = mutableListOf()
                "tc" -> tables.lastOrNull()?.cell = mutableListOf()
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (!inBody || !inText || ignored > 0 || paragraphs.isEmpty()) return
            textSize += length
            if (textSize > MAX_TEXT) throw ImportException("Word 正文太长，请拆成几个 DOCX 再导入")
            paragraphs.last().append(ch, start, length)
        }

        private fun block(text: String) {
            if (text.isBlank()) return
            val cell = tables.lastOrNull()?.cell
            if (cell != null) cell.add(text) else blocks.add(text)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            depth--
            if (ignored > 0) { ignored--; return }
            if (uri !in wordNamespaces || !inBody) return
            when (localName) {
                "t" -> inText = false
                "p" -> if (paragraphs.isNotEmpty()) block(paragraphs.removeLast().toString().trim())
                "tc" -> tables.lastOrNull()?.let { table ->
                    table.cells.add(table.cell.orEmpty().joinToString("\n")); table.cell = null
                }
                "tr" -> tables.lastOrNull()?.let { table ->
                    table.cells.joinToString("\t").trim().takeIf { it.isNotEmpty() }?.let(table.rows::add)
                }
                "tbl" -> if (tables.isNotEmpty()) block(tables.removeLast().rows.joinToString("\n\n"))
                "body" -> inBody = false
            }
        }
    }
}
