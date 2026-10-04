package com.cleo.cleos.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.cleo.cleos.ai.MemoryKinds
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.ConversationEntity
import com.cleo.cleos.data.db.DiaryEntryEntity
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
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

class ImportException(message: String) : Exception(message)

data class ImportedMessage(val role: String, val content: String, val at: Long)

data class ImportedConversation(
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ImportedMessage>,
)

data class ImportedDiary(val day: Long, val text: String, val at: Long)

/** What a memory import brought in: topics added, details added, and topics left out because their kind was full. */
data class MemoryImport(val added: Int, val details: Int, val skipped: Int = 0)

/** A topic the model had noted about the person there, with all its details. */
data class ImportedMemory(
    val kind: String,
    val name: String,
    val summary: String,
    val details: List<String>,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** What a file from another app would bring in, shown to the person before anything is written. */
data class ImportPlan(
    val conversations: List<ImportedConversation>,
    val diary: List<ImportedDiary>,
    /** The new TA's persona: the one set in that app's conversations, if there was one. */
    val persona: String,
    /** Whether that app's conversations had a persona of their own. Its default one lives in its code, not in the backup. */
    val hadPersona: Boolean,
    /** That persona was longer than a persona here can be, and was cut. */
    val personaCut: Boolean,
    /** What the model had noted about the person: the new TA's memory. */
    val memories: List<ImportedMemory>,
    /** The service and model that app was using, when they could be told; else the current TA's are used. */
    val baseUrl: String?,
    val model: String?,
    /** Pictures the backup only names: it refers to files that stay in that app. */
    val picturesLeftBehind: Int,
) {
    val messageCount: Int get() = conversations.sumOf { it.messages.size }
}

/**
 * The JSON backup of phone-ai-assistant, a companion app written in Flutter
 * ("日记备份-….json", `app` = phone_ai_assistant, `formatVersion` 1).
 *
 * Its conversations are one TA's, so they all go to one new TA, with what the person and
 * the model said. Left out: that app's tool calls and their results (for tools that don't
 * exist here), system lines, the screenshots the model took of the phone screen, and
 * stickers the model sent (a key with no words). Pictures the person sent are only named
 * in that backup, so a line says one was sent; stickers the person sent are already words
 * ("[表情：困了]"). Deleted conversations and the per-book discussions stay behind.
 *
 * Its diary is written by the model, so it becomes the new TA's diary. Of what the model
 * noted (memory topics), the four kinds about the person become the new TA's memory, with
 * every detail, so they still know the person here; its notes about itself stay behind.
 */
object PhoneAssistantBackup {
    const val APP = "phone_ai_assistant"
    private const val FORMAT_VERSION = 1

    /** The kinds of note about the person. That app reads a kind it doesn't know as "profile", and so does this. */
    private val ABOUT_THE_PERSON = setOf("profile", "interest", "recent", "rapport")

    private fun checked(text: String): JsonObject {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw ImportException("读不出来：这不是一个 JSON 备份文件")
        if (root.str("app") != APP) throw ImportException("认不出这个文件：现在只认 $APP 导出的备份（日记备份-….json）")
        val version = root.int("formatVersion")
        if (version == null || version > FORMAT_VERSION) throw ImportException("这份备份的格式（版本 $version）比这里认得的新")
        return root
    }

    private fun prefsOf(root: JsonObject) = root["prefs"] as? JsonObject ?: JsonObject(emptyMap())

    /** Only what the model noted: for filling in the memory of a TA brought over before there was any. */
    fun parseMemories(text: String, zone: ZoneId): List<ImportedMemory> =
        memories(prefsOf(checked(text)), zone).ifEmpty { throw ImportException("这份备份里没有关于你的记忆") }

    fun parse(text: String, zone: ZoneId, personaLimit: Int): ImportPlan {
        val root = checked(text)
        val prefs = prefsOf(root)

        var pictures = 0
        val personas = mutableListOf<Pair<Long, String>>()
        val conversations = (root["conversations"] as? JsonObject)?.values.orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val created = time(c.str("createdAt"), zone) ?: return@mapNotNull null
            val updated = time(c.str("updatedAt"), zone) ?: created
            c.str("systemPrompt")?.trim()?.takeIf { it.isNotEmpty() }?.let { personas += updated to it }
            val messages = (c["messages"] as? JsonArray).orEmpty().mapNotNull { m ->
                message(m, zone)?.let { (said, pics) ->
                    pictures += pics
                    said
                }
            }.sortedBy { it.at }
            if (messages.isEmpty()) return@mapNotNull null
            ImportedConversation(c.str("title")?.trim().orEmpty().ifEmpty { "新对话" }, created, updated, messages)
        }.sortedBy { it.createdAt }

        val diary = prefList(prefs, "diary_entries").mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            val body = d.str("content")?.trim().orEmpty()
            val day = day(d.str("date"), zone) ?: return@mapNotNull null
            if (body.isEmpty()) return@mapNotNull null
            val at = time(d.str("createdAt"), zone) ?: day.atStartOfDay(zone).toInstant().toEpochMilli()
            ImportedDiary(day.toEpochDay(), body, at)
        }.sortedBy { it.at }

        val memories = memories(prefs, zone)

        // A persona set in a conversation replaced that app's default one there. The latest
        // one set is the one the TA was last talking with.
        val set = personas.maxByOrNull { it.first }?.second

        val (baseUrl, model) = service(prefs)
        if (conversations.isEmpty() && diary.isEmpty() && memories.isEmpty()) {
            throw ImportException("这份备份里没有能带过来的对话、日记或记忆")
        }
        return ImportPlan(
            conversations = conversations,
            diary = diary,
            persona = set?.take(personaLimit).orEmpty(),
            hadPersona = set != null,
            personaCut = set != null && set.length > personaLimit,
            memories = memories,
            baseUrl = baseUrl,
            model = model,
            picturesLeftBehind = pictures,
        )
    }

    /** A message as it goes in, and how many pictures it had that don't come along. */
    private fun message(e: JsonElement, zone: ZoneId): Pair<ImportedMessage, Int>? {
        val m = e as? JsonObject ?: return null
        // The model's own screenshots of the phone screen: stored as the person's, said by no one.
        if ((m["metadata"] as? JsonObject)?.bool("glanceShot") == true) return null
        val at = time(m.str("timestamp"), zone) ?: return null
        val content = m.str("content").orEmpty().trim()
        return when (m.str("role")) {
            "user" -> {
                // Older messages kept one picture under imageData.
                val pics = (m["images"] as? JsonArray)?.size ?: if (m.str("imageData") != null) 1 else 0
                val note = if (pics > 0) "（这里发过 $pics 张图，没有一起搬过来）" else ""
                val text = listOf(note, content).filter { it.isNotEmpty() }.joinToString("\n")
                if (text.isEmpty()) null else ImportedMessage("user", text, at) to pics
            }
            "assistant" -> if (content.isEmpty()) null else ImportedMessage("assistant", content, at) to 0
            // "system" lines, "toolCall" and "toolResult": about that app, not something said.
            else -> null
        }
    }

    /** In that app's order: kind by kind, oldest first. */
    private fun memories(prefs: JsonObject, zone: ZoneId): List<ImportedMemory> =
        prefList(prefs, "memory_facts").mapNotNull { memory(it, zone) }
            .sortedWith(compareBy<ImportedMemory> { KIND_ORDER.indexOf(it.kind) }.thenBy { it.createdAt })

    private val KIND_ORDER = listOf("profile", "interest", "recent", "rapport")

    private fun memory(e: JsonElement, zone: ZoneId): ImportedMemory? {
        val t = e as? JsonObject ?: return null
        // "self" is what the model noted about itself. Any other kind it doesn't know (and the
        // first, flat version, which had none) that app reads as "profile", and so does this.
        val category = t.str("category")
        if (category == "self") return null
        val kind = category?.takeIf { it in ABOUT_THE_PERSON } ?: "profile"
        val name = t.str("name")?.trim().orEmpty().takeUnless { it == "未命名" }.orEmpty()
        val summary = (t.str("summary") ?: t.str("content"))?.trim().orEmpty()
        if (name.isEmpty() && summary.isEmpty()) return null
        val created = time(t.str("createdAt"), zone) ?: 0L
        val updated = time(t.str("updatedAt"), zone) ?: created
        val details = (t["details"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?: listOfNotNull(t.str("why")?.trim())
        return ImportedMemory(
            kind = kind,
            name = name.ifEmpty { summary.take(NAME_FROM_SUMMARY) },
            summary = summary.ifEmpty { name },
            details = details.filter { it.isNotEmpty() },
            pinned = t.bool("pinned") == true,
            createdAt = created,
            updatedAt = updated,
        )
    }

    private const val NAME_FROM_SUMMARY = 12

    /**
     * The service that app was set to, if it speaks the same (OpenAI-compatible) way as this
     * one. An endpoint it didn't override is that provider's default, known here only for
     * the providers there are presets for.
     */
    private fun service(prefs: JsonObject): Pair<String?, String?> {
        val active = prefString(prefs, "api_active_provider")?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
        val endpoint = prefString(prefs, "api_endpoint_$active")?.trim()?.takeIf { usable(it) }
        val preset = if (endpoint == null) presetFor(active) else null
        val baseUrl = endpoint ?: preset?.baseUrl ?: return null to null
        val model = prefString(prefs, "api_model_$active")?.trim()?.takeIf { it.isNotEmpty() } ?: preset?.defaultModel
        return baseUrl to model
    }

    // Anthropic's own API speaks a different format; the chat here only speaks OpenAI's.
    private fun usable(url: String) =
        (url.startsWith("https://") || url.startsWith("http://")) && "anthropic.com" !in url.lowercase()

    private fun presetFor(id: String): ApiPreset? {
        val k = id.lowercase()
        val name = when {
            "deepseek" in k -> "DeepSeek"
            "openrouter" in k -> "OpenRouter"
            "openai" in k -> "OpenAI"
            "silicon" in k -> "硅基流动"
            "moonshot" in k || "kimi" in k -> "Kimi"
            "zhipu" in k || "bigmodel" in k || "glm" in k -> "智谱"
            else -> return null
        }
        return ApiPresets.all.firstOrNull { it.name == name }
    }

    /** A kept preference's value: `{"type": "string", "value": …}`. */
    private fun prefValue(prefs: JsonObject, key: String): JsonElement? = (prefs[key] as? JsonObject)?.get("value")

    private fun prefString(prefs: JsonObject, key: String): String? = (prefValue(prefs, key) as? JsonPrimitive)?.contentOrNull

    /** A list kept as a JSON string (the diary, the notes). */
    private fun prefList(prefs: JsonObject, key: String): List<JsonElement> = when (val v = prefValue(prefs, key)) {
        is JsonArray -> v
        is JsonPrimitive -> v.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }.orEmpty()
        else -> emptyList()
    }

    /**
     * Dart's toIso8601String: local time without an offset ("2026-09-23T21:05:12.345678"),
     * or UTC with a Z.
     */
    internal fun time(s: String?, zone: ZoneId): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun day(s: String?, zone: ZoneId): LocalDate? {
        if (s.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(s).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(s.take(10)) }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}

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

/**
 * Brings a backup from another app in as a new TA. Nothing already here is touched, so
 * undoing it is deleting that TA.
 */
class ForeignImport(
    context: Context,
    private val db: AppDatabase,
    private val companions: Companions,
) {
    private val resolver = context.contentResolver

    suspend fun read(uri: Uri): ImportPlan = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { readAtMost(it, MAX_BYTES) } ?: throw ImportException("打不开这个文件")
        PhoneAssistantBackup.parse(bytes.decodeToString(), ZoneId.systemDefault(), Companions.PERSONA_LIMIT)
    }

    /** Writes [plan] as a new TA called [name], all in one transaction, and makes them the one being talked to. */
    suspend fun import(plan: ImportPlan, name: String): CompanionEntity = withContext(Dispatchers.IO) {
        val from = companions.current()
        val ta = CompanionEntity(
            name = name.trim(),
            persona = plan.persona,
            apiBaseUrl = plan.baseUrl ?: from.apiBaseUrl,
            apiModel = plan.model ?: from.apiModel,
            createdAt = System.currentTimeMillis(),
        )
        val id = db.withTransaction {
            val id = db.companions().insert(ta)
            for (c in plan.conversations) {
                val conversation = db.conversations().insert(
                    ConversationEntity(title = c.title, createdAt = c.createdAt, updatedAt = c.updatedAt, companionId = id),
                )
                db.messages().insertAll(
                    c.messages.map { MessageEntity(conversationId = conversation, role = it.role, content = it.content, createdAt = it.at) },
                )
            }
            db.memories().insertAll(plan.memories.map { it.entity(id, now = ta.createdAt) })
            db.diary().insertAll(
                plan.diary.map {
                    DiaryEntryEntity(
                        day = it.day,
                        title = "",
                        blocks = DiaryBlocks.encode(listOf(DiaryBlock.Text(it.text))),
                        createdAt = it.at,
                        updatedAt = it.at,
                        author = DiaryEntryEntity.AUTHOR_AI,
                        companionId = id,
                    )
                },
            )
            id
        }
        companions.select(id)
        ta.copy(id = id)
    }

    /**
     * Fills in a TA's memory from a backup of that app: what they don't have yet is added,
     * and a topic they have (by name) gets the details it is missing. For a TA brought over
     * before memories were kept, whose persona could only hold some of the details.
     */
    suspend fun fillMemories(uri: Uri, companionId: Long): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { readAtMost(it, MAX_BYTES) } ?: throw ImportException("打不开这个文件")
        val imported = PhoneAssistantBackup.parseMemories(bytes.decodeToString(), ZoneId.systemDefault())
        var added = 0
        var details = 0
        val now = System.currentTimeMillis()
        db.withTransaction {
            val have = db.memories().allFor(companionId)
            for (m in imported) {
                val same = have.firstOrNull { it.name.trim() == m.name.trim() }
                if (same == null) {
                    db.memories().insert(m.entity(companionId, now))
                    added++
                    continue
                }
                val old = MemoryDetails.decode(same.details)
                val merged = (old + m.details.filter { it !in old }).take(MemoryKinds.DETAILS)
                if (merged.size > old.size) {
                    // Filling in isn't news: the topic keeps the date it was last noted.
                    db.memories().update(same.copy(details = MemoryDetails.encode(merged), updatedAt = maxOf(same.updatedAt, m.updatedAt)))
                    details += merged.size - old.size
                }
            }
        }
        added to details
    }

    /** Brings memory in from any third-party file: nothing already here is touched. */
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
        // Text only, even years of chat stay far below this. Old exports with pictures inside
        // ran to hundreds of MB, more than a phone can hold as one JSON tree.
        private const val MAX_BYTES = 32 * 1024 * 1024
    }
}
