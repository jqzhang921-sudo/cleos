package com.cleo.cleos.data

import java.io.InputStream
import java.time.OffsetDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal data class DeepSeekSession(val id: String, val title: String, val count: Int, val updatedAt: Long) {
    val sourceKey get() = "deepseek:$id"
}

internal data class DeepSeekAttachment(val path: String?, val image: Boolean)
internal data class DeepSeekMessage(
    val role: String,
    val text: String,
    val at: Long,
    val attachments: List<DeepSeekAttachment>,
)

/** DeepSeek export v3. The selected leaf links back through the messages the user sees. */
internal class DeepSeekArchive(private val zip: ZipFile) {
    fun sessions(): List<DeepSeekSession> {
        val manifest = readJson("manifest.json", 8_192) as? JsonObject
        if ((manifest?.get("export_format_version") as? JsonPrimitive)?.intOrNull != 3)
            throw ImportException("请选择 DeepSeek 客户端导出的 ZIP 文件（格式版本 3）")
        val index = readJson("chat_session_index.json", 4 * MIB) as? JsonArray
            ?: throw ImportException("DeepSeek 导出包里找不到会话索引")
        if (index.size > 8_000) throw ImportException("这份导出包的会话太多")
        val seen = HashSet<String>()
        return index.map { element ->
            val item = element as? JsonObject ?: malformed()
            val id = item.text("id")?.takeIf { SESSION_ID.matches(it) } ?: malformed()
            if (!seen.add(id)) malformed()
            DeepSeekSession(id, item.text("title").orEmpty().trim().take(160).ifBlank { "未命名会话" },
                (item["message_count"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0,
                stamp(item.text("updated_at")))
        }.sortedByDescending { it.updatedAt }
    }

    fun messages(session: DeepSeekSession): List<DeepSeekMessage> {
        val chat = readJson("chat_sessions/${session.id}.json", 24 * MIB) as? JsonObject ?: malformed()
        if (chat.text("id") != session.id) malformed()
        val nodes = chat["messages"] as? JsonArray ?: malformed()
        if (nodes.size > 40_000) throw ImportException("这段 DeepSeek 会话的消息太多")
        if (nodes.isEmpty()) return emptyList()
        val byId = HashMap<Long, JsonObject>()
        for (node in nodes) {
            val row = node as? JsonObject ?: malformed()
            val id = row.number("id")?.takeIf { it > 0 } ?: malformed()
            if (byId.put(id, row) != null) malformed()
        }
        var next = chat.number("current_message_id") ?: malformed()
        val visited = HashSet<Long>()
        val branch = ArrayList<JsonObject>()
        while (next != 0L) {
            if (!visited.add(next)) malformed()
            val row = byId[next] ?: malformed()
            branch += row
            next = row.number("parent_id") ?: 0L
        }
        return branch.asReversed().mapNotNull(::message)
    }

    fun image(path: String): ZipEntry? = zip.getEntry(path)?.takeIf {
        !it.isDirectory && it.size in 1..(16L * MIB)
    }

    fun copyImage(entry: ZipEntry, output: java.io.OutputStream) {
        zip.getInputStream(entry).use { source ->
            if (source.copyToBounded(output, 16L * MIB) != entry.size) malformed()
        }
    }

    private fun message(row: JsonObject): DeepSeekMessage? {
        val role = when (row.text("role")) {
            "USER" -> "user"
            "ASSISTANT" -> "assistant"
            else -> return null
        }
        val text = ArrayList<String>()
        val attachments = ArrayList<DeepSeekAttachment>()
        for (value in (row["fragments"] as? JsonArray).orEmpty()) {
            val fragment = value as? JsonObject ?: continue
            when (fragment.text("type")) {
                "REQUEST", "RESPONSE", "TEMPLATE_RESPONSE" ->
                    fragment.text("content")?.takeIf { it.isNotBlank() }?.let(text::add)
                "FILE" -> for (item in (fragment["files"] as? JsonArray).orEmpty()) {
                    val file = item as? JsonObject ?: continue
                    val image = (file["is_image"] as? JsonPrimitive)?.booleanOrNull == true
                    attachments += DeepSeekAttachment(file.text("export_path")?.takeIf { image && imagePath(it) }, image)
                }
            }
        }
        if (text.isEmpty() && attachments.isEmpty()) return null
        return DeepSeekMessage(role, text.joinToString("\n\n"), stamp(row.text("inserted_at")), attachments)
    }

    private fun imagePath(path: String): Boolean {
        val parts = path.split('/', limit = 4)
        if (parts.size != 3 || parts[0] != "chat_sessions" || !SESSION_ID.matches(parts[1])) return false
        val name = parts[2]
        return name.length in 1..255 && name.none { it.isISOControl() || it == '\\' } &&
            name.substringAfterLast('.', "").lowercase() in IMAGE_TYPES
    }

    private fun readJson(path: String, max: Int): JsonElement? {
        val entry = zip.getEntry(path) ?: return null
        if (entry.isDirectory || entry.size !in 1..max.toLong()) malformed()
        val bytes = zip.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream(entry.size.toInt())
            if (input.copyToBounded(out, max.toLong()) != entry.size) malformed()
            out.toByteArray()
        }
        return try { Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) }
        catch (_: IllegalArgumentException) { malformed() }
        catch (_: java.nio.charset.CharacterCodingException) { malformed() }
    }

    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun malformed(): Nothing = throw ImportException("DeepSeek 导出包中的会话数据不完整或格式不正确")

    companion object {
        private const val MIB = 1024 * 1024
        private val SESSION_ID = Regex("[a-zA-Z0-9_-]{1,120}")
        private val IMAGE_TYPES = setOf("jpg", "jpeg", "png", "webp")

        private fun stamp(value: String?): Long = value?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrDefault(0L)
        } ?: 0L
    }
}

internal fun InputStream.copyToBounded(output: java.io.OutputStream, limit: Long, checkActive: () -> Unit = {}): Long {
    val buffer = ByteArray(64 * 1024)
    var bytes = 0L
    while (true) {
        checkActive()
        val size = read(buffer)
        if (size < 0) return bytes
        bytes += size
        if (bytes > limit) throw ImportException("DeepSeek 导出包中的文件超出大小上限")
        output.write(buffer, 0, size)
    }
}
