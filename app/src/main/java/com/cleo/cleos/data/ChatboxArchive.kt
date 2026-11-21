package com.cleo.cleos.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Chatbox V3 stores each conversation as a tree of JSON rows inside its ZIP. */
internal object ChatboxArchive {
    private const val MIB = 1024 * 1024
    private const val METADATA_LIMIT = 64 * 1024
    private const val SESSION_BUDGET = 64L * MIB
    private const val SESSION_LIMIT = 16 * MIB
    private const val ROW_LIMIT = 40_000
    private const val MAX_SESSIONS = 2_000
    private val sessionPath = Regex("sessions/[^/\\\\]+[.]jsonl")

    fun readIfPresent(bytes: ByteArray, cache: File): List<ImportedChat>? {
        val file = File.createTempFile("foreign-import-", ".zip", cache)
        try {
            file.writeBytes(bytes)
            val zip = try { ZipFile(file) } catch (_: ZipException) { return null }
            zip.use {
                val entry = it.getEntry("manifest.json")?.takeIf { e -> e.size in 1..METADATA_LIMIT.toLong() }
                    ?: return null
                val manifest = try { json(it.readText(entry, METADATA_LIMIT)) }
                catch (_: ImportException) { return null }
                catch (_: IOException) { return null }
                if (manifest.text("format") != "chatbox-backup") return null
                if ((manifest["formatVersion"] as? JsonPrimitive)?.intOrNull != 3 || manifest.text("layout") != "rows")
                    throw ImportException("请选择 Chatbox 导出的 V3 ZIP 备份")
                return try {
                    val archiveId = manifest.text("archiveId") ?: malformed()
                    val complete = it.getEntry("complete.json") ?: malformed()
                    if (json(it.readText(complete, METADATA_LIMIT)).text("archiveId") != archiveId) malformed()
                    chats(it)
                } catch (_: IOException) { malformed() }
            }
        } finally {
            file.delete()
        }
    }

    private fun chats(zip: ZipFile): List<ImportedChat> {
        val seenEntries = HashSet<String>()
        val seenSessions = HashSet<String>()
        var expanded = 0L
        val chats = ArrayList<ImportedChat>()
        for (entry in zip.entries()) {
            if (entry.isDirectory || !sessionPath.matches(entry.name)) continue
            if (!seenEntries.add(entry.name)) malformed()
            if (seenEntries.size > MAX_SESSIONS || entry.size > SESSION_LIMIT ||
                expanded + entry.size > SESSION_BUDGET)
                throw ImportException("这份 Chatbox 备份的聊天太多，请分批导出再导入")
            expanded += entry.size
            chats += chatsInSession(zip.readText(entry, SESSION_LIMIT), seenSessions)
        }
        if (chats.isEmpty()) throw ImportException("这份 Chatbox 备份里没有可导入的文字聊天")
        return chats
    }

    private fun chatsInSession(body: String, seenSessions: MutableSet<String>): List<ImportedChat> {
        val rows = body.lineSequence().filter { it.isNotBlank() }.iterator()
        val header = if (rows.hasNext()) json(rows.next()) else malformed()
        if (header.text("type") != "session") malformed()
        val session = header.obj("session") ?: malformed()
        val sessionId = session.id("id")
        if (!seenSessions.add(sessionId)) malformed()
        val name = session.text("name").orEmpty().ifBlank { "Chatbox 会话" }
        val threads = LinkedHashMap<String, JsonObject>()
        val messages = HashMap<String, MessageRow>()
        var count = 0
        while (rows.hasNext()) {
            if (++count > ROW_LIMIT) throw ImportException("这段 Chatbox 会话的消息太多，请拆分后导出")
            val row = json(rows.next())
            when (row.text("type")) {
                "thread" -> {
                    val thread = row.obj("thread") ?: malformed()
                    if (thread.id("sessionId") != sessionId || threads.put(thread.id("threadId"), thread) != null) malformed()
                }
                "message" -> {
                    val message = row.obj("message") ?: malformed()
                    if (message.id("sessionId") != sessionId) malformed()
                    val parsed = MessageRow(message.id("threadId"), message.text("parentMessageId"),
                        message.text("activeChildId"), message.toImportedMessage())
                    if (messages.put(message.id("messageId"), parsed) != null) malformed()
                }
                else -> malformed()
            }
        }
        return threads.mapNotNull { (threadId, thread) ->
            val selected = ArrayList<ImportedMessage>()
            val visited = HashSet<String>()
            var next = thread.text("rootMessageId")
            var parent: String? = null
            while (next != null) {
                val row = messages[next] ?: malformed()
                if (!visited.add(next) || row.thread != threadId || row.parent != parent) malformed()
                row.message?.let(selected::add)
                parent = next
                next = row.child
            }
            if (selected.isEmpty()) return@mapNotNull null
            val title = if (threadId == session.text("activeThreadId")) name
                else "$name · ${thread.text("name").orEmpty().ifBlank { "历史对话" }}"
            val source = WorldReading.fingerprint("${sessionId.length}:$sessionId$threadId")
            ImportedChat(title, selected, sourceKey = "chatbox:$source")
        }
    }

    private data class MessageRow(val thread: String, val parent: String?, val child: String?, val message: ImportedMessage?)

    private fun JsonObject.toImportedMessage(): ImportedMessage? {
        val content = obj("message") ?: return null
        val role = content.text("role")
        if (role != "user" && role != "assistant" || (content["isSummary"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val parts = (content["contentParts"] as? JsonArray).orEmpty().mapNotNull { element ->
            val part = element as? JsonObject ?: return@mapNotNull null
            if (part.text("type") != "text" || (part["protocolOnly"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            part.text("text")?.takeIf { it.isNotBlank() }
        }
        val text = parts.joinToString("\n\n").ifBlank { content.text("content").orEmpty() }
        if (text.isBlank()) return null
        val stamp = (content["timestamp"] as? JsonPrimitive)?.longOrNull
            ?: (get("createdAt") as? JsonPrimitive)?.longOrNull ?: 0L
        return ImportedMessage(role == "user", text, stamp)
    }

    private fun ZipFile.readText(entry: ZipEntry, limit: Int): String {
        if (entry.size !in 1..limit.toLong()) malformed()
        val output = ByteArrayOutputStream(entry.size.toInt())
        val size = try { getInputStream(entry).use { it.copyToBounded(output, limit.toLong()) } }
        catch (_: ImportException) { malformed() }
        val bytes = output.toByteArray()
        if (size != entry.size || CRC32().apply { update(bytes) }.value != entry.crc) malformed()
        return try { bytes.decodeToString(throwOnInvalidSequence = true) }
        catch (_: java.nio.charset.CharacterCodingException) { malformed() }
    }

    private fun json(raw: String): JsonObject =
        (try { Json.parseToJsonElement(raw) } catch (_: IllegalArgumentException) { malformed() }) as? JsonObject ?: malformed()
    private fun JsonObject.obj(key: String) = get(key) as? JsonObject
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.id(key: String) = text(key)?.takeIf { it.isNotBlank() && it.length <= 512 } ?: malformed()
    private fun malformed(): Nothing = throw ImportException("Chatbox 备份中的会话数据不完整或格式不正确，请重新导出")
}
