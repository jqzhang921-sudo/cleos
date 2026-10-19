package com.cleo.cleos.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette

/** One folded piece of glass for everything done on the way to a reply. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ActivityCard(
    events: List<ChatActivity>, live: Boolean, identity: Any, maxWidth: Dp,
    forceOpen: Boolean = false, onOpenCall: (Long) -> Unit, onLongClick: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val expanded = if (live) remember(identity) { mutableStateOf(false) }
        else rememberSaveable(identity) { mutableStateOf(false) }
    var open by expanded
    LaunchedEffect(forceOpen) { if (forceOpen) open = true }
    val tools = remember(events) { events.filterIsInstance<ChatActivity.Tool>() }
    val failures = remember(tools) { tools.map { ToolRuns.failed(it.message.note.orEmpty()) } }
    val stack = remember(tools, failures) { ToolKinds.stack(tools.map { it.kind }, failures, max = 3) }
    val interrupted = events.any { it is ChatActivity.Thought && it.interrupted }
    val failed = failures.any { it } || interrupted
    val hasThought = events.any { it is ChatActivity.Thought }
    val description = "回复过程：${tools.size} 次工具调用" +
        (if (hasThought) "，含思考内容" else "") + (if (live) "，正在进行" else "") +
        (if (interrupted) "，回复已中断" else "") +
        (if (failed) "，有未完成的操作" else "")
    val angle by animateFloatAsState(if (open) 180f else 0f,
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "activity-chevron")
    val scroll = rememberScrollState()
    GlassSurface(Modifier.widthIn(max = maxWidth), style = palette.card,
        shape = GlassShape.Rounded(18.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.animateContentSize(spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow))) {
            Row(
                Modifier.widthIn(min = 32.dp).heightIn(min = 24.dp)
                    .semantics {
                        contentDescription = description
                        stateDescription = if (open) "已展开" else "已收起"
                    }
                    .combinedClickable(interactionSource = null, indication = null, role = Role.Button,
                        onClickLabel = if (open) "收起回复过程" else "展开回复过程",
                        onClick = { open = !open }, onLongClickLabel = "多选过程记录", onLongClick = onLongClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasThought || live) {
                    if (interrupted) Icon(Icons.Outlined.Warning, contentDescription = null,
                        tint = palette.error, modifier = Modifier.size(14.dp))
                    else ActivityDot(live)
                    if (stack.shown.isNotEmpty()) Spacer(Modifier.width(8.dp))
                }
                // Keep the original connected tool-icon strip while folding thoughts into the same panel.
                stack.shown.forEachIndexed { i, (kind, bad) ->
                    if (i > 0) Box(Modifier.padding(horizontal = 3.dp).width(8.dp).height(1.dp)
                        .background(palette.contentSecondary.copy(alpha = 0.35f)))
                    Icon(if (bad) Icons.Outlined.Warning else kind.icon(), contentDescription = null,
                        tint = if (bad) palette.error else palette.contentSecondary, modifier = Modifier.size(16.dp))
                }
                if (stack.more > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("+${stack.more}", color = palette.contentSecondary, fontSize = 12.sp)
                }
                // A failure beyond the displayed kinds must still be visible when folded.
                if (stack.hiddenFailed) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = palette.error, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(if (stack.shown.isEmpty()) 8.dp else 4.dp))
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = palette.contentSecondary,
                    modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = angle })
            }
            if (open) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.content.copy(alpha = 0.08f)))
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(scroll).padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    events.forEach { event ->
                        when (event) {
                            is ChatActivity.Thought -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (event.interrupted) "思考 · 回复已中断" else "思考",
                                    color = if (event.interrupted) palette.error else palette.contentSecondary, fontSize = 12.sp)
                                SelectionContainer {
                                    Text(event.text.trim(), color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                                }
                            }
                            is ChatActivity.Tool -> {
                                val bad = ToolRuns.failed(event.message.note.orEmpty())
                                Row(Modifier.fillMaxWidth().clickable(role = Role.Button,
                                    onClickLabel = "查看工具参数和结果") { onOpenCall(event.message.id) }
                                    .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(if (bad) Icons.Outlined.Warning else event.kind.icon(), contentDescription = null,
                                        tint = if (bad) palette.error else palette.contentSecondary, modifier = Modifier.size(14.dp))
                                    Text(event.message.note.orEmpty(), color = if (bad) palette.error else palette.contentSecondary,
                                        fontSize = 13.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f))
                                    Text("›", color = palette.contentSecondary, fontSize = 13.sp)
                                }
                            }
                            is ChatActivity.Running -> Text(event.text, color = palette.contentSecondary,
                                fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityDot(running: Boolean) {
    val palette = LocalGlassPalette.current
    val opacity = if (running) {
        val transition = rememberInfiniteTransition(label = "activity-status")
        val value by transition.animateFloat(0.35f, 0.9f,
            infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "activity-breath")
        value
    } else 0.45f
    Box(Modifier.size(5.dp).alpha(opacity).background(palette.contentSecondary, CircleShape))
}
