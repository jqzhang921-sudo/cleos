package com.cleo.cleos.ai

import com.cleo.cleos.data.MessageAudios
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.db.*

/** A confirmation freezes only its affected records, never the group's mutable draft. */
@ConsistentCopyVisibility
data class GroupMessageDeletion internal constructor(
    val groupId: Long, val groupCreatedAt: Long, val epoch: Long,
    val selected: GroupMessageEntity, val anchor: GroupMessageEntity,
    val turns: List<GroupTurnEntity>, val replies: List<GroupMessageEntity>,
    val wholeRound: Boolean, val removed: List<GroupMessageEntity>,
    val removeTurns: List<Long>, val updateTurns: List<GroupTurnEntity>,
    val notes: List<GroupMemoryEntity>,
) {
    val files: Set<String> get() = removed.flatMap {
        MessageImages.decode(it.images).map { image -> image.file } + listOfNotNull(MessageAudios.decode(it.audio)?.file)
    }.filter { it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.[A-Za-z0-9]+")) }.toSet()
}

internal object GroupDeletionRules {
    fun plan(group: ChatGroupEntity, epoch: Long, selected: GroupMessageEntity, anchor: GroupMessageEntity,
        turns: List<GroupTurnEntity>, replies: List<GroupMessageEntity>, wholeRound: Boolean,
        notes: List<GroupMemoryEntity>): GroupMessageDeletion {
        require(selected.groupId == group.id && anchor.groupId == group.id && anchor.role == "user") { "消息不属于这个群" }
        require(selected.id > 0 && anchor.id > 0)
        require(turns.distinctBy { it.id }.size == turns.size && turns.all {
            it.groupId == group.id && it.userMessageId == anchor.id && it.status != "running"
        }) { "这一轮还在回复，请先停止" }
        val byId = turns.associateBy { it.id }
        require(replies.distinctBy { it.id }.size == replies.size && replies.all {
            it.groupId == group.id && it.role == "assistant" && it.turnId in byId && it.status !in setOf("pending", "running")
        }) { "这一轮的回复记录已变化" }
        for (turn in turns) require(replies.filter { it.turnId == turn.id }.sortedBy { it.id }.mapNotNull { it.speakerId } ==
            GroupChatRules.ids(turn.targets).also { require(it.isNotEmpty() && it.distinct().size == it.size) }) { "这一轮的成员记录不完整" }
        require(if (selected.role == "user") selected == anchor else selected in replies) { "原消息已变化" }
        // Removing the user anchor without its replies would make both history and backups orphaned.
        val round = turns.isNotEmpty() && (wholeRound || selected.role == "user")
        val removed = if (round) (listOf(anchor) + replies).sortedBy { it.id } else listOf(selected)
        val ids = removed.map { it.id }.toSet()
        val removeTurns = mutableListOf<Long>()
        val updateTurns = mutableListOf<GroupTurnEntity>()
        for (turn in turns) {
            val remaining = replies.filter { it.turnId == turn.id && it.id !in ids }.sortedBy { it.id }
            if (round || remaining.isEmpty()) removeTurns += turn.id
            else if (replies.any { it.turnId == turn.id && it.id in ids }) updateTurns += turn.copy(
                targets = GroupChatRules.encode(remaining.map { requireNotNull(it.speakerId) }),
                status = if (remaining.all { it.status == "sent" }) "completed" else
                    turn.status.takeIf { it in setOf("cancelled", "interrupted") } ?: "failed")
        }
        val affectedNotes = notes.filter { it.sourceMessageId in ids }
        require(affectedNotes.all { it.groupId == group.id }) { "小本本来源不属于这个群" }
        return GroupMessageDeletion(group.id, group.createdAt, epoch, selected, anchor, turns.sortedBy { it.id },
            replies.sortedBy { it.id }, round, removed, removeTurns, updateTurns, affectedNotes.sortedBy { it.id })
    }

    fun requireCurrent(approved: GroupMessageDeletion, current: GroupMessageDeletion) {
        check(approved == current) { "这轮内容已变化，请取消后重新选择，核对删除范围" }
    }
}
