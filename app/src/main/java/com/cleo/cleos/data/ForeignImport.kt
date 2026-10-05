package com.cleo.cleos.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.cleo.cleos.ai.MemoryKinds
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ImportException(message: String) : Exception(message)

/** What a memory import brought in: topics added, details added, and topics left out because their kind was full. */
data class MemoryImport(val added: Int, val details: Int, val skipped: Int = 0)

/** One topic as a foreign file has it: what it is, the line the TA reads, and the details behind it. */
data class ImportedMemory(
    val kind: String,
    val name: String,
    val summary: String,
    val details: List<String>,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Memory as it comes from any file that isn't this app's own backup: a JSON array, a JSON
 * object wrapping one, or plain text / Markdown. Every entry becomes one topic about the
 * person ("profile" unless the file says otherwise); what is already here is matched by name.
 */
object ForeignMemory {
    private val NAME_KEYS = listOf("name", "title", "topic", "key", "label")
    private val SUMMARY_KEYS = listOf("summary", "content", "text", "value", "body", "description", "note", "fact")
    private val DETAIL_KEYS = listOf("details", "items", "notes", "list", "bullets")
    private val KIND_KEYS = listOf("kind", "category", "type")
    private val WRAPPER_KEYS = listOf("memories", "memory", "facts", "items", "list", "data", "entries")

    /** Reads a third-party memory file. Throws [ImportException] when nothing can be read. */
    fun parse(text: String): List<ImportedMemory> {
        val body = text.trim()
        if (body.isEmpty()) throw ImportException("文件是空的")
        // Only an array or an object is a JSON memory file. Anything else the reader makes of the
        // text — a line of words with no punctuation parses as a bare literal — is plain text.
        val json = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        val items = when (json) {
            is JsonArray, is JsonObject -> fromJson(json)
            else -> fromText(body)
        }
        if (items.isEmpty()) throw ImportException("没读出来记忆：文件里没有能当成记忆的条目")
        return items
    }

    private fun fromJson(root: JsonElement): List<ImportedMemory> {
        val elements: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> WRAPPER_KEYS.firstNotNullOfOrNull { root[it] as? JsonArray } ?: listOf(root)
            else -> emptyList()
        }
        return elements.mapNotNull { element(it) }
    }

    private fun element(e: JsonElement): ImportedMemory? = when (e) {
        is JsonPrimitive -> e.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && e.isString }?.let { plain(it) }
        is JsonObject -> {
            val name = key(e, NAME_KEYS)
            val summary = key(e, SUMMARY_KEYS)
            val details = strings(DETAIL_KEYS.firstNotNullOfOrNull { e[it] })
            if (name.isNullOrBlank() && summary.isNullOrBlank() && details.isEmpty()) {
                null
            } else {
                topic(
                    kind = MemoryKinds.of(key(e, KIND_KEYS)?.lowercase())?.key ?: "profile",
                    name = name.orEmpty(),
                    summary = summary.orEmpty(),
                    details = details,
                    pinned = (e["pinned"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
        }
        else -> null
    }

    private fun key(o: JsonObject, keys: List<String>): String? = keys.firstNotNullOfOrNull { k ->
        (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
    }

    private fun strings(e: JsonElement?): List<String> = when (e) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
        is JsonPrimitive -> listOfNotNull(e.contentOrNull?.trim()?.ifEmpty { null })
        else -> emptyList()
    }

    /** One plain line: "名字: 内容" splits into a name and a summary, anything else is its own. */
    private fun plain(s: String): ImportedMemory {
        val m = SPLIT.find(s.trim())
        val name = m?.groupValues?.get(1)?.trim().orEmpty()
        val summary = m?.groupValues?.get(2)?.trim().orEmpty()
        return topic("profile", name, summary.ifEmpty { name.ifEmpty { s.trim() } })
    }

    /**
     * One topic, cut to what the memory keeps: a name of [MemoryKinds.NAME_MAX], a summary of
     * [MemoryKinds.SUMMARY_MAX], details of [MemoryKinds.DETAILS] at [MemoryKinds.DETAIL_MAX] each.
     * The summary is the one line the TA reads with every message, so what a file's paragraph
     * holds past it is kept as details rather than lost.
     */
    private fun topic(
        kind: String,
        name: String,
        summary: String,
        details: List<String> = emptyList(),
        pinned: Boolean = false,
    ): ImportedMemory {
        val n = name.trim().take(MemoryKinds.NAME_MAX)
        val whole = summary.trim()
        val s = whole.take(MemoryKinds.SUMMARY_MAX)
        val extra = if (whole.length > MemoryKinds.SUMMARY_MAX) whole.chunked(MemoryKinds.DETAIL_MAX) else emptyList()
        return ImportedMemory(
            kind = kind,
            name = n.ifEmpty { s.take(NAME_FROM_SUMMARY) },
            summary = s.ifEmpty { n },
            details = (extra + details)
                .filter { it.isNotBlank() }
                .map { it.trim().take(MemoryKinds.DETAIL_MAX) }
                .take(MemoryKinds.DETAILS),
            pinned = pinned,
            createdAt = 0L,
            updatedAt = 0L,
        )
    }

    /** Plain text / Markdown: blank lines and bullets split topics, the rest joins into one. */
    private fun fromText(text: String): List<ImportedMemory> {
        val out = mutableListOf<ImportedMemory>()
        val buffer = StringBuilder()
        fun flush() {
            val s = buffer.toString().trim()
            buffer.setLength(0)
            if (s.isNotEmpty()) out += plain(s)
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) { flush(); continue }
            val bullet = BULLET.find(line)
            if (bullet != null) {
                flush()
                val t = line.substring(bullet.value.length).trim()
                if (t.isNotEmpty()) out += plain(t)
            } else {
                buffer.appendLine(line.removePrefix("#").trim())
            }
        }
        flush()
        return out
    }

    private const val NAME_FROM_SUMMARY = 12
    private val SPLIT = Regex("^([^:：]{1,24})[:：]\\s*(.+)$")
    private val BULLET = Regex("^(?:[-*+•]|\\d+[.、)])\\s+")
}

/** Brings memory in from a file of any other app's shape into one TA. Nothing already here is touched. */
class ForeignImport(
    context: Context,
    private val db: AppDatabase,
) {
    private val resolver = context.contentResolver

    /** Reads [uri] and merges what it holds into [companionId]'s memory. */
    suspend fun importForeignMemories(uri: Uri, companionId: Long): MemoryImport = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { readAtMost(it, MAX_BYTES) } ?: throw ImportException("打不开这个文件")
        val imported = ForeignMemory.parse(bytes.decodeToString())
        var added = 0
        var details = 0
        var skipped = 0
        val now = System.currentTimeMillis()
        db.withTransaction {
            val have = db.memories().allFor(companionId).toMutableList()
            // Every topic's line goes with every message, so a kind holds [MemoryKinds.PER_KIND] of
            // them and no more, the same as when the TA remembers something itself: what is over
            // is left out, and said so, rather than quietly making every message longer.
            val room = MemoryKinds.all.associate { kind -> kind.key to (MemoryKinds.PER_KIND - have.count { it.kind == kind.key }).coerceAtLeast(0) }.toMutableMap()
            for (m in imported) {
                val same = have.firstOrNull { it.name.trim() == m.name.trim() }
                if (same == null) {
                    if ((room[m.kind] ?: 0) <= 0) {
                        skipped++
                        continue
                    }
                    room[m.kind] = (room[m.kind] ?: 1) - 1
                    val entity = m.entity(companionId, now)
                    // Kept in [have] as well, so a file naming the same topic twice makes one.
                    have += entity.copy(id = db.memories().insert(entity))
                    added++
                    continue
                }
                val old = MemoryDetails.decode(same.details)
                val merged = (old + m.details.filter { it !in old }).take(MemoryKinds.DETAILS)
                if (merged.size > old.size) {
                    // Filling in isn't news: the topic keeps the date it was last noted. A third-party
                    // file says nothing about when it was noted, so there is no newer date to take.
                    db.memories().update(same.copy(details = MemoryDetails.encode(merged)))
                    details += merged.size - old.size
                }
            }
        }
        MemoryImport(added, details, skipped)
    }

    private fun ImportedMemory.entity(companionId: Long, now: Long) = MemoryEntity(
        companionId = companionId,
        kind = kind,
        name = name,
        summary = summary,
        details = MemoryDetails.encode(details.take(MemoryKinds.DETAILS)),
        pinned = pinned,
        source = MemoryEntity.SOURCE_IMPORT,
        createdAt = createdAt.takeIf { it > 0 } ?: now,
        updatedAt = updatedAt.takeIf { it > 0 } ?: now,
    )

    private fun readAtMost(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) {
                throw ImportException("文件太大了（超过 ${max / 1024 / 1024} MB），多半里面还夹着老的图片。在那边重新导出一份再试。")
            }
        }
        return out.toByteArray()
    }

    companion object {
        // Text only, even years of memories stay far below this. Old exports with pictures inside
        // ran to hundreds of MB, more than a phone can hold as one JSON tree.
        private const val MAX_BYTES = 32 * 1024 * 1024
    }
}
