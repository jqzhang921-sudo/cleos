package com.cleo.cleos.ai

import com.cleo.cleos.data.db.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** A single optional handoff after an ordinary two-member turn, never an autonomous loop. */
object GroupInterjectionRules {
    const val TOOL = "invite_group_member"

    fun candidates(group: ChatGroupEntity, turn: GroupTurnEntity, anchor: GroupMessageEntity?,
        speaker: Long, replies: List<GroupMessageEntity>, members: List<GroupMemberEntity>,
        available: Set<Long>, canInvite: Boolean): List<GroupMemberEntity> {
        val targets = GroupChatRules.ids(turn.targets)
        if (!canInvite || !group.allowInterjections || turn.status != "running" || turn.groupId != group.id ||
            anchor?.id != turn.userMessageId || anchor.groupId != group.id || anchor.role != "user" ||
            anchor.mentions != "[]" || targets.size != 2 || targets.distinct().size != 2 || targets.last() != speaker ||
            replies.size != 2 || replies.map { it.speakerId } != targets ||
            replies.any { it.groupId != group.id || it.turnId != turn.id || it.role != "assistant" } ||
            replies.first().status != "sent" || replies.last().status !in setOf("running", "sent")) return emptyList()
        return members.filter { it.groupId == group.id && it.active && it.companionId in available && it.companionId !in targets }
            .distinctBy { it.companionId }.take(GroupChatRules.MAX_MEMBERS - 2)
    }

    fun spec(candidates: List<GroupMemberEntity>): ToolSpec? {
        if (candidates.isEmpty()) return null
        val ids = candidates.map { it.companionId }.distinct()
        return ToolSpec(TOOL, emptySet(), "请伙伴接话",
            "只有话题确实适合另一位伙伴补充、且不是重复已有回答时，才邀请一位还没发言的群成员。可以不用，不要每轮邀请。" +
                "候选：" + candidates.joinToString("、") { "${it.name}（成员 ${it.companionId}）" } +
                "。这只记下邀请；你成功结束本次回复后才会安排，对方尚未回答。每轮最多多一位，不是后台约定。",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("member_id", buildJsonObject {
                    put("type", "integer"); put("enum", JsonArray(ids.map(::JsonPrimitive)))
                    put("description", "从当前候选中选择实际成员编号，不按名字猜测")
                }) })
                put("required", JsonArray(listOf(JsonPrimitive("member_id"))))
                put("additionalProperties", false)
            })
    }

    fun target(arguments: String, candidates: List<GroupMemberEntity>): Long {
        val args = ToolArgs.parse(arguments)
        require(args != null && args.keys == setOf("member_id")) { "接话邀请的参数不完整，没有安排" }
        val primitive = args["member_id"] as? JsonPrimitive
        val id = primitive?.takeUnless { it.isString }?.content?.toLongOrNull()
        require(id != null && id > 0 && candidates.any { it.companionId == id }) { "这位伙伴不在本轮接话候选中，没有安排" }
        return id
    }

    fun isExtra(turn: GroupTurnEntity, anchor: GroupMessageEntity?, row: GroupMessageEntity): Boolean {
        val targets = GroupChatRules.ids(turn.targets)
        return targets.size == 3 && anchor?.mentions == "[]" && anchor.id == turn.userMessageId &&
            anchor.groupId == turn.groupId && row.turnId == turn.id && row.groupId == turn.groupId && row.speakerId == targets.last()
    }
}

/** Only freshly appended pending slots run. Retrying or restoring cannot create another handoff. */
object GroupInterjectionRound {
    suspend fun run(initial: List<GroupMessageEntity>, only: Long?, begin: suspend (Long) -> Boolean,
        respond: suspend (GroupMessageEntity, Boolean) -> Unit, current: suspend () -> List<GroupMessageEntity>) {
        GroupReplyEngine.round(initial, only, begin) { respond(it, only == null) }
        currentCoroutineContext().ensureActive()
        if (only != null) return
        val added = current().filter { row -> initial.none { it.id == row.id } }
        check(added.size <= 1 && (added.isEmpty() || initial.size == 2 && added.all { row ->
            row.status == "pending" && row.groupId == initial.first().groupId && row.turnId == initial.first().turnId &&
                row.role == "assistant" && row.speakerId != null && initial.none { it.speakerId == row.speakerId }
        })) { "接话队列不完整，没有继续请求" }
        GroupReplyEngine.round(added, null, begin) { respond(it, false) }
    }
}
