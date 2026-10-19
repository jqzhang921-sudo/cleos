package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.GroupMessageEntity
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Group-owned gallery previews, never file paths or image bytes in model history. */
object GroupPhotoRules {
    const val MAX_IMAGES = 3
    private val json = Json
    fun valid(raw: String?): Boolean = raw == null || runCatching {
        require(raw.length <= 16000)
        val pictures = json.decodeFromString<List<MessageImage>>(raw)
        require(pictures.isNotEmpty() && pictures.size <= MAX_IMAGES && pictures.map { it.file }.distinct().size == pictures.size)
        fun url(value: String) = value.length <= 2000 && value.toHttpUrlOrNull()?.let {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty()
        } == true
        for (picture in pictures) {
            require(picture.file.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.[A-Za-z0-9]+")))
            require(picture.width in 1..McpImages.MAX_EDGE && picture.height in 1..McpImages.MAX_EDGE)
            require(PhotoLibrary.entries.any { it.title == picture.source })
            val credit = requireNotNull(picture.credit)
            require(credit.title.isNotBlank() && credit.title.length <= 500 && credit.creator.isNotBlank() && credit.creator.length <= 500)
            require(credit.license.isNotBlank() && credit.license.length <= 120 && url(credit.pageUrl) && url(credit.licenseUrl))
            require(credit.previewNote == null || credit.previewNote.length <= 600)
        }
    }.isSuccess

    fun restored(row: GroupMessageEntity, exists: (String) -> Boolean): GroupMessageEntity {
        val original = MessageImages.decode(row.images)
        val remaining = original.filter { exists(it.file) }
        val missingOnly = original.isNotEmpty() && remaining.isEmpty() && row.content.isBlank()
        val userMissing = row.role == "user" && original.size != remaining.size
        return row.copy(images = MessageImages.encode(remaining),
            content = if (userMissing && row.content.isBlank() && remaining.isEmpty()) "（备份中的图片文件缺失）" else row.content,
            status = if (missingOnly && row.status == "sent" && row.role != "user") "failed" else row.status,
            error = if (userMissing) "备份中有 ${original.size - remaining.size} 张图片缺失，请重新选择后发送"
                else if (missingOnly) "备份中的图片文件缺失，可以单独重试" else row.error)
    }

    fun context(raw: String?, pixelsProvided: Boolean = false): String {
        val photos = MessageImages.decode(raw).take(MAX_IMAGES)
        if (photos.isEmpty()) return ""
        val references = photos.map { picture -> buildJsonObject {
            put("来源", picture.source.orEmpty())
            picture.credit?.let { put("标题", it.title); put("作者", it.creator); put("许可", it.license); put("来源链接", it.pageUrl) }
        } }
        val kind = if (photos.any { it.source == GroupUploadRules.SOURCE }) "上传图片" else "图库图片"
        val note = if (pixelsProvided) "是否提供画面以实际附图标记为准，不推断未附的图片细节"
            else "只提供来源说明，没有提供画面，不要猜细节"
        return "\n[这位作者在本群已发出的$kind：$note]\n" + JsonArray(references).toString()
    }
}

/** Save + bounded group commit precede any success receipt. Stopped imports cannot leave orphan files. */
object GroupPhotoDelivery {
    suspend fun deliver(outcome: ToolOutcome, budget: Int,
        save: suspend (ByteArray, String) -> MessageImage, commit: suspend (List<MessageImage>) -> Unit,
        remove: (List<String>) -> Unit): ToolOutcome {
        val photo = outcome.photo ?: return outcome
        val caller = currentCoroutineContext()
        val created = mutableListOf<String>()
        var committed = false
        try {
            caller.ensureActive()
            val delivery = PhotoDelivery.deliver(photo, budget) { bytes, mime ->
                withContext(NonCancellable) { save(bytes, mime).also { created += it.file } }
            }
            caller.ensureActive()
            if (delivery.images.isNotEmpty()) withContext(NonCancellable) {
                caller.ensureActive()
                commit(delivery.images)
                committed = true
            }
            caller.ensureActive()
            return outcome.copy(photo = null, imageResult = null,
                result = delivery.result(outcome.result).replace("已在聊天中展示", "已在本群展示"),
                note = if (committed) "发出了 1 张图库照片" else "这张图片没有发出")
        } finally {
            if (!committed) withContext(NonCancellable) { remove(created) }
        }
    }
}
