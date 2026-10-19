package com.cleo.cleos.desktop

import kotlinx.serialization.json.*
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ImportFailure(message: String) : Exception(message)
internal fun importCheck(value: Boolean, message: String = "备份中的记录不完整，未导入。") { if (!value) throw ImportFailure(message) }
internal fun JsonObject.word(key: String, default: String = ""): String {
    val v = this[key] ?: return default
    if (v is JsonNull) return default
    return (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ImportFailure("备份字段类型不正确。")
}
internal fun JsonObject.number(key: String, default: Long? = null): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: default ?: throw ImportFailure("备份缺少必要的数字字段。")
internal fun JsonObject.flag(key: String, default: Boolean = false): Boolean =
    if (this[key] == null || this[key] is JsonNull) default else (this[key] as? JsonPrimitive)?.booleanOrNull ?: throw ImportFailure("备份字段类型不正确。")
internal fun JsonObject.rows(key: String, required: Boolean = false): List<JsonObject> {
    val value = this[key] ?: if (required) throw ImportFailure("这份文件缺少必要的备份内容。") else return emptyList()
    return (value as? JsonArray)?.map { it as? JsonObject ?: throw ImportFailure("备份记录格式不正确。") } ?: throw ImportFailure("备份记录格式不正确。")
}
internal fun nested(value: JsonElement?): JsonElement? = when {
    value == null || value is JsonNull -> null
    value is JsonPrimitive && value.isString -> value.content.takeIf { it.isNotBlank() }?.let {
        try { Json.parseToJsonElement(it) } catch (_: Exception) { throw ImportFailure("备份中的附件或记忆格式不正确。") }
    }
    else -> value
}
internal fun stringList(value: JsonElement?): List<String> = nested(value)?.let { v ->
    (v as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw ImportFailure("备份列表格式不正确。") }
        ?: throw ImportFailure("备份列表格式不正确。")
}.orEmpty()

internal fun safeMediaName(name: String): Boolean = name.length in 1..220 &&
    Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.[A-Za-z0-9]+").matches(name) &&
    name.substringBefore('.').uppercase() !in setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9")

internal fun mediaPath(name: String): String {
    importCheck(safeMediaName(name), "备份包含不安全的媒体文件名，未导入。")
    return "media/$name"
}

fun fileDigest(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

@ConsistentCopyVisibility
data class BackupPreview internal constructor(
    val source: Path, val sha256: String, val version: Int, val exportedAt: Long,
    val counts: Map<String, Int>, val missingMedia: List<String>, val warnings: List<String>,
    val data: DesktopData, internal val document: ByteArray,
)

/** One parser serves file import now and an authenticated transfer file later. */
class MobileBackupImporter(
    private val maxArchiveBytes: Long = 2L * 1024 * 1024 * 1024,
    private val maxExpandedBytes: Long = 4L * 1024 * 1024 * 1024,
    private val maxJsonBytes: Long = 32L * 1024 * 1024,
    private val maxMediaBytes: Long = 128L * 1024 * 1024,
    private val maxEntries: Int = 30000,
) {
    private fun entries(zip: ZipFile): List<ZipEntry> {
        val entries = zip.entries().asSequence().take(maxEntries + 1).toList()
        importCheck(entries.size <= maxEntries, "备份中的文件过多，未导入。")
        val seen = hashSetOf<String>(); var total = 0L
        for (e in entries) {
            val name = e.name
            importCheck(name.isNotBlank() && name.length <= 500 && !name.contains('\\') && !name.startsWith('/') && !name.contains(':') && name.split('/').none { it == "." || it == ".." }, "备份中的文件路径不安全，未导入。")
            importCheck(seen.add(name.lowercase()), "备份中存在重名文件，未导入。")
            if (!e.isDirectory) {
                importCheck(e.size >= 0 && e.compressedSize >= 0, "备份文件信息不完整，未导入。")
                total += e.size; importCheck(total <= maxExpandedBytes, "备份展开后过大，未导入。")
                importCheck(e.size <= if (name == "backup.json") maxJsonBytes else maxMediaBytes, "备份中有单个文件过大，未导入。")
                if (name.startsWith("images/")) importCheck(safeMediaName(name.removePrefix("images/")), "备份中的媒体路径不安全，未导入。")
            }
        }
        importCheck(entries.count { it.name == "backup.json" && !it.isDirectory } == 1, "没有找到 Cleos 的 backup.json，请选择手机导出的 ZIP 备份。")
        return entries
    }
    private fun copyChecked(zip: ZipFile, e: ZipEntry, output: java.io.OutputStream) {
        val crc = CRC32(); var written = 0L
        zip.getInputStream(e).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                written += n; importCheck(written <= e.size && written <= maxExpandedBytes, "备份文件大小不符，未导入。")
                crc.update(buffer, 0, n); output.write(buffer, 0, n)
            }
        }
        importCheck(written == e.size && crc.value == e.crc, "备份校验失败，文件可能已损坏；未导入。")
    }
    fun inspect(path: Path): BackupPreview {
        importCheck(Files.isRegularFile(path) && Files.size(path) in 1..maxArchiveBytes, "请选择有效且不超过 2 GB 的 Cleos ZIP 备份。")
        try {
            val digest = fileDigest(path)
            return ZipFile(path.toFile()).use { zip ->
                val files = entries(zip)
                val jsonBytes = java.io.ByteArrayOutputStream().also { copyChecked(zip, files.first { e -> e.name == "backup.json" }, it) }.toByteArray()
                val root = diskJson.parseToJsonElement(jsonBytes.toString(Charsets.UTF_8)) as? JsonObject ?: throw ImportFailure("这不是有效的 Cleos 备份。")
                importCheck(root.word("format") == "cleos-backup", "这不是 Cleos 手机备份。")
                val version = root.number("version"); importCheck(version in 1..15, "暂时支持手机备份格式 1–15；请更新电脑版后再导入更高版本。")
                val exportedAt = root.number("exportedAt"); importCheck(exportedAt >= 0)
                val available = files.filter { !it.isDirectory && it.name.startsWith("images/") }.map { it.name.removePrefix("images/") }.toSet()
                val converted = convert(root, available)
                val counts = linkedMapOf("TA" to converted.first.companions.size, "私聊" to converted.first.conversations.count { !it.group }, "群聊" to converted.first.conversations.count { it.group },
                    "消息" to (root.rows("messages", true).size + root.rows("groupMessages").size), "记忆" to root.rows("memories").size,
                    "群记录" to root.rows("groupMemories").size, "设定" to root.rows("lore").size, "媒体文件" to available.size,
                    "日记" to root.rows("diary", true).size, "待办" to root.rows("todos", true).size, "信件" to root.rows("letters").size,
                    "收藏" to root.rows("favorites").size, "动态" to root.rows("feedPosts").size, "表情包" to root.rows("stickers").size)
                val origin = ImportOrigin(digest, version.toInt(), exportedAt, System.currentTimeMillis(), counts, converted.second)
                val warnings = buildList {
                    add("手机 API Key 不在备份中；不会开启手机的主动消息、来电或 HTTP 权限。")
                    add("日记和待办可从原始手机快照中只读查看，TA 未分享的小秘密继续隐藏；信件、收藏、动态及完整工具记录仍保存在原始归档中。")
                    add("图片可查看，录音可本地播放；发送图片需在对应 TA 的模型设置中确认并开启看图。录音不会发送给模型。")
                    if (converted.second.isNotEmpty()) add("${converted.second.size} 个被引用的媒体文件不在备份里，将保留缺失提示。")
                    if (converted.first.conversations.any { it.readOnly }) add("有群聊已没有可用成员，保留为只读历史，不会重新激活已离开的 TA。")
                }
                BackupPreview(path.toAbsolutePath(), digest, version.toInt(), exportedAt, counts, converted.second, warnings, converted.first.copy(importOrigin = origin).checked(), jsonBytes)
            }
        } catch (e: ImportFailure) { throw e }
        catch (_: Exception) { throw ImportFailure("无法读取这份备份，请确认 ZIP 完整且由 Cleos 导出；已有资料未更改。") }
    }

    /** Destination must be a newly created, unpublished staging directory owned by the caller. */
    fun write(preview: BackupPreview, destination: Path) {
        importCheck(Files.isDirectory(destination) && Files.list(destination).use { !it.findAny().isPresent }, "导入目标不是空的暂存目录，未导入。")
        importCheck(fileDigest(preview.source) == preview.sha256, "备份在预览后发生了变化，请重新选择。")
        ZipFile(preview.source.toFile()).use { zip ->
            val files = entries(zip)
            for (e in files.filter { !it.isDirectory && it.name.startsWith("images/") }) {
                val target = destination.resolve(mediaPath(e.name.removePrefix("images/"))).normalize()
                importCheck(target.startsWith(destination.resolve("media")))
                Files.createDirectories(target.parent)
                Files.newOutputStream(target).use { copyChecked(zip, e, it) }
            }
        }
        val source = destination.resolve("source"); Files.createDirectories(source)
        Files.copy(preview.source, source.resolve("phone-backup.zip"))
        importCheck(fileDigest(source.resolve("phone-backup.zip")) == preview.sha256, "复制备份后校验失败，未导入。")
        Files.write(source.resolve("backup.json"), preview.document)
        LocalStore(destination).use { it.save(preview.data) }
    }

    private fun convert(root: JsonObject, media: Set<String>): Pair<DesktopData, List<String>> {
        val settings = root["settings"] as? JsonObject ?: throw ImportFailure("备份缺少设置资料。")
        val references = linkedSetOf<String>()
        fun asset(name: String?): String? = name?.takeIf { it.isNotBlank() }?.let { references += it; mediaPath(it) }
        fun optional(o: JsonObject, key: String) = o.word(key).takeIf { it.isNotBlank() }
        fun images(value: JsonElement?): List<Attachment> = nested(value)?.let { list ->
            (list as? JsonArray)?.map { item ->
                val picture = item as? JsonObject ?: throw ImportFailure("备份图片记录格式不正确。")
                val credit = picture["credit"] as? JsonObject
                Attachment(asset(picture.word("file")) ?: throw ImportFailure("图片记录缺少文件名。"), label = picture.word("source"),
                    credit = credit?.let { listOf(it.word("title"), it.word("creator"), it.word("license"), it.word("pageUrl"), it.word("licenseUrl")).filter { s -> s.isNotBlank() }.joinToString(" · ") }.orEmpty())
            } ?: throw ImportFailure("备份图片列表格式不正确。")
        }.orEmpty()
        fun audio(value: JsonElement?): List<Attachment> = nested(value)?.let {
            val item = it as? JsonObject ?: throw ImportFailure("备份录音格式不正确。")
            listOf(Attachment(asset(item.word("file")) ?: throw ImportFailure("录音记录缺少文件名。"), "audio", "录音原文件已保留"))
        }.orEmpty()
        fun unique(rows: List<JsonObject>) {
            val ids = rows.map { it.number("id") }; importCheck(ids.all { it > 0 } && ids.distinct().size == ids.size)
        }
        var sources = root.rows("companions")
        if (sources.isEmpty()) sources = listOf(buildJsonObject {
            put("id", 1); put("name", settings.word("aiName", "TA")); put("persona", settings.word("persona"))
            put("apiBaseUrl", settings.word("apiBaseUrl")); put("apiModel", settings.word("apiModel"))
            optional(settings, "aiAvatar")?.let { put("avatar", it) }
        })
        unique(sources)
        val memories = root.rows("memories"); unique(memories)
        val lore = root.rows("lore"); unique(lore)
        val tas = sources.mapIndexed { index, row ->
            val id = row.number("id")
            DesktopTa("mobile-ta-$id", row.word("name").ifBlank { "TA" }, row.word("persona"), index % 5,
                Endpoint(row.word("apiBaseUrl"), row.word("apiModel"), false), avatar = asset(optional(row, "avatar")), userAvatar = asset(optional(row, "userAvatar")),
                memories = memories.filter { it.number("companionId") == id }.map { ImportedMemory(it.word("name"), it.word("summary"), stringList(it["details"]), it.word("kind"), it.flag("pinned")) },
                lore = lore.filter { it.number("companionId") == id }.sortedBy { it.number("position", 0) }.map { ImportedLore(it.word("title"), it.word("content"), stringList(it["keys"]), it.flag("enabled", true)) })
        }
        val taIds = sources.map { it.number("id") }.toSet()
        importCheck((memories + lore).all { it.number("companionId") in taIds })
        fun message(row: JsonObject, prefix: String, taId: Long?, group: Boolean): Message {
            val role = row.word("role"); val user = role == "user"; val context = role in setOf("user", "assistant")
            val who = if (user) Message.USER else if (context) "mobile-ta-${taId ?: throw ImportFailure("消息缺少发言者。")}" else "activity"
            val error = row.word("error")
            val oldStatus = row.word("status", "sent")
            val status = when { oldStatus in setOf("running", "pending", "cancelled", "interrupted") -> "interrupted"; oldStatus == "failed" || error.isNotBlank() -> "error"; else -> "complete" }
            val thought = nested(row["thought"]) as? JsonObject
            val reasoning = thought?.word("text").orEmpty().ifBlank { row.word("reasoning") }
            return Message("$prefix-${row.number("id")}", who,
                if (context) row.word("content") else row.word("note").ifBlank { when (role) { "call" -> "手机通话记录（原始记录已归档）"; "tool" -> "手机工具记录（详细结果已归档）"; "request" -> "小秘密请求记录（原始记录已归档）"; else -> "手机活动记录（原始记录已归档）" } },
                reasoning, status, if (status == "interrupted") "手机上未完成的回复已保留，不会自动续发。" else error, row.number("createdAt"),
                row.word("speakerName"), asset(optional(row, "speakerAvatar")), images(row["images"]) + audio(row["audio"]), context, !context)
        }
        val chats = root.rows("conversations", true); unique(chats)
        val privateMessages = root.rows("messages", true); unique(privateMessages)
        importCheck(privateMessages.all { m -> chats.any { it.number("id") == m.number("conversationId") } })
        val conversations = chats.sortedBy { it.number("updatedAt", 0) }.map { row ->
            val id = row.number("id"); val ta = row.number("companionId", 1); importCheck(ta in taIds)
            Conversation("mobile-private-$id", row.word("title"), listOf("mobile-ta-$ta"), messages = privateMessages.filter { it.number("conversationId") == id }
                .sortedWith(compareBy<JsonObject> { it.number("createdAt") }.thenBy { it.number("id") }).map { message(it, "private", ta, false) }, recap = row.word("recap"))
        }.toMutableList()
        val groups = root.rows("groups"); unique(groups)
        val members = root.rows("groupMembers"); val groupIds = groups.map { it.number("id") }.toSet()
        importCheck(members.all { it.number("groupId") in groupIds } && members.map { it.number("groupId") to it.number("companionId") }.distinct().size == members.size)
        val groupMessages = root.rows("groupMessages"); unique(groupMessages)
        val groupMemories = root.rows("groupMemories"); unique(groupMemories)
        importCheck((groupMessages + groupMemories).all { it.number("groupId") in groupIds })
        for (group in groups) {
            val id = group.number("id"); val roster = members.filter { it.number("groupId") == id }
            val active = roster.filter { it.flag("active", true) && it.number("companionId") in taIds }.sortedBy { it.number("position", 0) }.map { "mobile-ta-${it.number("companionId")}" }
            importCheck(active.size <= 4)
            val history = groupMessages.filter { it.number("groupId") == id }.sortedBy { it.number("id") }.map { row ->
                val speaker = if (row.word("role") == "assistant") row.number("speakerId") else null
                if (speaker != null) importCheck(roster.any { it.number("companionId") == speaker })
                message(row, "group", speaker, true)
            }
            // Retain draft media and ownership even before composing attachments is available.
            images(group["draftImages"]); audio(group["draftAudio"])
            conversations += Conversation("mobile-group-$id", group.word("title"), active, true, history, group.word("draft"), active.isEmpty(),
                sharedNotes = groupMemories.filter { it.number("groupId") == id }.map { ImportedMemory(it.word("title"), it.word("content")) })
        }
        for (ta in tas) if (conversations.none { !it.group && ta.id in it.memberIds }) conversations += Conversation(memberIds = listOf(ta.id))
        val userAvatar = asset(optional(settings, "userAvatar"))
        val missing = references.filter { it !in media }
        return DesktopData(userName = settings.word("userName").ifBlank { "我" }, companions = tas, conversations = conversations,
            selectedId = conversations.maxByOrNull { it.messages.lastOrNull()?.at ?: 0 }?.id.orEmpty(), userAvatar = userAvatar) to missing
    }
}
