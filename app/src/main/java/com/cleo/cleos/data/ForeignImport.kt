package com.cleo.cleos.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.cleo.cleos.ai.ChatRepository
import com.cleo.cleos.ai.MemoryKinds
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.ConversationEntity
import com.cleo.cleos.data.db.LoreEntity
import com.cleo.cleos.data.db.MemoryEntity
import com.cleo.cleos.data.db.MessageEntity
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

/** One 设定 entry out of a world book: what brings it up, and what is true about it. */
data class ImportedLore(
    val book: String,
    val title: String,
    val keys: List<String>,
    val content: String,
    /** The file's insertion order, so the page shows them the way the book had them. */
    val position: Int,
    /** The file says this one is off; it is kept, and not given to the TA. */
    val enabled: Boolean = true,
)

/** A 角色卡: who another app's character is, as that app wrote them. */
data class ImportedCard(
    val name: String,
    val persona: String,
    /** What they say first, when the card has an opening line. Empty when it hasn't. */
    val greeting: String = "",
)

/** A chat log: what was said with one character. */
data class ImportedChat(val title: String, val messages: List<ImportedMessage>)

/** One line of a chat log. [at]: when it was said, 0 when the file doesn't say. */
data class ImportedMessage(val fromMe: Boolean, val content: String, val at: Long)

/** Everything one file held, by module: what such a file can carry, all four of them or any one. */
data class ForeignExport(
    val card: ImportedCard? = null,
    val lore: List<ImportedLore> = emptyList(),
    val memories: List<ImportedMemory> = emptyList(),
    val chats: List<ImportedChat> = emptyList(),
) {
    val books: List<String> get() = lore.map { it.book }.filter { it.isNotEmpty() }.distinct()

    /** Nothing in it had any of the four shapes. */
    val empty: Boolean
        get() = card == null && lore.isEmpty() && memories.isEmpty() && chats.none { it.messages.isNotEmpty() }
}

/**
 * Memory as it comes from any file that isn't this app's own backup: a JSON array, a JSON
 * object wrapping one, or plain text / Markdown. Every entry becomes one topic about the
 * person ("profile" unless the file says otherwise); what is already here is matched by name.
 */
object ForeignMemory {
    private val NAME_KEYS = listOf("name", "title", "topic", "key", "label")
    private val SUMMARY_KEYS = listOf("summary", "content", "text", "value", "body", "description", "note", "fact")
    private val DETAIL_KEYS = listOf("details", "items", "notes", "list", "bullets", "tags")
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

    /** One array of entries, for a reader that found them itself (ForeignFile). */
    internal fun entries(list: JsonArray): List<ImportedMemory> = list.mapNotNull { element(it) }

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
    internal fun topic(
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

/**
 * Reads what another app exported, whatever module it is. A 角色卡 is who a character is; a
 * 世界书 is the world they live in; a 记忆库 is what their app remembers; a 聊天记录 is what
 * was said. One file may hold one of them, or all four one after another.
 *
 * Anything that is none of the four is read as memory the way it always was, so a plain text
 * or Markdown file of notes still works.
 */
object ForeignFile {
    /** The keys only a character card has. A bare "description" is not one: every world book has that. */
    private val CARD_KEYS = listOf(
        "system_prompt", "personality", "scenario", "greeting_message", "first_mes",
        "example_dialogue", "mes_example", "char_persona", "character_book",
    )

    /** Throws [ImportException] when the file holds nothing that can be read at all. */
    fun read(text: String): ForeignExport {
        val body = text.trim()
        if (body.isEmpty()) throw ImportException("文件是空的")
        val json = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        // One JSON document: an object is a module, an array is a list of memory entries (as ever).
        val one = (json as? JsonObject)?.let { module(it, 0) }?.takeIf { !it.empty }
        if (one != null) return one
        // Not one document: a file with several modules in it, one after another.
        val blocks = blocks(body)
        if (blocks.isNotEmpty()) {
            val many = ForeignExport(
                card = blocks.firstNotNullOfOrNull { card(it) },
                lore = blocks.flatMap { lore(it) },
                memories = blocks.flatMap { memories(it) },
                chats = blocks.mapNotNull { chat(it) },
            )
            if (!many.empty) return many
        }
        return ForeignExport(memories = ForeignMemory.parse(body))
    }

    /** One object as one module; null when it is none of the four (it is then read as memory). */
    private fun module(o: JsonObject, position: Int): ForeignExport? {
        chat(o)?.let { return ForeignExport(chats = listOf(it)) }
        lore(o, position).takeIf { it.isNotEmpty() }?.let { return ForeignExport(lore = it) }
        card(o)?.let { c -> return ForeignExport(card = c, lore = book(o, c.name)) }
        memories(o).takeIf { it.isNotEmpty() }?.let { return ForeignExport(memories = it) }
        return null
    }

    private fun chat(o: JsonObject): ImportedChat? {
        val list = o["messages"] as? JsonArray ?: return null
        val messages = list.mapNotNull { element ->
            val e = element as? JsonObject
                ?: return@mapNotNull (element as? JsonPrimitive)?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { ImportedMessage(fromMe = false, content = it, at = 0L) }
            val content = text(e["content"]).orEmpty()
            if (content.isEmpty()) return@mapNotNull null
            // What the app itself wrote into the log (its own prompt, a tool's answer) is not a line
            // anyone said.
            val role = text(e["role"])?.lowercase().orEmpty()
            if (role == "system" || role == "tool") return@mapNotNull null
            ImportedMessage(fromMe = role == "user", content = content, at = number(e["timestamp"]) ?: 0L)
        }
        if (messages.isEmpty()) return null
        return ImportedChat(text(o["title"]).orEmpty(), messages)
    }

    /** The entries of a world book: `entries` holding them, each with the words that bring it up. */
    private fun lore(o: JsonObject, position: Int = 0): List<ImportedLore> {
        val raw = o["entries"] ?: return emptyList()
        val book = text(o["name"]).orEmpty()
        return when (raw) {
            is JsonArray -> raw.mapIndexedNotNull { i, e -> (e as? JsonObject)?.let { entry(it, book, position + i) } }
            // Some books write them as an object keyed by their order ("0", "1", …).
            is JsonObject -> raw.mapNotNull { (k, v) -> (v as? JsonObject)?.let { entry(it, book, k.toIntOrNull() ?: position) } }
            else -> emptyList()
        }
    }

    private fun entry(e: JsonObject, book: String, position: Int): ImportedLore? {
        val content = text(e["content"]).orEmpty()
        if (content.isEmpty()) return null
        val keys = keyList(e["keys"]) + keyList(e["secondary_keys"])
        // A world book's entries are the ones that say which words bring them up. Without that it is
        // some other app's memory file, and it is read as one.
        if (keys.isEmpty() && text(e["comment"]) == null && number(e["insertion_order"]) == null) return null
        val title = text(e["comment"]).orEmpty().ifEmpty { keys.firstOrNull().orEmpty() }.ifEmpty { content.take(TITLE_FROM_CONTENT) }
        return ImportedLore(
            book = book,
            title = title.take(TITLE_MAX),
            keys = keys,
            content = content,
            position = number(e["insertion_order"])?.toInt() ?: position,
            enabled = (e["enabled"] as? JsonPrimitive)?.booleanOrNull != false,
        )
    }

    /** The world book a card carries inside it, when it carries one. */
    private fun book(o: JsonObject, name: String): List<ImportedLore> {
        val inner = o["character_book"] as? JsonObject ?: return emptyList()
        val raw = inner["entries"] ?: return emptyList()
        val bookName = text(inner["name"]).orEmpty().ifEmpty { name }
        return when (raw) {
            is JsonArray -> raw.mapIndexedNotNull { i, e -> (e as? JsonObject)?.let { entry(it, bookName, i) } }
            is JsonObject -> raw.mapNotNull { (k, v) -> (v as? JsonObject)?.let { entry(it, bookName, k.toIntOrNull() ?: 0) } }
            else -> emptyList()
        }
    }

    /** A character card: the strong keys only it has, straight or under `data` (the newer shape). */
    private fun card(o: JsonObject): ImportedCard? {
        val src = (o["data"] as? JsonObject)?.takeIf { isCard(it) } ?: o
        if (!isCard(src)) return null
        val persona = listOfNotNull(
            text(src["description"]),
            text(src["personality"])?.let { "性格：$it" },
            text(src["scenario"])?.let { "场景：$it" },
            text(src["system_prompt"]),
            text(src["char_persona"]),
            text(src["example_dialogue"])?.let { "说话的样子（照这个来）：\n$it" },
            text(src["mes_example"])?.let { "说话的样子（照这个来）：\n$it" },
        ).fold(mutableListOf<String>()) { kept, part ->
            // The same words often sit in two of these fields; the model does not need them twice.
            if (kept.none { it.contains(part) }) kept += part
            kept
        }.joinToString("\n\n").take(Companions.PERSONA_LIMIT)
        return ImportedCard(
            name = text(src["name"]).orEmpty().ifEmpty { text(src["char_name"]).orEmpty() }.take(NAME_MAX),
            persona = persona,
            greeting = text(src["greeting_message"]) ?: text(src["first_mes"]) ?: text(src["greeting"]) ?: "",
        )
    }

    private fun isCard(o: JsonObject): Boolean = CARD_KEYS.any { o[it] != null }

    /** What a 记忆库 holds: the memories, and the person's own idea of themselves beside them. */
    private fun memories(o: JsonObject): List<ImportedMemory> {
        val out = mutableListOf<ImportedMemory>()
        (o["memories"] as? JsonArray)?.let { out += ForeignMemory.entries(it) }
        val profile = o["user_profile"] as? JsonObject
        val persona = profile?.let { text(it["persona"]) }.orEmpty()
        if (persona.isNotEmpty()) out += ForeignMemory.topic("profile", "人设", persona)
        return out
    }

    /**
     * The top-level `{…}` runs of a file that is not one JSON document: an export that holds
     * several modules, one after another. Only used when they are most of the file, so a text
     * file with a stray brace in it stays a text file.
     */
    private fun blocks(text: String): List<JsonObject> {
        val runs = mutableListOf<String>()
        var depth = 0
        var from = -1
        var inString = false
        var escaped = false
        text.forEachIndexed { i, c ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) from = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth <= 0 && from >= 0) {
                        runs += text.substring(from, i + 1)
                        from = -1
                        depth = 0
                    }
                }
            }
        }
        val parsed = runs.map { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject }
        val kept = parsed.filterNotNull()
        if (kept.isEmpty() || runs.sumOf { it.length } * 3 < text.length * 2) return emptyList()
        return kept
    }

    private fun text(e: JsonElement?): String? =
        (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }

    private fun number(e: JsonElement?): Long? = (e as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()

    private fun keyList(e: JsonElement?): List<String> = when (e) {
        is JsonArray -> e.mapNotNull { text(it) }
        is JsonPrimitive -> text(e)?.split(KEY_SPLIT)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        else -> emptyList()
    }.map { it.take(LoreKeys.KEY_MAX) }.distinct()

    private val KEY_SPLIT = Regex("[、,，;；/|]+")
    private const val NAME_MAX = 40
    private const val TITLE_MAX = 60
    private const val TITLE_FROM_CONTENT = 20
}

/**
 * Brings what another app exported into this one: memory into a TA's memory, a world book into
 * their 设定, a chat log into a conversation of its own, and a character card into a TA of its
 * own — everything else in that file going to that new TA. Nothing already here is touched.
 */
class ForeignImport(
    context: Context,
    private val db: AppDatabase,
    /** Where a 角色卡 becomes a TA: the same road as "加一个 TA", so their model is already set. */
    private val companions: Companions,
) {
    private val resolver = context.contentResolver

    /** What one import made, for the line the page shows. */
    suspend fun import(uri: Uri, companionId: Long): ForeignImportResult = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { readAtMost(it, MAX_BYTES) } ?: throw ImportException("打不开这个文件")
        val file = ForeignFile.read(bytes.decodeToString())
        val now = System.currentTimeMillis()
        var target = companionId
        var made: String? = null
        file.card?.let { card ->
            // The card is the TA; everything else in the file is about them, so they are made first.
            val id = companions.add()
            companions.update(id) { it.copy(name = card.name, persona = card.persona) }
            target = id
            made = card.name
        }
        val memories = if (file.memories.isEmpty()) MemoryImport(0, 0) else merge(file.memories, target, now)
        val lore = addLore(file.lore, target, now)
        // The card's opening line, or the log itself: one conversation, whichever the file had.
        val log = file.chats.firstOrNull()
        val said = log?.messages.orEmpty()
        val greeting = file.card?.greeting.orEmpty()
        var title: String? = null
        var written = 0
        var cut = false
        if (said.isNotEmpty() || greeting.isNotBlank()) {
            val lines = if (said.isEmpty()) listOf(ImportedMessage(fromMe = false, content = greeting, at = 0L)) else said
            cut = lines.size > MAX_MESSAGES
            val kept = if (cut) lines.takeLast(MAX_MESSAGES) else lines
            title = log?.title?.trim().takeUnless { it.isNullOrEmpty() }
                ?: file.card?.name?.takeIf { it.isNotBlank() }
                ?: ChatRepository.DEFAULT_TITLE
            written = writeChat(target, title, kept, now)
        }
        ForeignImportResult(
            newTa = made,
            newTaId = if (made == null) null else target,
            memories = memories,
            lore = lore,
            books = file.books,
            chatTitle = title,
            chatMessages = written,
            chatTruncated = cut,
        )
    }

    /** Memory topics merge into what the TA already has: the same name fills in, a new name is added. */
    private suspend fun merge(imported: List<ImportedMemory>, companionId: Long, now: Long): MemoryImport {
        var added = 0
        var details = 0
        var skipped = 0
        db.withTransaction {
            val have = db.memories().allFor(companionId).toMutableList()
            // Every topic's line goes with every message, so a kind holds [MemoryKinds.PER_KIND] of
            // them and no more, the same as when the TA remembers something itself: what is over
            // is left out, and said so, rather than quietly making every message longer.
            val room = MemoryKinds.all
                .associate { kind -> kind.key to (MemoryKinds.PER_KIND - have.count { it.kind == kind.key }).coerceAtLeast(0) }
                .toMutableMap()
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
        return MemoryImport(added, details, skipped)
    }

    /** 设定 entries, skipping the ones already there: importing the same book twice changes nothing. */
    private suspend fun addLore(entries: List<ImportedLore>, companionId: Long, now: Long): Int {
        if (entries.isEmpty()) return 0
        return db.withTransaction {
            val have = db.lore().allFor(companionId).map { it.title.trim() to it.content.trim() }.toMutableSet()
            var added = 0
            for (e in entries) {
                if (!have.add(e.title.trim() to e.content.trim())) continue
                db.lore().insert(
                    LoreEntity(
                        companionId = companionId,
                        book = e.book,
                        title = e.title,
                        keys = LoreKeys.encode(e.keys),
                        content = e.content.take(CONTENT_MAX),
                        enabled = e.enabled,
                        position = e.position,
                        source = MemoryEntity.SOURCE_IMPORT,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                added++
            }
            added
        }
    }

    /** One conversation holding what the log said, in order; returns how many lines went in. */
    private suspend fun writeChat(companionId: Long, title: String, lines: List<ImportedMessage>, now: Long): Int =
        db.withTransaction {
            val known = lines.mapNotNull { it.at.takeIf { at -> at > 0 } }
            val first = (known.firstOrNull() ?: now).coerceAtMost(now)
            val last = (known.lastOrNull() ?: now).coerceAtMost(now)
            val id = db.conversations().insert(
                ConversationEntity(
                    title = title.take(TITLE_MAX),
                    createdAt = minOf(first, last),
                    updatedAt = maxOf(first, last),
                    companionId = companionId,
                ),
            )
            // A line the file gives no time for goes just after the one before it, so the order holds.
            var previous = minOf(first, last) - 1
            val rows = lines.map { m ->
                val at = if (m.at > 0) m.at.coerceAtMost(now) else previous + 1
                previous = maxOf(previous, at)
                MessageEntity(
                    conversationId = id,
                    role = if (m.fromMe) "user" else "assistant",
                    content = m.content.take(CONTENT_MAX),
                    createdAt = at,
                )
            }
            db.messages().insertAll(rows)
            rows.size
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

        /** One line of a chat log, and one 设定 entry: past this it is not a line anyone reads. */
        private const val CONTENT_MAX = 20_000

        /** How much of a long chat comes over: the newest of it, which is the part that is still live. */
        private const val MAX_MESSAGES = 2_000

        /** A conversation title is a heading, not a sentence. */
        private const val TITLE_MAX = 60
    }
}

/** What one file brought in, module by module, so the page can say what happened. */
data class ForeignImportResult(
    /** The TA a 角色卡 made, by name; empty when the card had no name. Everything else went to them. */
    val newTa: String? = null,
    /** That TA's id, so the page can follow them. Null when the file had no 角色卡. */
    val newTaId: Long? = null,
    val memories: MemoryImport = MemoryImport(0, 0),
    val lore: Int = 0,
    /** The world books those entries came from. */
    val books: List<String> = emptyList(),
    val chatTitle: String? = null,
    val chatMessages: Int = 0,
    /** The log was longer than one conversation takes, and its beginning was left out. */
    val chatTruncated: Boolean = false,
) {
    /** What the person is told afterwards, whichever of the four modules the file had. */
    fun said(): String {
        val parts = mutableListOf<String>()
        val who = newTa?.trim().orEmpty()
        if (newTa != null) {
            parts += if (who.isEmpty()) {
                "文件里有一张角色卡，照它新开了一个 TA（还没起名字）"
            } else {
                "文件里有一张角色卡，照它新开了一个 TA「$who」"
            }
        }
        if (memories.added > 0 || memories.details > 0) {
            parts += buildString {
                append("记下 ${memories.added} 件事")
                if (memories.details > 0) append("、${memories.details} 条细节")
            }
        }
        if (lore > 0) {
            val named = books.take(2).joinToString("、") { "《$it》" }
            val from = when {
                named.isEmpty() -> ""
                books.size > 2 -> "（$named 等 ${books.size} 本）"
                else -> "（$named）"
            }
            parts += "搬来 $lore 条设定$from"
        }
        if (chatMessages > 0) parts += "接上 ${chatMessages} 句聊天"
        if (parts.isEmpty()) {
            return when {
                memories.skipped > 0 -> "这个文件里没有能认出来的东西。有 ${memories.skipped} 条记忆因为同类已满没进来：" +
                    "先在「记忆」里把同类的合并或删掉一些，再导一次。"
                else -> "这个文件里没有能认出来的东西。"
            }
        }
        val tail = buildString {
            if (newTa != null) append("，都归他")
            if (memories.skipped > 0) append("。还有 ${memories.skipped} 条记忆没进来：一类最多 ${MemoryKinds.PER_KIND} 件")
            if (chatTruncated) append("。聊天太长，只接上了最近的一部分")
        }
        return parts.joinToString("，") + tail + "。"
    }
}
