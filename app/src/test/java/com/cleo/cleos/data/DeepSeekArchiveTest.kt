package com.cleo.cleos.data

import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeepSeekArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun currentBranchFollowsParentLinksAndRetainsImageReferences() {
        archive("""
            {"id":"thread-1","current_message_id":5,"messages":[
              {"id":5,"parent_id":4,"role":"ASSISTANT","fragments":[
                 {"type":"THINK","content":"private reasoning"},{"type":"RESPONSE","content":"final reply"}]},
              {"id":1,"parent_id":null,"role":"USER","inserted_at":"2026-11-16T17:45:00+08:00",
               "fragments":[{"type":"REQUEST","content":"first question"}]},
              {"id":2,"parent_id":1,"role":"ASSISTANT","fragments":[{"type":"RESPONSE","content":"first reply"}]},
              {"id":3,"parent_id":2,"role":"USER","fragments":[{"type":"REQUEST","content":"old edit"}]},
              {"id":4,"parent_id":2,"role":"USER","fragments":[{"type":"FILE","files":[
                  {"is_image":true,"export_path":"chat_sessions/opaque-folder/白云 1.jpg"},
                  {"is_image":true,"export_path":"chat_sessions/../other.jpg"}]}]}
            ]}
        """) { parsed ->
            val session = parsed.sessions().single()
            assertEquals("A session", session.title)
            val lines = parsed.messages(session)
            assertEquals(listOf("first question", "first reply", "", "final reply"), lines.map { it.text })
            assertEquals(listOf("user", "assistant", "user", "assistant"), lines.map { it.role })
            assertEquals(Instant.parse("2026-11-16T09:45:00Z").toEpochMilli(), lines.first().at)
            assertEquals("chat_sessions/opaque-folder/白云 1.jpg", lines[2].attachments[0].path)
            assertNull(lines[2].attachments[1].path)
        }
    }

    @Test fun rejectsCyclesInTheSelectedBranch() {
        archive("""{"id":"thread-1","current_message_id":1,"messages":[{"id":1,"parent_id":1}]}""") { parsed ->
            assertThrows(ImportException::class.java) { parsed.messages(parsed.sessions().single()) }
        }
    }

    private fun archive(chat: String, use: (DeepSeekArchive) -> Unit) {
        val file = folder.newFile("export.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            val files = mapOf("manifest.json" to """{"export_format_version":3}""",
                "chat_sessions/thread-1.json" to chat,
                "chat_session_index.json" to """[{"id":"thread-1","title":"A session","message_count":5}]""")
            for ((path, text) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        ZipFile(file).use { use(DeepSeekArchive(it)) }
    }
}
