package com.cleo.cleos.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class WorldDocument(val title: String, val source: String, val content: String, val detail: String)

/** Local text extraction only. The original URI, PDF images and EPUB assets never go to a model. */
object WorldDocumentImport {
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_EXPANDED = 32 * 1024 * 1024
    private const val MAX_PAGES = 1_500
    val mimeTypes = arrayOf("application/pdf", "application/epub+zip", "text/plain", "text/markdown", "application/octet-stream")

    suspend fun read(context: Context, uri: Uri, progress: (String) -> Unit = {}): WorldDocument = withContext(Dispatchers.IO) {
        val task = currentCoroutineContext()
        var name = uri.lastPathSegment?.take(240)?.ifBlank { "读物" } ?: "读物"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (n >= 0) name = cursor.getString(n)?.take(240)?.ifBlank { "读物" } ?: name
                val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                require(size < 0 || cursor.isNull(size) || cursor.getLong(size) <= MAX_BYTES) { "文件超过 20 MB，请先拆分后导入" }
            }
        }
        val file = File.createTempFile("world-import-", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(8192); var total = 0
                    while (true) {
                        task.ensureActive(); val n = input.read(buffer); if (n < 0) break
                        total += n; require(total <= MAX_BYTES) { "文件超过 20 MB，请先拆分后导入" }; out.write(buffer, 0, n)
                    }
                }
            } ?: error("打不开这个文件，请重新选择")
            task.ensureActive()
            progress("正在读取文件…")
            val head = file.inputStream().use { input -> val bytes = ByteArray(8); val n = input.read(bytes); if (n > 0) bytes.copyOf(n) else byteArrayOf() }
            when {
                head.toString(Charsets.ISO_8859_1).startsWith("%PDF-") -> pdf(context, file, name, { task.ensureActive() }, progress)
                head.size >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4b.toByte() -> epub(file, name) { task.ensureActive() }
                name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "text") || context.contentResolver.getType(uri)?.startsWith("text/") == true -> text(file.readBytes(), name)
                else -> error("请选择 PDF、EPUB、TXT 或 Markdown 文件")
            }.also { task.ensureActive() }
        } finally { file.delete() }
    }

    private fun pdf(context: Context, file: File, name: String, check: () -> Unit, progress: (String) -> Unit): WorldDocument {
        PDFBoxResourceLoader.init(context.applicationContext)
        try {
            val memory = MemoryUsageSetting.setupTempFileOnly(64L * 1024 * 1024).setTempDir(context.cacheDir)
            PDDocument.load(file, memory).use { doc ->
                require(doc.currentAccessPermission.canExtractContent()) { "这份 PDF 不允许提取文字，请换一份可读取的文件" }
                val pages = doc.numberOfPages
                require(pages in 1..MAX_PAGES) { "PDF 最多支持 1500 页，请先拆分后导入" }
                val out = LimitedText(check)
                var empty = 0; var pageStart = 0; var lastPage = 0
                fun marker(number: Int) {
                    check(); progress("正在读取 PDF · $number / $pages 页")
                    out.append("\n\n〔PDF 第 $number 页〕\n")
                }
                val stripper = object : PDFTextStripper() {
                    override fun startPage(page: PDPage) {
                        // PDFBox never calls startPage for a page without a content stream.
                        for (skipped in lastPage + 1 until currentPageNo) { marker(skipped); empty++ }
                        marker(currentPageNo); lastPage = currentPageNo
                        pageStart = out.size
                        super.startPage(page)
                    }
                    override fun endPage(page: PDPage) {
                        super.endPage(page)
                        if (out.substring(pageStart).isBlank()) empty++
                    }
                }
                stripper.sortByPosition = true
                stripper.writeText(doc, out)
                for (skipped in lastPage + 1..pages) { marker(skipped); empty++ }
                require(empty < pages) { "没有提取到可读文字。这可能是扫描版 PDF，暂不支持 OCR；可以换用文字版 PDF、EPUB 或 TXT" }
                val body = WorldReading.normalize(out.toString())
                return WorldDocument(doc.documentInformation.title?.trim()?.takeIf { it.isNotBlank() }?.take(120) ?: title(name),
                    source("PDF 提取文字 · $pages 页" + if (empty > 0) "（$empty 页无文字）" else "", name, doc.documentInformation.author), body,
                    "$pages 页 · ${body.length} 字符" + if (empty > 0) "；$empty 页未提取到文字，可能是图片或空白页。仅保存提取的文字，不包含图片、版式和批注。" else "；仅保存提取的文字与页码，不包含图片、版式和批注。")
            }
        } catch (_: InvalidPasswordException) { error("这份 PDF 有密码，请先解锁后导入") }
    }

    internal fun text(bytes: ByteArray, name: String): WorldDocument {
        require(bytes.size <= MAX_BYTES) { "文件超过 20 MB" }
        val (charset, offset) = when {
            bytes.size >= 3 && bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) -> Charsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        val raw = try { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { error("文字编码暂不支持，请将文件另存为 UTF-8 或带 BOM 的 UTF-16") }
        val body = WorldReading.normalize(raw)
        require(body.isNotBlank() && '\u0000' !in body) { "文件里没有可读正文，请选择文字文件" }
        require(body.length <= WorldReading.MAX_TEXT) { "文字超过 50 万个字符，请先拆分后导入；本次没有截断或保存" }
        return WorldDocument(title(name), source("TXT / Markdown", name, null), body, "${body.length} 字符 · 纯文字")
    }

    /** EPUB spine determines reading order, never ZIP order. Nothing is extracted to disk. */
    internal fun epub(file: File, name: String, check: () -> Unit = {}): WorldDocument = ZipFile(file).use { zip ->
        val names = HashSet<String>(); var expanded = 0L; var count = 0
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            check(); val e = entries.nextElement(); count++
            require(count <= 5_000 && names.add(e.name) && !e.name.startsWith('/') && '\\' !in e.name && e.name.split('/').none { it == ".." }) { "EPUB 文件结构不受支持" }
            require(e.size >= 0) { "EPUB 文件信息不完整" }; expanded += e.size
            require(expanded <= MAX_EXPANDED) { "EPUB 解压后超过 32 MB，请先拆分" }
        }
        fun entry(path: String, max: Int = 2 * 1024 * 1024): ByteArray {
            check(); val e = zip.getEntry(path) ?: error("EPUB 缺少阅读所需的文件")
            require(e.size <= max) { "EPUB 的单个章节过大，请先拆分" }
            return zip.getInputStream(e).use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { check(); val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= max) { "EPUB 章节过大" }; out.write(buffer, 0, n) }; out.toByteArray()
            }
        }
        require(entry("mimetype", 100).toString(Charsets.UTF_8).trim() == "application/epub+zip") { "这不是可支持的 EPUB 文件" }
        require(zip.getEntry("META-INF/encryption.xml") == null) { "这份 EPUB 包含加密内容，暂不支持，请换用未加密版本" }
        val container = xml(entry("META-INF/container.xml"))
        val root = container.getElementsByTagNameNS("*", "rootfile").item(0) as? Element ?: error("EPUB 缺少书籍目录")
        val packagePath = resolve("", root.getAttribute("full-path"))
        val pkg = xml(entry(packagePath)); val items = pkg.getElementsByTagNameNS("*", "item")
        val manifest = (0 until items.length).map { items.item(it) as Element }.associateBy { it.getAttribute("id") }
        val spine = pkg.getElementsByTagNameNS("*", "itemref")
        require(spine.length in 1..MAX_PAGES) { "EPUB 的章节数量不受支持" }
        val out = LimitedText(check); var empty = 0
        for (i in 0 until spine.length) {
            check(); val ref = spine.item(i) as Element
            val item = manifest[ref.getAttribute("idref")] ?: error("EPUB 章节目录不完整")
            require(item.getAttribute("media-type") in setOf("application/xhtml+xml", "text/html")) { "这份 EPUB 有暂不支持的章节格式" }
            val chapter = xml(entry(resolve(packagePath, item.getAttribute("href"))))
            val body = chapter.getElementsByTagNameNS("*", "body").item(0) ?: error("EPUB 章节缺少正文")
            val text = EpubText(check)
            var nodes = 0
            fun visit(node: Node, depth: Int = 0, pre: Boolean = false) {
                check()
                require(depth <= 128 && ++nodes <= 100_000) { "EPUB 章节结构过于复杂，请换用文字版文件" }
                if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) { text.append(node.nodeValue, pre); return }
                val tag = node.localName?.lowercase()
                if (tag in setOf("script", "style", "noscript", "head")) return
                if (tag == "br") { text.lineBreak(); return }
                val spacing = if (tag in setOf("li", "tr", "dt", "dd")) 1 else if (tag in blocks) 2 else 0
                if (!pre && spacing > 0) text.separate(spacing)
                val children = node.childNodes
                for (j in 0 until children.length) visit(children.item(j), depth + 1, pre || tag == "pre")
                if (!pre && spacing > 0) text.separate(spacing)
            }
            visit(body)
            val chapterText = text.finish()
            if (chapterText.isBlank()) empty++
            else { out.append("\n\n〔EPUB 阅读章节 ${i + 1}〕\n"); out.append(chapterText) }
        }
        val content = WorldReading.normalize(out.toString())
        require(content.isNotBlank()) { "这份 EPUB 没有可提取的正文，可能是图片书，暂不支持 OCR" }
        fun metadata(tag: String) = pkg.getElementsByTagNameNS("*", tag).item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
        WorldDocument(metadata("title")?.take(120) ?: title(name), source("EPUB 提取文字" + if (empty > 0) "（$empty 个章节无文字）" else "", name, metadata("creator")), content,
            "${spine.length} 个阅读章节 · ${content.length} 字符" + if (empty > 0) "；$empty 个章节没有文字。仅保存章节文字，不包含图片和版式。" else "；仅保存章节文字，不包含图片和版式。")
    }

    private val blocks = setOf("p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "blockquote", "pre", "nav", "ul", "ol", "dl", "dt", "dd")

    /** XHTML collapses indentation outside pre; nested containers must not multiply paragraph gaps. */
    private class EpubText(private val check: () -> Unit) {
        private val out = StringBuilder()
        private var pendingSpace = false
        private fun put(char: Char) {
            if (out.length % 4096 == 0) check()
            require(out.length < WorldReading.MAX_TEXT) { "文字超过 50 万个字符，请先拆分后导入；本次没有截断或保存" }
            out.append(char)
        }
        fun append(value: String, pre: Boolean) {
            if (pre) {
                pendingSpace = false
                value.replace("\r\n", "\n").replace('\r', '\n').forEach(::put)
                return
            }
            value.forEach { char ->
                if (char in " \t\r\n\u000c") pendingSpace = true
                else {
                    if (pendingSpace && out.isNotEmpty() && !out.last().isWhitespace()) put(' ')
                    pendingSpace = false; put(char)
                }
            }
        }
        fun separate(lines: Int) {
            pendingSpace = false
            if (out.isEmpty()) return
            var existing = 0
            for (i in out.lastIndex downTo 0) { if (out[i] != '\n') break; existing++ }
            repeat((lines - existing).coerceAtLeast(0)) { put('\n') }
        }
        fun lineBreak() {
            pendingSpace = false
            if (out.isNotEmpty() && !out.endsWith("\n\n")) put('\n')
        }
        fun finish(): String = out.toString().trimEnd('\n')
    }
    private fun title(name: String) = name.substringBeforeLast('.', name).trim().take(120).ifBlank { "未命名读物" }
    private fun source(format: String, name: String, author: String?) = listOfNotNull(format, name, author?.take(200)?.takeIf { it.isNotBlank() }).joinToString(" · ").take(500)
    private fun resolve(base: String, href: String): String {
        require(href.isNotBlank() && '\\' !in href) { "EPUB 文件路径无效" }
        val uri = URI(null, null, base, null).resolve(href).normalize()
        val path = uri.path ?: error("EPUB 文件路径无效")
        require(uri.scheme == null && uri.authority == null && uri.query == null && !path.startsWith('/') && path.split('/').none { it == ".." }) { "EPUB 含有外部或无效的章节路径" }
        return path
    }
    private fun xml(bytes: ByteArray): org.w3c.dom.Document {
        // Android's Harmony DOM does not implement the external-entity feature switches.
        // Decode EPUB's UTF-8/16 XML first and reject internal subsets before any DOM parsing.
        // A simple XHTML doctype is discarded locally, so its external DTD is never fetched.
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> Charsets.UTF_16BE
            bytes.size >= 4 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        val raw = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require('\u0000' !in raw) { "EPUB 的文字编码不受支持" }
        val clean = raw.replace(Regex("<!DOCTYPE\\s+[^\\[<>]*>", RegexOption.IGNORE_CASE), "")
        require(!clean.contains("<!DOCTYPE", true) && !clean.contains("<!ENTITY", true)) { "EPUB 含有不支持的实体声明，请换用普通文字版本" }
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setExpandEntityReferences(false)
        }.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> org.xml.sax.InputSource(StringReader("")) }
        }.parse(org.xml.sax.InputSource(StringReader(clean)))
    }

    private class LimitedText(private val check: () -> Unit) : Writer() {
        private val text = StringBuilder()
        val size get() = text.length
        fun substring(start: Int) = text.substring(start)
        fun truncate(size: Int) = text.setLength(size)
        override fun write(buffer: CharArray, offset: Int, count: Int) {
            check(); require(text.length + count <= WorldReading.MAX_TEXT) { "文字超过 50 万个字符，请先拆分后导入；本次没有截断或保存" }
            text.append(buffer, offset, count)
        }
        override fun toString() = text.toString()
        override fun flush() = Unit
        override fun close() = Unit
    }
}
