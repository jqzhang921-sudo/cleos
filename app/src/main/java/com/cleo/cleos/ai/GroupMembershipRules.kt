package com.cleo.cleos.ai

import com.cleo.cleos.data.db.GroupMemberEntity

/** Stable historical membership: leaving changes eligibility, never the author of old messages. */
object GroupMembershipRules {
    fun selected(ids: List<Long>, available: Set<Long>): List<Long> {
        require(ids.size in 2..GroupChatRules.MAX_MEMBERS && ids.distinct().size == ids.size && ids.all { it > 0 && it in available }) {
            "请选择 2–4 位仍在的不同 TA"
        }
        return ids
    }

    fun roster(current: List<GroupMemberEntity>, selected: List<Long>, added: List<GroupMemberEntity>): List<GroupMemberEntity> {
        GroupMembershipRules.selected(selected, (current + added).map { it.companionId }.toSet())
        val known = current.map { it.companionId }.toSet()
        require(added.map { it.companionId }.toSet() == selected.filterNot { it in known }.toSet())
        require(added.map { it.companionId }.distinct().size == added.size)
        val old = current.sortedBy { it.position }.map {
            val active = it.companionId in selected
            if (it.active && active) it else it.copy(active = active, sharePrivateMemories = false, privateMemoryGrants = "[]")
        }
        val fresh = selected.filterNot { it in known }.mapIndexed { offset, id ->
            added.first { it.companionId == id }.copy(position = old.size + offset, active = true,
                sharePrivateMemories = false, privateMemoryGrants = "[]")
        }
        return old + fresh
    }

    fun mentions(ids: List<Long>, members: List<GroupMemberEntity>, available: Set<Long>) =
        ids.distinct().filter { id -> id in available && members.any { it.companionId == id && it.active } }
}
