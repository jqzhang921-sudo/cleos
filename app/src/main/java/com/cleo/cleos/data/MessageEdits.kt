package com.cleo.cleos.data

import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.db.ConversationEntity

object MessageEdits {
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

    /** Correct a folded statement without discarding the rest of a person's recap. */
    fun correctedRecap(c: ConversationEntity, m: MessageEntity, text: String): String? {
        val at = c.recapUntilAt ?: return c.recap
        val folded = m.createdAt < at || (m.createdAt == at && m.id <= (c.recapUntilId ?: Long.MIN_VALUE))
        if (!folded) return c.recap
        return c.recap.orEmpty() + "\n【对方更正过往消息】原话：${StickerText.plain(m.content)}\n更正为：${StickerText.plain(text)}"
    }
}
