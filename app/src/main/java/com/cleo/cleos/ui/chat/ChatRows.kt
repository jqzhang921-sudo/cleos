package com.cleo.cleos.ui.chat

import com.cleo.cleos.ai.StreamingReply
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.MessageThoughts
import com.cleo.cleos.data.db.MessageEntity

internal sealed interface ChatActivity {
    val messageId: Long?
    data class Thought(override val messageId: Long?, val text: String, val interrupted: Boolean = false) : ChatActivity
    data class Tool(val message: MessageEntity, val kind: ToolKind) : ChatActivity {
        override val messageId: Long get() = message.id
    }
    data class Running(val text: String) : ChatActivity { override val messageId: Long? get() = null }
}

internal sealed interface ChatRow {
    val key: Any
    data class Stamp(val at: Long) : ChatRow { override val key: Any get() = "t$at" }
    data object RecapMark : ChatRow { override val key: Any get() = "recap" }
    data class Woke(val at: Long) : ChatRow { override val key: Any get() = "w$at" }
    data class Message(val message: MessageEntity, val isLast: Boolean, val showFace: Boolean = true,
                       val tool: ToolKind? = null, val activityFolded: Boolean = false) : ChatRow {
        override val key: Any get() = message.id
    }
    data class Live(val reply: StreamingReply) : ChatRow { override val key: Any get() = "live" }
    /** Events are oldest first; bodies and pictures remain separate chat rows. */
    data class Activity(val events: List<ChatActivity>, val live: Boolean = false,
                        val conversationId: Long = 0) : ChatRow {
        override val key: Any get() = if (live) "activity-live-$conversationId"
            else "activity-${events.first().messageId}"
        val ids: Set<Long> get() = events.mapNotNull { it.messageId }.toSet()
    }
}

internal fun MessageEntity.isToolLine() = role == "tool" && !note.isNullOrBlank()
internal fun MessageEntity.thoughtOnly() = role == "assistant" && content.isEmpty() && error == null &&
    MessageImages.decode(images).isEmpty() && audio == null && feedShare == null
private fun MessageEntity.silent() = (thoughtOnly() && (thought == null || proactive)) ||
    (role == "tool" && note.isNullOrBlank()) || call != null
private fun MessageEntity.side(): Boolean? = when {
    role == "user" && note == null -> true
    role == "assistant" && !thoughtOnly() -> false
    else -> null
}
private fun MessageEntity.foldedBy(until: Pair<Long, Long>) = createdAt < until.first ||
    (createdAt == until.first && id <= until.second)

/** Newest first for the reverse-layout chat. This transforms presentation only, never stored rows. */
internal fun buildRows(messages: List<MessageEntity>, recapUntil: Pair<Long, Long>? = null,
                       eachFace: Boolean = false, showThought: Boolean = true,
                       live: StreamingReply? = null): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 8)
    var newerSide: Boolean? = null
    var marked = false
    val last = messages.indexOfLast { it.role != "pat" }
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        if (recapUntil != null && !marked && m.foldedBy(recapUntil)) {
            rows += ChatRow.RecapMark
            marked = true
            newerSide = null
        }
        if (m.silent()) continue
        // A wake's private deciding process is not part of its message, including old stored rows.
        val thought = if (showThought && !m.proactive && m.role == "assistant") MessageThoughts.decode(m.thought) else null
        val tool = if (m.isToolLine()) ToolKinds.of(ToolDetails.nameOf(messages, m)) else null
        val events = buildList {
            thought?.text?.takeIf { it.isNotBlank() }?.let { add(ChatActivity.Thought(m.id, it, interrupted = m.error != null)) }
            if (tool != null) add(ChatActivity.Tool(m, tool))
        }
        val pictures = MessageImages.decode(m.images)
        val hasBody = !m.thoughtOnly() && (tool == null || pictures.isNotEmpty())
        if (!hasBody && events.isEmpty()) continue
        val side = m.side()
        if (hasBody) rows += ChatRow.Message(m, i == last, eachFace || side == null || side != newerSide,
            tool, activityFolded = events.isNotEmpty())
        if (events.isNotEmpty()) rows += ChatRow.Activity(events)
        newerSide = side
        val prev = messages.getOrNull(i - 1)
        val gap = prev == null || m.createdAt - prev.createdAt > 10 * 60 * 1000L
        if (m.proactive) {
            val older = (i - 1 downTo 0).firstOrNull { !messages[it].silent() }?.let { messages[it] }
            if (gap || older == null || !older.proactive) {
                rows += ChatRow.Woke(m.createdAt)
                newerSide = null
            }
        }
        if (gap) {
            rows += ChatRow.Stamp(m.createdAt)
            newerSide = null
        }
    }
    return withLiveRows(foldActivities(rows), messages, live, showThought)
}

/** Stored rows are cached by the screen; streaming deltas need not decode the whole history again. */
internal fun withLiveRows(storedRows: List<ChatRow>, messages: List<MessageEntity>,
                          live: StreamingReply?, showThought: Boolean): List<ChatRow> {
    if (live == null) return storedRows
    val rows = ArrayList<ChatRow>()
    val shownLive = live?.let { reply ->
        val stored = reply.savedId != null && messages.any { it.id == reply.savedId }
        when {
            stored && reply.finished -> null
            stored -> reply.copy(text = "")
            else -> reply
        }
    }
    if (shownLive != null) {
        val reply = shownLive
        // A provider may hand over a thought before its stored row arrives in the UI.
        val stored = reply.savedId != null && messages.any { it.id == reply.savedId }
        val events = buildList {
            if (showThought && !stored && reply.thought.isNotBlank())
                add(ChatActivity.Thought(null, reply.thought, interrupted = reply.finished && reply.error != null))
            reply.activity?.takeUnless { reply.finished }?.let { add(ChatActivity.Running("$it…")) }
        }
        // Approval stays outside the folded panel. Without events, retain the ordinary waiting dots.
        if (reply.text.isNotEmpty() || reply.asking != null || (!reply.finished && events.isEmpty())) rows += ChatRow.Live(reply)
        if (events.isNotEmpty()) rows += ChatRow.Activity(events, live = !reply.finished, conversationId = reply.conversationId)
    }
    if (rows.isEmpty()) return storedRows
    return foldActivities(rows + storedRows)
}

/** Keep all activity in a reply together, even when its tools sent words or pictures between steps.
 * User messages, notices, permissions, calls, time stamps and recap boundaries end a run. */
private fun foldActivities(rows: List<ChatRow>): List<ChatRow> = buildList {
    val segment = ArrayList<ChatRow>()
    fun flush() {
        val groups = segment.filterIsInstance<ChatRow.Activity>()
        if (groups.isEmpty()) addAll(segment) else {
            val waiting = segment.filterIsInstance<ChatRow.Live>().firstOrNull { !it.reply.finished && it.reply.text.isEmpty() && it.reply.asking == null }
            addAll(segment.filterNot { it is ChatRow.Activity || it === waiting })
            val ongoing = groups.firstOrNull { it.live }
            val events = groups.asReversed().flatMap { it.events } +
                if (waiting != null) listOf(ChatActivity.Running("正在继续…")) else emptyList()
            add(ChatRow.Activity(events, ongoing != null || waiting != null,
                ongoing?.conversationId ?: waiting?.reply?.conversationId ?: 0))
        }
        segment.clear()
    }
    for (row in rows) {
        val inReply = when (row) {
            is ChatRow.Activity -> true
            is ChatRow.Live -> row.reply.asking == null
            is ChatRow.Message -> row.message.role == "assistant" || row.message.isToolLine()
            else -> false
        }
        if (inReply) segment += row else { flush(); add(row) }
    }
    flush()
}
