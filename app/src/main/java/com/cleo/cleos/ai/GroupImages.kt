package com.cleo.cleos.ai

import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.db.GroupMessageEntity

/** Uploaded files belong to one group, independently of the gallery's licensed previews. */
object GroupUploadRules {
    const val SOURCE = "用户上传"
    const val MAX_IMAGES = 3
    fun prefix(groupId: Long) = "group-user-$groupId-"
    fun valid(raw: String?, groupId: Long): Boolean {
        if (raw == null) return true
        if (raw.length > 16000 || groupId <= 0) return false
        val pictures = MessageImages.decode(raw)
        return pictures.size in 1..MAX_IMAGES && pictures.map { it.file }.distinct().size == pictures.size && pictures.all {
            it.file.startsWith(prefix(groupId)) && it.file.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.[A-Za-z0-9]+")) &&
                it.width in 1..McpImages.MAX_EDGE && it.height in 1..McpImages.MAX_EDGE && it.source == SOURCE && it.credit == null
        }
    }
    fun checked(raw: String?, groupId: Long): List<MessageImage> {
        require(valid(raw, groupId)) { "群图片记录不完整，请重新选择" }
        return MessageImages.decode(raw)
    }
}

/** Bounded pixels from this group's newest pictures, with their actual message ownership. */
object GroupVisionRules {
    const val MAX_IMAGES = 3
    const val NO_PIXELS = "[本群图片：本次没有提供画面，只有文字及来源说明，不要推断图片细节。]"
    private val attached = Regex("\\[本群附图：[^\\]]*\\]")
    fun selected(groupId: Long, history: List<GroupMessageEntity>, userMessageId: Long? = null): Map<Long, List<String>> {
        val candidates = history.filter { it.groupId == groupId }.flatMap { row ->
            val valid = if (row.role == "user") row.status == "sent" && GroupUploadRules.valid(row.images, groupId)
                else row.role == "assistant" && GroupPhotoRules.valid(row.images)
            if (!valid) emptyList() else MessageImages.decode(row.images).map { row.id to it.file }
        }
        // A preceding member's gallery cards must not displace the pictures the user just asked about.
        val anchor = candidates.filter { it.first == userMessageId }
        val chosen = (anchor + candidates.asReversed()).distinctBy { it.second }.take(MAX_IMAGES).toSet()
        return candidates.filter { it in chosen }.groupBy({ it.first }, { it.second })
    }
    fun label(pictures: List<MessageImage>, files: List<String>): String {
        val numbers = files.map { file -> pictures.indexOfFirst { it.file == file } + 1 }.filter { it > 0 }
        return "[本群附图：本次提供这条消息的第 ${numbers.joinToString("、")} 张图片画面。]"
    }
    fun withoutPixels(messages: List<ApiMessage>): List<ApiMessage> = messages.mapIndexed { i, m ->
        val content = if (m.images.isNotEmpty()) m.content.replace(attached, NO_PIXELS) else m.content
        m.copy(images = emptyList(), content = content + if (i == 0)
            "\n当前接口明确拒绝图片，本次实际没有图像输入；只依据文字和来源说明回复，不假装看过画面。" else "")
    }
}
