package com.cleo.cleos.ui.chat

import com.cleo.cleos.ai.GroupChatRules
import com.cleo.cleos.data.db.GroupMessageEntity
import com.cleo.cleos.data.db.GroupTurnEntity

internal sealed interface GroupChatItem {
    val key: String
    data class Message(val message: GroupMessageEntity) : GroupChatItem { override val key = "message:${message.id}" }
    data class CallRound(val anchor: GroupMessageEntity, val replies: List<GroupMessageEntity>) : GroupChatItem {
        override val key = "call:${anchor.id}"
        val messages get() = listOf(anchor) + replies
        val ongoing get() = replies.any { it.status == "pending" || it.status == "running" }
        val incomplete get() = replies.any { it.status in setOf("failed", "cancelled", "interrupted") ||
            !it.error.isNullOrBlank() || groupCallWords(it.content)?.unfinished?.isNotBlank() == true }
    }
}

internal const val GROUP_CALL_USER = "[群通话转写]\n"
private const val GROUP_CALL_SPOKEN = "[群通话]\n"
private const val GROUP_CALL_UNFINISHED = "[群通话，这句未完整播完]\n"
internal data class GroupCallWords(val spoken: String, val unfinished: String = "")

/** Remove only the stored leading labels; quoted labels elsewhere remain ordinary text. */
internal fun groupCallWords(content: String): GroupCallWords? = when {
    content.startsWith(GROUP_CALL_UNFINISHED) -> GroupCallWords("", content.removePrefix(GROUP_CALL_UNFINISHED))
    content.startsWith(GROUP_CALL_SPOKEN) -> {
        val body = content.removePrefix(GROUP_CALL_SPOKEN)
        val boundary = body.indexOf("\n$GROUP_CALL_UNFINISHED")
        if (boundary < 0) GroupCallWords(body) else GroupCallWords(body.substring(0, boundary),
            body.substring(boundary + 1 + GROUP_CALL_UNFINISHED.length))
    }
    else -> null
}

/** Presentation only. Never infer whole telephone sessions from adjacent timestamps. */
internal fun groupChatItems(messages: List<GroupMessageEntity>, turns: List<GroupTurnEntity>): List<GroupChatItem> {
    val byAnchor = turns.groupBy { it.groupId to it.userMessageId }
    val byTurn = messages.filter { it.turnId != null }.groupBy { it.turnId }
    return buildList {
        var i = 0
        while (i < messages.size) {
            val anchor = messages[i]
            val linked = byAnchor[anchor.groupId to anchor.id].orEmpty()
            val replies = linked.flatMap { byTurn[it.id].orEmpty() }.sortedBy { it.id }
            val plain = { m: GroupMessageEntity -> m.audio == null && m.images == null && m.toolTrace in setOf("{}", "") }
            val valid = anchor.role == "user" && anchor.turnId == null && anchor.content.startsWith(GROUP_CALL_USER) && plain(anchor) &&
                linked.distinctBy { it.id }.size == linked.size && linked.all { turn ->
                    val targets = GroupChatRules.ids(turn.targets)
                    targets.isNotEmpty() && targets.distinct().size == targets.size &&
                        byTurn[turn.id].orEmpty().sortedBy { it.id }.map { it.speakerId } == targets
                } && replies.all { it.groupId == anchor.groupId && it.role == "assistant" && plain(it) &&
                    (it.content.isBlank() || groupCallWords(it.content) != null) } &&
                // Every consumed row must actually be next in the timeline; incomplete metadata falls back to bubbles.
                i + 1 + replies.size <= messages.size && messages.subList(i + 1, i + 1 + replies.size) == replies &&
                (linked.isNotEmpty() || messages.getOrNull(i + 1)?.role != "assistant")
            if (valid) {
                add(GroupChatItem.CallRound(anchor, replies))
                i += replies.size + 1
            } else {
                add(GroupChatItem.Message(anchor))
                i++
            }
        }
    }
}
