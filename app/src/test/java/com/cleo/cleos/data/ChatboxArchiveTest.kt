package com.cleo.cleos.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatboxArchiveTest {
    @get:Rule val cache = TemporaryFolder()

    @Test fun importsSelectedTextPathsAndKeepsArchivedThreadsSeparate() {
        val rows = """
            {"type":"session","session":{"id":"session","name":"小茶","activeThreadId":"main"}}
            {"type":"thread","thread":{"sessionId":"session","threadId":"old","name":"前天","rootMessageId":"old-q"}}
            {"type":"thread","thread":{"sessionId":"session","threadId":"main","rootMessageId":"system"}}
            {"type":"message","message":{"sessionId":"session","threadId":"old","messageId":"old-q","message":{"role":"user","content":"前天好"}}}
            {"type":"message","message":{"sessionId":"session","threadId":"main","messageId":"system","activeChildId":"q","message":{"role":"system","content":"系统提示"}}}
            {"type":"message","message":{"sessionId":"session","threadId":"main","messageId":"q","parentMessageId":"system","activeChildId":"answer","message":{"role":"user","contentParts":[{"type":"text","text":"你好"},{"type":"image","key":"image"}],"timestamp":123}}}
            {"type":"message","message":{"sessionId":"session","threadId":"main","messageId":"alternative","parentMessageId":"q","message":{"role":"assistant","content":"旧版本"}}}
            {"type":"message","message":{"sessionId":"session","threadId":"main","messageId":"answer","parentMessageId":"q","createdAt":456,"message":{"role":"assistant","contentParts":[{"type":"reasoning","text":"内部思考"},{"type":"text","text":"内部字段","protocolOnly":true},{"type":"text","text":"来了"},{"type":"text","text":"请坐"}]}}}
        """.trimIndent()
        val zip = archive(rows)
        val chats = requireNotNull(ChatboxArchive.readIfPresent(zip, cache.root))
        assertEquals(listOf("小茶 · 前天", "小茶"), chats.map { it.title })
        assertEquals(listOf(ImportedMessage(true, "前天好", 0)), chats.first().messages)
        assertEquals(listOf(ImportedMessage(true, "你好", 123), ImportedMessage(false, "来了\n\n请坐", 456)), chats.last().messages)
        assertNotEquals(chats.first().sourceKey, chats.last().sourceKey)
        assertEquals(chats, ChatboxArchive.readIfPresent(zip, cache.root))
        assertTrue(cache.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun rejectsBrokenBranchesAndIncompleteArchives() {
        val rows = """
            {"type":"session","session":{"id":"session","activeThreadId":"main"}}
            {"type":"thread","thread":{"sessionId":"session","threadId":"main","rootMessageId":"missing"}}
        """.trimIndent()
        val missing = assertThrows(ImportException::class.java) {
            ChatboxArchive.readIfPresent(archive(rows), cache.root)
        }
        assertTrue(missing.message.orEmpty().contains("Chatbox"))
        assertThrows(ImportException::class.java) {
            ChatboxArchive.readIfPresent(archive(rows, completion = "different"), cache.root)
        }
        assertTrue(cache.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun leavesOtherZipFormatsToTheirExistingReaders() {
        val zip = pack("word/document.xml" to "<document/>", "manifest.json" to """{"format":"other"}""")
        assertNull(ChatboxArchive.readIfPresent(zip, cache.root))
    }

    private fun archive(rows: String, completion: String = "backup"): ByteArray = pack(
        "resources/000000.jpg" to "ignored",
        "sessions/session.jsonl" to rows,
        "settings.json" to "ignored",
        "manifest.json" to """{"format":"chatbox-backup","formatVersion":3,"layout":"rows","archiveId":"backup"}""",
        "complete.json" to """{"archiveId":"$completion"}""",
    )

    private fun pack(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, contents) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
