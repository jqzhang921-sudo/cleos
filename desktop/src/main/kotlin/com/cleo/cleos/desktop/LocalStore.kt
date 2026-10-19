package com.cleo.cleos.desktop

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.nio.ByteBuffer

internal val diskJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

/** A failed replacement leaves the last complete file and a previous snapshot intact. */
fun atomicWrite(path: Path, bytes: ByteArray) {
    Files.createDirectories(path.parent)
    val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
    FileChannel.open(temporary, CREATE, TRUNCATE_EXISTING, WRITE).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
    if (Files.exists(path)) Files.copy(path, path.resolveSibling(path.fileName.toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING)
    try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
}

class LocalStore(val directory: Path) : AutoCloseable {
    private val channel: FileChannel
    private val lock: FileLock
    private val path get() = directory.resolve("library.json")
    init {
        Files.createDirectories(directory)
        channel = FileChannel.open(directory.resolve("desktop.lock"), CREATE, WRITE)
        lock = try { channel.tryLock() ?: error("Cleos 已在使用这个资料库，请回到已经打开的窗口。") }
        catch (e: Exception) { channel.close(); throw e }
    }
    @Synchronized fun load(): DesktopData {
        if (!Files.exists(path)) return DesktopData.fresh()
        return try { diskJson.decodeFromString<DesktopData>(Files.readString(path)).checked().recoverInterrupted() }
        catch (e: Exception) { throw IllegalStateException("资料库暂时无法读取，原文件和 .bak 已保留，没有覆盖它们。\n$directory", e) }
    }
    @Synchronized fun save(data: DesktopData) = atomicWrite(path, diskJson.encodeToString(data.checked()).toByteArray(Charsets.UTF_8))
    override fun close() { lock.release(); channel.close() }
}

interface KeyVault { fun get(address: String): String; fun put(address: String, key: String) }

fun keyAddress(base: String): String = base.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')

/** Windows DPAPI binds secrets to this Windows user. No keys enter library.json or logs. */
class WindowsKeyVault(private val directory: Path) : KeyVault {
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val path get() = directory.resolve("keys.dpapi")
    @Synchronized private fun read(): Map<String, String> {
        if (!Files.exists(path)) return emptyMap()
        val decrypted = Crypt32Util.cryptUnprotectData(Files.readAllBytes(path))
        return try { diskJson.decodeFromString(serializer, decrypted.toString(Charsets.UTF_8)) } finally { decrypted.fill(0) }
    }
    @Synchronized override fun get(address: String): String = read()[keyAddress(address)].orEmpty()
    @Synchronized override fun put(address: String, key: String) {
        val values = read().toMutableMap()
        if (key.isBlank()) values.remove(keyAddress(address)) else values[keyAddress(address)] = key.trim()
        val plain = diskJson.encodeToString(serializer, values).toByteArray(Charsets.UTF_8)
        try { atomicWrite(path, Crypt32Util.cryptProtectData(plain)) } finally { plain.fill(0) }
    }
}
