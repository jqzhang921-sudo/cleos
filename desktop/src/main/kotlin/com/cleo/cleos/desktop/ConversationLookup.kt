package com.cleo.cleos.desktop

internal data class ChatSearchHit(val message: Message, val excerpt: String)
internal data class ChatMediaHit(val message: Message, val attachment: Attachment)

/** Only the selected conversation's visible body is searched; thoughts and other stores stay out. */
internal fun searchConversation(chat: Conversation, query: String, limit: Int = 50, checkpoint: () -> Unit = {}): List<ChatSearchHit> {
    val needle = query.trim()
    if (needle.isEmpty() || limit <= 0) return emptyList()
    return buildList {
        for (message in chat.messages.asReversed()) {
            checkpoint()
            if (message.notice) continue
            val match = message.text.indexOf(needle, ignoreCase = true)
            if (match < 0) continue
            val start = (match - 35).coerceAtLeast(0)
            val end = (start + 180).coerceAtMost(message.text.length)
            val excerpt = (if (start > 0) "…" else "") + message.text.substring(start, end).replace('\n', ' ').replace('\r', ' ') + (if (end < message.text.length) "…" else "")
            add(ChatSearchHit(message, excerpt))
            if (size >= limit.coerceAtMost(100)) break
        }
    }
}

internal fun recentConversationMedia(chat: Conversation, limit: Int = 24): List<ChatMediaHit> {
    if (limit <= 0) return emptyList()
    return chat.messages.asReversed().asSequence().filterNot { it.notice }
        .flatMap { message -> message.attachments.asReversed().asSequence().filter { it.kind == "image" || it.kind == "audio" }.map { ChatMediaHit(message, it) } }
        .take(limit.coerceAtMost(100)).toList()
}
