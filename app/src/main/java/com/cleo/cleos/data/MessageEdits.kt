package com.cleo.cleos.data

import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.db.ConversationEntity

object MessageEdits {
    fun prefix(messages: List<MessageEntity>, edited: MessageEntity) = messages.filter {
        it.conversationId == edited.conversationId &&
            (it.createdAt < edited.createdAt || (it.createdAt == edited.createdAt && it.id <= edited.id))
    }.sortedWith(compareBy<MessageEntity> { it.createdAt }.thenBy { it.id })
    fun eligible(m: MessageEntity) = m.role == "user" && m.note == null && m.audio == null &&
        m.call == null && m.toolCalls == null && m.content.isNotBlank() && StickerText.without(m.content).isNotBlank()

    fun problem(current: MessageEntity?, original: MessageEntity, text: String): String? = when {
        current == null || current.conversationId != original.conversationId -> "这条消息已经不存在了"
        !eligible(current) -> "这条消息不能编辑"
        current.content != original.content -> "消息已经被修改，请重新打开编辑"
        text.isBlank() -> "消息内容不能为空"
        text.trim() == current.content -> "内容还没有变化"
        else -> null
    }

    /** A summary may contain events after the edit; build this branch from its actual prefix. */
    fun branchConversation(c: ConversationEntity, at: Long) = c.copy(id = 0,
        title = c.title.removePrefix("修改前 · "), createdAt = at, updatedAt = at,
        recap = null, recapUntilAt = null, recapUntilId = null, followUpMessageId = null, followUpAt = null)
}
