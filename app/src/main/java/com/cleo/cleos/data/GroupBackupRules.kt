package com.cleo.cleos.data

import com.cleo.cleos.ai.GroupPhotoRules
import com.cleo.cleos.ai.GroupChatRules
import com.cleo.cleos.ai.GroupMemoryRules
import com.cleo.cleos.ai.GroupToolRules
import com.cleo.cleos.ai.GroupUploadRules
import com.cleo.cleos.ai.GroupVoiceRules
import com.cleo.cleos.ai.GroupSpeechRules
import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json

/** Validate before replacing the user's database. Deleted TAs may remain as historical members. */
object GroupBackupRules {
    fun validate(groups: List<ChatGroupEntity>, members: List<GroupMemberEntity>, turns: List<GroupTurnEntity>, messages: List<GroupMessageEntity>,
        memories: List<GroupMemoryEntity> = emptyList()) {
        fun valid(ok: Boolean) { require(ok) { "备份中的群聊记录不完整，未恢复" } }
        fun ids(raw: String): List<Long> {
            val parsed = runCatching { Json.decodeFromString<List<Long>>(raw) }.getOrNull()
            valid(parsed != null)
            return requireNotNull(parsed)
        }
        fun file(name: String?) = name == null || name.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.[A-Za-z0-9]+"))
        val groupIds = groups.map { it.id }.toSet()
        val turnById = turns.associateBy { it.id }
        val messageById = messages.associateBy { it.id }
        valid(groupIds.size == groups.size && groupIds.all { it > 0 })
        valid(turnById.size == turns.size && turnById.keys.all { it > 0 })
        valid(messageById.size == messages.size && messageById.keys.all { it > 0 })
        valid(members.all { it.groupId in groupIds && it.companionId > 0 && file(it.avatar) })
        valid(members.map { it.groupId to it.companionId }.distinct().size == members.size)
        val membersByGroup = members.groupBy { it.groupId }
        for (group in groups) {
            val roster = membersByGroup[group.id].orEmpty()
            valid(roster.count { it.active } in 2..4 && roster.map { it.position }.sorted() == roster.indices.toList())
            valid(group.title.isNotBlank() && group.title.length <= 40 && group.draft.length <= GroupChatRules.MAX_TEXT && group.nextSpeaker >= 0)
            valid(GroupUploadRules.valid(group.draftImages, group.id))
            valid(GroupVoiceRules.validDraft(group.draftAudio, group.id))
            valid(group.draftAudio == null || group.draftImages == null && group.draft.isBlank())
            val mentioned = ids(group.mentions)
            valid(mentioned.distinct().size == mentioned.size && mentioned.all { id -> roster.any { it.companionId == id && it.active } })
        }
        for (row in messages) {
            valid(row.groupId in groupIds && row.role in setOf("user", "assistant") && file(row.speakerAvatar))
            valid(row.content.length <= GroupChatRules.MAX_REPLY)
            valid(if (row.role == "user") GroupUploadRules.valid(row.images, row.groupId) else GroupPhotoRules.valid(row.images))
            valid(if (row.role == "user") GroupVoiceRules.validAudio(row.audio, row.groupId) &&
                (row.audio == null || row.content.isNotBlank() && row.images == null) else GroupSpeechRules.validRow(row))
            valid(GroupToolRules.validate(row.toolTrace))
            if (row.role == "user") valid(GroupToolRules.decode(row.toolTrace).let { it.records.isEmpty() && it.notice.isEmpty() })
            val mentioned = ids(row.mentions)
            valid(mentioned.distinct().size == mentioned.size && mentioned.all { id -> membersByGroup[row.groupId].orEmpty().any { it.companionId == id } })
            if (row.role == "user") valid(row.turnId == null && row.speakerId == null && row.status == "sent" &&
                (row.content.isNotBlank() || row.images != null) && row.content.length <= GroupChatRules.MAX_TEXT)
            else {
                val turn = turnById[row.turnId]
                valid(turn != null && turn.groupId == row.groupId && row.id > turn.userMessageId && row.speakerId in ids(turn.targets))
                valid(membersByGroup[row.groupId].orEmpty().any { it.companionId == row.speakerId })
                valid(row.status in setOf("pending", "running", "sent", "failed", "cancelled", "interrupted"))
                valid(row.status != "sent" || row.content.isNotBlank() || !row.images.isNullOrBlank())
            }
        }
        val uploads = groups.flatMap { MessageImages.decode(it.draftImages).map { p -> p.file } } +
            messages.filter { it.role == "user" }.flatMap { MessageImages.decode(it.images).map { p -> p.file } }
        valid(uploads.distinct().size == uploads.size)
        val voices = groups.mapNotNull { GroupVoiceRules.draft(it.draftAudio, it.id)?.file } +
            messages.mapNotNull { GroupVoiceRules.messageAudio(it)?.file }
        valid(voices.distinct().size == voices.size)
        valid(messages.filter { it.role == "assistant" }.map { it.turnId to it.speakerId }.distinct().size == messages.count { it.role == "assistant" })
        for (turn in turns) {
            val anchor = messageById[turn.userMessageId]
            val targets = ids(turn.targets)
            val roster = membersByGroup[turn.groupId].orEmpty()
            valid(turn.groupId in groupIds && anchor != null && anchor.role == "user" && anchor.groupId == turn.groupId)
            valid(targets.size in 1..4 && targets.distinct().size == targets.size && targets.all { id -> roster.any { it.companionId == id } })
            valid(messages.filter { it.turnId == turn.id }.sortedBy { it.id }.mapNotNull { it.speakerId } == targets)
            valid(turn.status in setOf("running", "completed", "failed", "cancelled", "interrupted"))
        }
        valid(memories.map { it.id }.distinct().size == memories.size && memories.all { it.id > 0 && it.groupId in groupIds })
        valid(memories.groupBy { it.groupId }.values.all { it.size <= GroupMemoryRules.MAX_NOTES })
        valid(memories.mapNotNull { row -> row.sourceMessageId?.let { row.groupId to it } }.distinct().size == memories.count { it.sourceMessageId != null })
        for (note in memories) {
            valid(note.title.isNotBlank() && note.title.length <= GroupMemoryRules.MAX_TITLE &&
                note.content.isNotBlank() && note.content.length <= GroupMemoryRules.MAX_CONTENT && note.createdAt >= 0 && note.updatedAt >= 0)
            valid(note.sourceKind in setOf("manual", "message") && (note.sourceKind != "manual" || note.sourceMessageId == null))
            note.sourceMessageId?.let { id ->
                val source = messageById[id]
                valid(source != null && source.groupId == note.groupId && GroupMemoryRules.canRemember(source))
            }
        }
    }
    fun restored(row: GroupMessageEntity) = if (row.status in setOf("pending", "running"))
        row.copy(status = "interrupted", error = "备份中的回复尚未完成，可以重试") else row
    fun restored(row: GroupTurnEntity) = if (row.status == "running") row.copy(status = "interrupted") else row
}
