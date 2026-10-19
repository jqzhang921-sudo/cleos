package com.cleo.cleos.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.util.Comparator

@Serializable data class DesktopLibrary(val id: String, val name: String, val sourceSha256: String = "")
@Serializable private data class WorkspaceIndex(val format: String = "cleos-desktop-workspace", val version: Int = 1,
    val selected: String = "local", val libraries: List<DesktopLibrary> = listOf(DesktopLibrary("local", "我的电脑资料库")))

/** The original preview's root library remains in place. Imports receive separate folders. */
class DesktopWorkspace(val root: Path, private val vault: KeyVault = WindowsKeyVault(root)) : AutoCloseable {
    private val channel: FileChannel
    private val lock: FileLock
    private var index: WorkspaceIndex
    private var closed = false
    var controller by mutableStateOf<DesktopController?>(null); private set
    var libraries by mutableStateOf(emptyList<DesktopLibrary>()); private set
    var selected by mutableStateOf("local"); private set
    var problem by mutableStateOf(""); private set
    var storageBusy by mutableStateOf(false); private set
    val libraryName get() = libraries.find { it.id == selected }?.name ?: "我的电脑资料库"
    val canSwitch get() = !storageBusy && controller?.busyThread == null && controller?.mediaBusy!=true
    init {
        Files.createDirectories(root)
        channel = FileChannel.open(root.resolve("workspace.lock"), CREATE, WRITE)
        lock = try { channel.tryLock() ?: error("这个 Cleos 工作区已经打开，请回到已有窗口。") } catch (e: Exception) { channel.close(); throw e }
        try {
            val path = root.resolve("workspace.json")
            index = if (Files.exists(path)) diskJson.decodeFromString<WorkspaceIndex>(Files.readString(path)) else WorkspaceIndex()
            require(index.format == "cleos-desktop-workspace" && index.version == 1 && index.libraries.isNotEmpty()) { "资料库索引无法读取，原资料已保留。" }
            require(index.libraries.map { it.id }.distinct().size == index.libraries.size && index.libraries.all { validId(it.id) } && index.libraries.any { it.id == index.selected }) { "资料库索引不完整，原资料已保留。" }
            libraries = index.libraries; selected = index.selected
            controller = open(index.selected)
            runCatching { com.cleo.cleos.transfer.removeAbandonedReceipts(root.resolve("transfers").toFile()) }
        } catch (e: Exception) { lock.release(); channel.close(); throw e }
    }
    private fun validId(id: String) = id == "local" || Regex("library-[a-f0-9]{32}").matches(id)
    private fun path(id: String): Path {
        require(validId(id))
        val target = if (id == "local") root else root.resolve("libraries").resolve(id)
        require(target.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
        return target
    }
    private fun open(id: String): DesktopController {
        val target = path(id)
        if (id != "local") require(Files.isRegularFile(target.resolve("library.json"))) { "导入资料库暂时找不到，原始索引已保留。" }
        val store = LocalStore(target)
        return try { DesktopController(store, vault) } catch (e: Exception) { store.close(); throw e }
    }
    private fun commit(next: WorkspaceIndex) { atomicWrite(root.resolve("workspace.json"), diskJson.encodeToString(next).toByteArray(Charsets.UTF_8)); index = next; libraries = next.libraries; selected = next.selected }
    fun switch(id: String): Boolean {
        if (closed || !canSwitch || id == selected) return id == selected
        if (libraries.none { it.id == id }) return false
        var next: DesktopController? = null
        return try {
            next = open(id)
            commit(index.copy(selected = id))
            controller?.close(); controller = next; true
        } catch (_: Exception) { next?.close(); problem = "这次没有切换成功，原资料库仍然打开。"; false }
    }
    /** Preparation happens off the UI thread; the index is published only after validation. */
    suspend fun importBackup(preview: BackupPreview, name: String, importer: MobileBackupImporter = MobileBackupImporter()): Boolean {
        return publishImport(preview.sha256, name) { staging -> importer.write(preview, staging) }
    }
    suspend fun restoreBackup(preview: DesktopBackupPreview, name: String, archive: DesktopBackupArchive = DesktopBackupArchive()): Boolean {
        val context = currentCoroutineContext()
        return publishImport(preview.sha256, name) { staging -> archive.write(preview, staging) { context.ensureActive() } }
    }
    suspend fun exportBackup(target: Path, archive: DesktopBackupArchive = DesktopBackupArchive()): DesktopBackupPreview? {
        if (closed || !canSwitch) { problem = "请先结束正在进行的回复或备份操作。"; return null }
        val current = controller ?: return null
        val snapshot = current.data; val directory = current.store.directory; val name = libraryName
        storageBusy = true
        try {
            val context = currentCoroutineContext()
            return withContext(Dispatchers.IO) {
                val resolved = target.toAbsolutePath().normalize().parent.toRealPath().resolve(target.fileName)
                importCheck(!resolved.startsWith(root.toRealPath()), "请把备份保存在 Cleos 资料目录以外的位置，例如文档或 U 盘。")
                archive.export(directory, snapshot, name, resolved) { context.ensureActive() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { problem = (e as? ImportFailure)?.message ?: "导出没有完成，请检查保存位置或磁盘空间；原资料未更改。"; return null }
        finally { storageBusy = false }
    }
    internal suspend fun publishImport(sha256: String, name: String, write: (Path) -> Unit): Boolean {
        if (closed || !canSwitch) { problem = "请先结束正在进行的回复。"; return false }
        libraries.find { it.sourceSha256 == sha256 }?.let { return switch(it.id) }
        val id = "library-" + newId().replace("-", "")
        val base = root.resolve("libraries")
        val destination = path(id)
        var published = false
        var created = false
        var phase = "准备导入位置"
        var next: DesktopController? = null
        storageBusy = true
        try {
            withContext(Dispatchers.IO) {
                Files.createDirectories(base)
                Files.createDirectory(destination)
                created = true
                phase = "复制并校验备份"
                write(destination)
            }
            if (closed) return false
            phase = "读取导入资料"
            next = open(id)
            val entry = DesktopLibrary(id, name.trim().take(80).ifBlank { "导入的资料" }, sha256)
            // Only the atomic workspace index makes a library visible. Keep the unique,
            // unregistered directory in place: Windows can deny a directory rename while
            // a freshly written descendant is held by another process.
            phase = "登记资料库"
            commit(index.copy(selected = id, libraries = index.libraries + entry))
            published = true
            controller?.close(); controller = next
            return true
        } catch (e: Exception) {
            next?.close()
            if (e is kotlinx.coroutines.CancellationException) throw e
            problem = when {
                e is ImportFailure -> e.message.orEmpty()
                generateSequence<Throwable>(e){it.cause}.any {it is AccessDeniedException} ->
                    "导入没有完成（$phase）：Windows 暂时无法访问这些文件。原有资料库未更改，请保留备份后重试。"
                else -> "导入没有完成（$phase），原有资料库未更改。请检查备份、保存位置和可用空间。"
            }
            return false
        } finally {
            storageBusy = false
            if (!published) {
                // Only this new, unregistered directory belongs to the attempt. Never remove
                // the original archive or any registered library when preparation fails.
                if (created) removeUnpublished(base, destination)
            }
        }
    }
    private fun removeUnpublished(base: Path, target: Path) {
        val rootPath = base.toAbsolutePath().normalize(); val checked = target.toAbsolutePath().normalize()
        require(checked.parent == rootPath && (checked.fileName.toString().startsWith(".staging-library-") || checked.fileName.toString().startsWith("library-")))
        if (Files.exists(checked, LinkOption.NOFOLLOW_LINKS)) runCatching {
            Files.walk(checked).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { p -> require(p.normalize().startsWith(rootPath)); Files.deleteIfExists(p) } }
        }
    }
    override fun close() { if (!closed) { closed = true; controller?.close(); lock.release(); channel.close() } }
}
