package com.cleo.cleos.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.ConversationEntity
import com.cleo.cleos.data.db.MessageEntity
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class DeepSeekPreview(
    val uri: Uri,
    val companionId: Long,
    val companionName: String,
    val sessions: List<DeepSeekSession>,
    val imported: Set<String>,
)

internal data class DeepSeekImportResult(
    val sessions: Int,
    val messages: Int,
    val images: Int,
    val missingImages: Int,
    val skipped: Int,
    val unreadable: Int,
) {
    fun summary() = buildString {
        append("已导入 $sessions 段会话、$messages 条消息")
        if (images > 0) append("、$images 张图片")
        if (missingImages > 0) append("。$missingImages 张图片缺失或无法读取")
        if (skipped > 0) append("。跳过 $skipped 段已导入的会话")
        if (unreadable > 0) append("。$unreadable 段会话为空或无法读取")
    }
}

internal class DeepSeekImport(context: Context, private val db: AppDatabase, private val images: ImageStore) {
    private val resolver = context.contentResolver
    private val cache = context.cacheDir

    suspend fun preview(uri: Uri, companionId: Long, name: String): DeepSeekPreview = withContext(Dispatchers.IO) {
        val sessions = readZip(uri) { it.sessions() }
        if (sessions.isEmpty()) throw ImportException("这份 DeepSeek 导出包里没有会话")
        DeepSeekPreview(uri, companionId, name, sessions, db.conversations().importSources(companionId).toSet())
    }

    suspend fun run(
        preview: DeepSeekPreview,
        selected: Set<String>,
        includeImages: Boolean,
        update: suspend (processed: Int, total: Int) -> Unit,
    ): DeepSeekImportResult = withContext(Dispatchers.IO) {
        val sessions = preview.sessions.filter { it.id in selected }
        if (sessions.isEmpty()) throw ImportException("请先选要搬入的会话")
        val owner = preview.companionId
        var done = 0
        var count = 0
        var pictures = 0
        var missing = 0
        var skipped = 0
        var unreadable = 0
        readZip(preview.uri) { archive ->
            for ((index, session) in sessions.withIndex()) {
                currentCoroutineContext().ensureActive()
                update(index, sessions.size)
                if (db.conversations().hasImport(owner, session.sourceKey)) {
                    skipped++
                    continue
                }
                val lines = try { archive.messages(session) }
                catch (_: ImportException) { emptyList() }
                catch (_: ZipException) { emptyList() }
                if (lines.isEmpty()) {
                    unreadable++
                    continue
                }
                val copied = ArrayList<String>()
                var unavailable = 0
                var committed = false
                try {
                    var lastTime = lines.firstOrNull { it.at > 0 }?.at ?: System.currentTimeMillis()
                    val rows = lines.map { line ->
                        currentCoroutineContext().ensureActive()
                        val photos = if (includeImages) line.attachments.filter { it.image }.mapNotNull { attachment ->
                            val saved = attachment.path?.let { readPhoto(archive, it) }
                            if (saved == null) unavailable++ else copied += saved.file
                            saved
                        } else emptyList()
                        lastTime = maxOf(lastTime, line.at)
                        val text = line.text.ifBlank {
                            if (photos.isNotEmpty()) "" else if (line.attachments.any { it.image }) "（原消息含图片）" else "（原消息含附件）"
                        }
                        MessageEntity(conversationId = 0L, role = line.role, content = text, createdAt = lastTime,
                            images = MessageImages.encode(photos))
                    }
                    withContext(NonCancellable) {
                        committed = db.withTransaction {
                            if (db.companions().get(owner) == null) throw ImportException("要接收聊天的 TA 已不存在")
                            if (db.conversations().hasImport(owner, session.sourceKey)) return@withTransaction false
                            val id = db.conversations().insert(ConversationEntity(
                                title = session.title, createdAt = rows.first().createdAt,
                                updatedAt = maxOf(session.updatedAt, rows.last().createdAt),
                                companionId = owner, importSource = session.sourceKey,
                            ))
                            db.messages().insertAll(rows.map { it.copy(conversationId = id) })
                            true
                        }
                    }
                    if (committed) {
                        done++
                        count += rows.size
                        pictures += copied.size
                        missing += unavailable
                    } else skipped++
                } finally {
                    if (!committed) images.delete(copied)
                }
            }
            update(sessions.size, sessions.size)
        }
        DeepSeekImportResult(done, count, pictures, missing, skipped, unreadable)
    }

    private suspend fun readPhoto(archive: DeepSeekArchive, path: String): MessageImage? {
        val entry = archive.image(path) ?: return null
        val file = File.createTempFile("deepseek-image-", ".tmp", cache)
        try {
            file.outputStream().use { archive.copyImage(entry, it) }
            val stored = images.import(Uri.fromFile(file), prefix = "deepseek-")
            return MessageImage(stored.file, stored.width, stored.height)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            file.delete()
        }
    }

    private suspend fun <T> readZip(uri: Uri, block: suspend (DeepSeekArchive) -> T): T {
        try {
            resolver.openFileDescriptor(uri, "r")?.use { fd ->
                if (fd.statSize > MAX_ZIP) throw ImportException("这份 DeepSeek 导出包超过 4 GB")
                // Local document providers usually expose a seekable descriptor.
                val zip = try { ZipFile(File("/proc/self/fd/${fd.fd}")) }
                catch (_: IOException) { null }
                catch (_: SecurityException) { null }
                if (zip != null) return zip.use { block(DeepSeekArchive(it)) }
            }
            val temp = File.createTempFile("deepseek-archive-", ".zip", cache)
            try {
                val job = currentCoroutineContext()
                val input = resolver.openInputStream(uri) ?: throw ImportException("打不开这份 DeepSeek 导出包")
                input.use { stream ->
                    temp.outputStream().use { out -> stream.copyToBounded(out, MAX_ZIP, job::ensureActive) }
                }
                return ZipFile(temp).use { block(DeepSeekArchive(it)) }
            } finally {
                temp.delete()
            }
        } catch (_: ZipException) {
            throw ImportException("打不开 ZIP，请选一份完整的 DeepSeek 导出包")
        } catch (_: IOException) {
            throw ImportException("读取失败，请检查文件或将 ZIP 保存到手机本地后再试")
        } catch (_: SecurityException) {
            throw ImportException("文件访问权限已失效，请重新选 ZIP")
        }
    }

    private companion object { const val MAX_ZIP = 4L * 1024 * 1024 * 1024 }
}
