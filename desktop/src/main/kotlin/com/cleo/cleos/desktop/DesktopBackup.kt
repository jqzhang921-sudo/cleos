package com.cleo.cleos.desktop

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable internal data class BackupFile(val name: String, val bytes: Long, val sha256: String)
@Serializable internal data class DesktopBackupManifest(
    val format: String = "cleos-desktop-backup", val version: Int = 1,
    val exportedAt: Long, val libraryName: String, val files: List<BackupFile>,
)

@ConsistentCopyVisibility
data class DesktopBackupPreview internal constructor(
    val source: Path, val sha256: String, val exportedAt: Long, val libraryName: String,
    val data: DesktopData, val counts: Map<String, Int>, val missingMedia: List<String>,
    val hasPhoneArchive: Boolean, internal val manifest: DesktopBackupManifest,
)

/** A portable snapshot of ONE library. Never walk the workspace or copy its DPAPI vault. */
class DesktopBackupArchive(
    private val maxArchiveBytes: Long = 8L * 1024 * 1024 * 1024,
    private val maxExpandedBytes: Long = 8L * 1024 * 1024 * 1024,
    private val maxJsonBytes: Long = 32L * 1024 * 1024,
    private val maxMediaBytes: Long = 128L * 1024 * 1024,
    private val maxEntries: Int = 30010,
) {
    private fun check(value: Boolean, message: String) = importCheck(value, message)
    private fun limit(name: String): Long = when {
        name == "manifest.json" -> 8L * 1024 * 1024
        name == "library.json" || name == "source/backup.json" -> maxJsonBytes
        name == "source/phone-backup.zip" -> 2L * 1024 * 1024 * 1024
        name.startsWith("media/") && safeMediaName(name.removePrefix("media/")) -> maxMediaBytes
        else -> throw ImportFailure("电脑备份包含不支持的文件或不安全路径，未恢复。")
    }
    private fun portable(data: DesktopData): DesktopData = data.checked().copy(
        companions = data.companions.map { it.copy(endpoint = it.endpoint.copy(allowHttp = false)) },
        transcription=data.transcription.copy(allowHttp=false),
    )
    private fun references(data: DesktopData): Set<String> = buildSet {
        data.userAvatar?.let { add(it) }
        data.companions.forEach { ta -> ta.avatar?.let { add(it) }; ta.userAvatar?.let { add(it) } }
        data.conversations.forEach { chat -> chat.messages.forEach { m ->
            m.speakerAvatar?.let { add(it) }; m.attachments.forEach { add(it.file) }
        };chat.draftImages.forEach {add(it.file)};chat.draftVoice?.let {add(it.file)} }
    }.also { names -> names.forEach { name ->
        check(name.startsWith("media/") && safeMediaName(name.removePrefix("media/")), "资料中的附件路径不安全，请保留原资料检查，未继续备份。")
    } }

    private fun copy(input: InputStream, output: OutputStream, expected: Long, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
        val buffer = ByteArray(65536)
        while (true) {
            checkCancelled()
            val n = input.read(buffer); if (n < 0) break
            count += n
            check(count <= expected, "备份文件在读取时发生变化或大小不符，未完成。")
            output.write(buffer, 0, n); digest.update(buffer, 0, n)
        }
        check(count == expected, "备份文件不完整，未完成。")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun read(zip: ZipFile, entry: ZipEntry, output: OutputStream, checkCancelled: () -> Unit): String {
        val crc = CRC32()
        val checked = object : OutputStream() {
            override fun write(b: Int) { output.write(b); crc.update(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { output.write(b, off, len); crc.update(b, off, len) }
        }
        val digest = zip.getInputStream(entry).use { copy(it, checked, entry.size, checkCancelled) }
        check(crc.value == entry.crc, "电脑备份校验失败，文件可能损坏，未恢复。")
        return digest
    }
    private fun entries(zip: ZipFile): Map<String, ZipEntry> {
        val list = zip.entries().asSequence().take(maxEntries + 1).toList()
        check(list.size <= maxEntries, "电脑备份文件数量超过上限，未恢复。")
        val seen = hashSetOf<String>(); var total = 0L
        list.forEach { e ->
            val cap = limit(e.name)
            check(!e.isDirectory && seen.add(e.name.lowercase()), "电脑备份存在目录或重名文件，未恢复。")
            check(e.size in 0..cap && e.compressedSize >= 0, "电脑备份中的单个文件过大或信息不完整，未恢复。")
            check(e.size <= maxExpandedBytes - total, "电脑备份展开后过大，未恢复。")
            total += e.size
        }
        check(list.any { it.name == "manifest.json" } && list.any { it.name == "library.json" }, "这不是 Cleos 电脑版导出的备份。")
        return list.associateBy { it.name }
    }
    private fun bytes(zip: ZipFile, entry: ZipEntry, checkCancelled: () -> Unit): ByteArray =
        ByteArrayOutputStream().also { read(zip, entry, it, checkCancelled) }.toByteArray()

    fun inspect(source: Path, checkCancelled: () -> Unit = {}): DesktopBackupPreview {
        try {
            check(Files.isRegularFile(source) && Files.size(source) in 1..maxArchiveBytes, "请选择有效且不超过 8 GiB 的电脑 ZIP 备份。")
            val digest = digest(source, checkCancelled)
            return ZipFile(source.toFile()).use { zip ->
                val entries = entries(zip)
                val manifest = diskJson.decodeFromString<DesktopBackupManifest>(bytes(zip, entries.getValue("manifest.json"), checkCancelled).toString(Charsets.UTF_8))
                check(manifest.format == "cleos-desktop-backup" && manifest.version == 1, "这个电脑备份版本暂不支持，请更新电脑版后重试。")
                check(manifest.exportedAt >= 0 && manifest.libraryName.length in 1..80, "电脑备份信息不完整，未恢复。")
                check(manifest.files.size == entries.size - 1 && manifest.files.map { it.name }.toSet() == entries.keys - "manifest.json", "电脑备份清单与文件不一致，未恢复。")
                manifest.files.forEach { f ->
                    check(f.name != "manifest.json" && f.bytes == entries.getValue(f.name).size && Regex("[a-f0-9]{64}").matches(f.sha256), "电脑备份清单无效，未恢复。")
                    val sha = read(zip, entries.getValue(f.name), OutputStream.nullOutputStream(), checkCancelled)
                    check(sha == f.sha256, "电脑备份摘要校验失败，未恢复。")
                }
                val data = portable(diskJson.decodeFromString<DesktopData>(bytes(zip, entries.getValue("library.json"), checkCancelled).toString(Charsets.UTF_8))).recoverInterrupted()
                val media = entries.keys.filter { it.startsWith("media/") }.toSet()
                val missing = (references(data) - media).sorted()
                val hasArchive = entries.containsKey("source/phone-backup.zip")
                check(hasArchive == entries.containsKey("source/backup.json"), "电脑备份中的手机原始归档不完整，未恢复。")
                if (data.importOrigin != null) check(hasArchive && manifest.files.first { it.name == "source/phone-backup.zip" }.sha256 == data.importOrigin.sha256,
                    "电脑备份缺少对应的手机原始归档，未恢复。")
                check(digest(source, checkCancelled) == digest, "备份在检查时发生了变化，请重新选择。")
                DesktopBackupPreview(source.toAbsolutePath(), digest, manifest.exportedAt, manifest.libraryName, data,
                    linkedMapOf("TA" to data.companions.size, "私聊" to data.conversations.count { !it.group }, "群聊" to data.conversations.count { it.group },
                        "消息" to data.conversations.sumOf { it.messages.size }, "媒体文件" to media.size), missing, hasArchive, manifest)
            }
        } catch (e: ImportFailure) { throw e }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { throw ImportFailure("无法读取这份电脑备份，请确认 ZIP 完整；已有资料未更改。") }
    }

    private fun digest(path: Path, checkCancelled: () -> Unit): String = Files.newInputStream(path).use {
        copy(it, OutputStream.nullOutputStream(), Files.size(path), checkCancelled)
    }

    /** Snapshot is captured on the UI thread. All disk work runs on the caller's IO dispatcher. */
    fun export(directory: Path, snapshot: DesktopData, libraryName: String, target: Path, checkCancelled: () -> Unit = {}): DesktopBackupPreview {
        val data = portable(snapshot); references(data)
        check(!Files.exists(target, NOFOLLOW_LINKS), "文件已存在，请换一个新的文件名；原备份未覆盖。")
        val files = linkedMapOf<String, Path>()
        val media = directory.resolve("media")
        if (Files.exists(media, NOFOLLOW_LINKS)) {
            check(Files.isDirectory(media, NOFOLLOW_LINKS), "媒体目录不是普通目录，未导出。")
            Files.list(media).use { paths -> paths.sorted().forEach { path ->
                val name = "media/${path.fileName}"; limit(name)
                check(Files.isRegularFile(path, NOFOLLOW_LINKS), "媒体目录含链接或特殊文件，未导出。")
                files[name] = path
                check(files.size < maxEntries - 2, "资料库文件数量超过备份上限，未导出。")
            } }
        }
        val source = directory.resolve("source")
        if (Files.exists(source, NOFOLLOW_LINKS)) check(Files.isDirectory(source, NOFOLLOW_LINKS), "原始归档目录不是普通目录，未导出。")
        for (name in listOf("source/phone-backup.zip", "source/backup.json")) {
            val path = directory.resolve(name)
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                check(Files.isRegularFile(path, NOFOLLOW_LINKS), "原始归档含链接或特殊文件，未导出。")
                files[name] = path
            }
        }
        check(("source/phone-backup.zip" in files) == ("source/backup.json" in files) && (data.importOrigin == null || "source/phone-backup.zip" in files),
            "找不到完整的手机原始归档。为避免漏掉日记等资料，这次未导出；请检查原资料库。")
        val document = diskJson.encodeToString(data).toByteArray(Charsets.UTF_8)
        check(document.size.toLong() <= maxJsonBytes, "资料库文字超过单文件备份上限，未导出。")
        val temporary = Files.createTempFile(target.toAbsolutePath().parent, ".cleos-export-", ".partial")
        try {
            var total = document.size.toLong()
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                val records = mutableListOf<BackupFile>()
                zip.putNextEntry(ZipEntry("library.json")); zip.write(document); zip.closeEntry()
                records += BackupFile("library.json", document.size.toLong(), MessageDigest.getInstance("SHA-256").digest(document).joinToString("") { "%02x".format(it) })
                files.forEach { (name, path) ->
                    val size = Files.size(path)
                    check(size <= limit(name) && size <= maxExpandedBytes - total, "资料库文件超过备份大小上限，未导出。")
                    total += size; checkCancelled()
                    zip.putNextEntry(ZipEntry(name))
                    val sha = Files.newInputStream(path, NOFOLLOW_LINKS).use { copy(it, zip, size, checkCancelled) }
                    zip.closeEntry(); records += BackupFile(name, size, sha)
                }
                val manifest = DesktopBackupManifest(exportedAt = System.currentTimeMillis(), libraryName = libraryName.trim().take(80).ifBlank { "电脑资料" }, files = records)
                val manifestBytes = diskJson.encodeToString(manifest).toByteArray(Charsets.UTF_8)
                check(manifestBytes.size.toLong() <= limit("manifest.json"), "备份清单超过上限，未导出。")
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifestBytes); zip.closeEntry()
            }
            FileChannel.open(temporary, WRITE).use { it.force(true) }
            val preview = inspect(temporary, checkCancelled)
            checkCancelled()
            // Same-directory rename on Windows; no REPLACE_EXISTING, including when another
            // process created the chosen name during export. Works on removable FAT volumes too.
            Files.move(temporary, target)
            return preview.copy(source = target.toAbsolutePath())
        } finally { Files.deleteIfExists(temporary) }
    }

    /** Caller owns a fresh staging directory; hash every entry again before publishing. */
    fun write(preview: DesktopBackupPreview, destination: Path, checkCancelled: () -> Unit = {}) {
        check(Files.isDirectory(destination, NOFOLLOW_LINKS) && Files.list(destination).use { !it.findAny().isPresent }, "恢复目标不是空的暂存目录，未恢复。")
        check(digest(preview.source, checkCancelled) == preview.sha256, "备份在预览后发生了变化，请重新选择。")
        ZipFile(preview.source.toFile()).use { zip ->
            val entries = entries(zip)
            preview.manifest.files.forEach { file ->
                limit(file.name)
                val path = destination.resolve(file.name)
                Files.createDirectories(path.parent)
                val sha = Files.newOutputStream(path, CREATE_NEW, WRITE).use { read(zip, entries.getValue(file.name), it, checkCancelled) }
                check(sha == file.sha256, "恢复时备份校验失败，原有资料未更改。")
            }
        }
        check(digest(preview.source, checkCancelled) == preview.sha256, "备份在恢复时发生了变化，原有资料未更改。")
        // Reapply the permission boundary even to an edited archive with valid hashes.
        LocalStore(destination).use { it.save(preview.data) }
    }
}
