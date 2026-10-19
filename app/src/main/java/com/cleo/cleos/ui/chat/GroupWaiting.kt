package com.cleo.cleos.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.ai.GroupReplyProgress
import com.cleo.cleos.ai.GroupReplyStage
import com.cleo.cleos.ai.GroupToolRules
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.db.GroupMessageEntity
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.avatarLetter

/**
 * What the group list shows: the stored items as they are, or a run of members who have nothing to show yet, folded into
 * one strip. Only presentation: the rows, ids and turns underneath are untouched.
 */
internal sealed interface GroupListEntry {
    val key: String
    data class Chat(val item: GroupChatItem) : GroupListEntry { override val key get() = item.key }

    /** Replies start from the front. The last waiting slot stays stable as earlier slots materialize. */
    data class Waiting(val turnId: Long?, val rows: List<GroupMessageEntity>) : GroupListEntry {
        override val key get() = "waiting:${rows.last().groupId}:${turnId ?: 0}:${rows.last().id}"
    }
}

/** Adjacent waiting messages of one turn become one [GroupListEntry.Waiting]; everything else keeps its own entry and order. */
internal fun groupListEntries(items: List<GroupChatItem>, waiting: (GroupMessageEntity) -> Boolean): List<GroupListEntry> = buildList {
    var i = 0
    while (i < items.size) {
        val first = (items[i] as? GroupChatItem.Message)?.message?.takeIf(waiting)
        if (first == null) {
            add(GroupListEntry.Chat(items[i]))
            i++
            continue
        }
        val run = mutableListOf(first)
        var j = i + 1
        while (j < items.size) {
            val next = (items[j] as? GroupChatItem.Message)?.message
            if (next != null && first.turnId != null && next.groupId == first.groupId &&
                next.turnId == first.turnId && waiting(next)) { run += next; j++ } else break
        }
        add(GroupListEntry.Waiting(first.turnId, run))
        i = j
    }
}

internal data class GroupWaitingState(
    /** Answering now. */
    val active: List<GroupMessageEntity>,
    /** Whose avatars lead the strip: those answering, or, when nobody is yet, the next in line. */
    val lead: List<GroupMessageEntity>,
    val queued: List<GroupMessageEntity>,
)

internal object GroupWaiting {
    /**
     * A member with nothing yet to show: queued or running, no text (not even streamed), no voice, photos, tool record or error.
     * Anything real keeps its row, so a failure, a retry or a tool record is never hidden behind the strip.
     */
    fun isWaiting(row: GroupMessageEntity, streaming: Set<Long>): Boolean {
        if (row.role != "assistant" || row.status !in setOf("pending", "running")) return false
        if (row.id in streaming || row.content.isNotEmpty() || row.audio != null || !row.error.isNullOrBlank()) return false
        if (MessageImages.decode(row.images).isNotEmpty()) return false
        val trace = GroupToolRules.decode(row.toolTrace)
        return trace.records.isEmpty() && trace.notice.isEmpty()
    }

    fun split(rows: List<GroupMessageEntity>): GroupWaitingState {
        val active = rows.filter { it.status == "running" }
        return if (active.isNotEmpty()) GroupWaitingState(active, active, rows.filter { it.status != "running" })
        else GroupWaitingState(emptyList(), rows.take(1), rows.drop(1))
    }

    fun name(row: GroupMessageEntity) = row.speakerName.ifBlank { "TA" }

    /** The real stage, said without a clock: how long it has taken is kept in the progress, not shown. */
    fun stageText(stage: GroupReplyStage?): String = when (stage) {
        null -> "正在连接"
        GroupReplyStage.WAITING -> "等待回应"
        GroupReplyStage.THINKING -> "正在思考"
        GroupReplyStage.ANSWERING -> "正在回复"
        GroupReplyStage.TOOLS -> "正在处理"
        GroupReplyStage.SPEAKING -> "准备语音"
    }

    fun label(state: GroupWaitingState, progress: Map<Long, GroupReplyProgress>): String =
        if (state.active.isNotEmpty()) {
            state.active.joinToString("、") { name(it) } + " " + stageText(progress[state.active.first().id]?.stage)
        } else name(state.lead.first()) + " 准备接话"
}

/**
 * One small strip for the members who have not started: the one answering has a softly breathing ring and what it is
 * doing; the ones in line follow, dimmed, after 排队. When a member starts writing it leaves the strip and its words
 * appear as a normal message.
 */
@Composable
internal fun GroupWaitingStrip(rows: List<GroupMessageEntity>, progress: Map<Long, GroupReplyProgress>) {
    val palette = LocalGlassPalette.current
    val state = GroupWaiting.split(rows)
    val label = GroupWaiting.label(state, progress)
    val queuedNames = state.queued.joinToString("、") { GroupWaiting.name(it) }
    GlassSurface(Modifier.widthIn(max = 340.dp).semantics(mergeDescendants = true) {
        contentDescription = label + if (state.queued.isNotEmpty()) "，排队：$queuedNames" else ""
    }, style = palette.card, shape = GlassShape.Capsule,
        contentPadding = PaddingValues(start = 7.dp, end = 13.dp, top = 5.dp, bottom = 5.dp)) {
        Row(Modifier.animateContentSize(spring(dampingRatio = .9f, stiffness = Spring.StiffnessMediumLow)),
            verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                state.lead.take(3).forEach { WaitingAvatar(it, pulsing = state.active.isNotEmpty()) }
            }
            Spacer(Modifier.width(8.dp))
            Crossfade(label, Modifier.weight(1f, fill = false), animationSpec = tween(160), label = "group-waiting") { text ->
                Text(text, color = palette.content, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.queued.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text("排队", color = palette.contentSecondary, fontSize = 11.sp)
                Spacer(Modifier.width(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) { state.queued.take(3).forEach { QueuedAvatar(it) } }
                if (state.queued.size > 3) {
                    Spacer(Modifier.width(4.dp))
                    Text("+${state.queued.size - 3}", color = palette.contentSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun WaitingAvatar(row: GroupMessageEntity, pulsing: Boolean) {
    val palette = LocalGlassPalette.current
    val ring = rememberPulse(pulsing)
    // The ring is drawn from the animated value in the draw phase only, so breathing does not recompose the list.
    Box(Modifier.size(28.dp).drawBehind {
        if (pulsing) drawCircle(palette.accent.copy(alpha = ring.value * .75f),
            radius = size.minDimension / 2 - .75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
    }.padding(3.dp), contentAlignment = Alignment.Center) {
        Avatar(row.speakerAvatar, row.speakerEmoji ?: avatarLetter(row.speakerName, "TA"), 22.dp)
    }
}

@Composable
private fun QueuedAvatar(row: GroupMessageEntity) {
    val palette = LocalGlassPalette.current
    Box(Modifier.size(22.dp).alpha(.55f).border(1.dp, palette.contentSecondary.copy(alpha = .25f), CircleShape).padding(1.5.dp)) {
        Avatar(row.speakerAvatar, row.speakerEmoji ?: avatarLetter(row.speakerName, "TA"), 19.dp)
    }
}

/** A slow, gentle breath between dim and full; only runs while someone is actually answering. */
@Composable
private fun rememberPulse(on: Boolean): State<Float> {
    if (!on) return remember { mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "group-waiting-pulse")
    return transition.animateFloat(.25f, 1f, infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ring")
}
