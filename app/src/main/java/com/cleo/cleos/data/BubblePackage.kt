package com.cleo.cleos.data

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
internal data class BubblePackageManifest(val format: String, val version: Int, val preset: BubblePreset)

/** Portable bubble-only ZIP; chat history, API keys and wallpaper never enter this file. */
class BubblePackage(private val images: ImageStore) {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun export(preset: BubblePreset, output: OutputStream) = withContext(Dispatchers.IO) {
        val p = preset.normalized()
        val files = p.decoration.imageFiles()
        require(files.all { images.file(it).isFile }) { "方案中的图片缺失，请重新选择图片后再导出。" }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("theme.json")); zip.write(json.encodeToString(BubblePackageManifest(format = "cleos-bubble", version = 1, preset = p)).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            files.forEach { name ->
                zip.putNextEntry(ZipEntry("images/$name")); images.file(name).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    suspend fun import(input: InputStream): BubblePreset = withContext(Dispatchers.IO) {
        val (p, entries) = BubblePackageReader.read(input)
        val files = p.decoration.imageFiles()
        files.forEach { name ->
            val bytes = entries.getValue("images/$name")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048) { "气泡图片格式或大小不支持。" }
        }
        val mapped = linkedMapOf<String, String>()
        try {
            files.forEach { name ->
                val bytes = entries.getValue("images/$name")
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("气泡图片无法读取。")
                try { mapped[name] = images.save(bitmap, "bubble-") } finally { bitmap.recycle() }
            }
            p.copy(id = UUID.randomUUID().toString(), decoration = p.decoration.mapImages { mapped[it] })
        } catch (e: Exception) { images.delete(mapped.values); throw e }
    }
}

internal object BubblePackageReader {
    private val json = Json { ignoreUnknownKeys = true }
    fun read(input: InputStream): Pair<BubblePreset, Map<String, ByteArray>> {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entries.size < 16 && entry.name !in entries) { "气泡包文件结构不正确。" }
                require(entry.name == "theme.json" || entry.name.matches(Regex("images/[A-Za-z0-9_-][A-Za-z0-9._-]*\\.(png|jpg|jpeg)"))) { "气泡包含有不支持的文件。" }
                val limit = if (entry.name == "theme.json") 256 * 1024 else 8 * 1024 * 1024
                val buffer = java.io.ByteArrayOutputStream()
                val block = ByteArray(8192)
                while (true) {
                    val count = zip.read(block)
                    if (count < 0) break
                    total += count
                    require(buffer.size() + count <= limit && total <= 32L * 1024 * 1024) { "气泡包太大，无法导入。" }
                    buffer.write(block, 0, count)
                }
                entries[entry.name] = buffer.toByteArray()
            }
        }
        val manifest = json.decodeFromString<BubblePackageManifest>(entries["theme.json"]?.toString(Charsets.UTF_8) ?: error("气泡包缺少 theme.json。"))
        require(manifest.format == "cleos-bubble" && manifest.version == 1) { "不支持这个气泡包版本。" }
        val p = manifest.preset.normalized()
        val files = p.decoration.imageFiles()
        require(entries.keys == (files.map { "images/$it" } + "theme.json").toSet()) { "气泡包图片缺失或文件不匹配。" }
        return p to entries
    }
}
