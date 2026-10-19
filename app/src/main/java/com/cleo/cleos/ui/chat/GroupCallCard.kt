package com.cleo.cleos.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.*
import com.cleo.cleos.data.db.GroupMessageEntity

/** Old transcripts need no rewrite; actions always receive the original stored message id. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupCallCard(round: GroupChatItem.CallRound, epoch: Long, userAvatar: String?,
    onExpanded: () -> Unit, messageMenu: @Composable (GroupMessageEntity, Boolean, () -> Unit) -> Unit) {
    val palette = LocalGlassPalette.current
    var open by rememberSaveable(round.anchor.groupId, epoch, round.anchor.id) { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var headerMenu by remember(round.anchor.id) { mutableStateOf(false) }
    val angle by animateFloatAsState(if (open) 180f else 0f,
        spring(dampingRatio = .88f, stiffness = Spring.StiffnessMediumLow), label = "group-call-chevron")
    val names = (listOf(round.anchor) + round.replies.distinctBy { it.speakerId })
        .map { it.speakerName.ifBlank { if (it.role == "user") "我" else "TA" } }
    val state = when { round.ongoing -> "正在接话"; round.incomplete -> "有未完成的发言"; else -> "这一轮的对话" }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        GlassSurface(Modifier.widthIn(max = 500.dp).fillMaxWidth(), style = palette.card,
            shape = GlassShape.Rounded(22.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
            Column(Modifier.animateContentSize(spring(dampingRatio = .9f, stiffness = Spring.StiffnessMediumLow),
                finishedListener = { _, _ -> if (open && reveal) { reveal = false; onExpanded() } })) {
                Box {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {
                    contentDescription = "群通话记录，${round.messages.size} 条，$state"
                    stateDescription = if (open) "已展开" else "已收起"
                }.combinedClickable(role = Role.Button, onClickLabel = if (open) "收起通话记录" else "展开通话记录",
                    onClick = { open = !open; reveal = open }, onLongClickLabel = "操作这一轮转写", onLongClick = { headerMenu = true }),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Call, null, tint = palette.accentContent, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("群通话 · 本轮", color = palette.content, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(Dates.chatStamp(round.anchor.createdAt), color = palette.contentSecondary, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        Text(names.joinToString(" · "), color = palette.contentSecondary, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (round.ongoing || round.incomplete) Text(state,
                            color = if (round.ongoing) palette.accentContent else palette.contentSecondary, fontSize = 11.sp)
                    }
                    Icon(Icons.Rounded.ExpandMore, null, tint = palette.contentSecondary,
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = angle })
                }
                messageMenu(round.anchor, headerMenu) { headerMenu = false }
                }
                if (open) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = palette.contentSecondary.copy(alpha = .14f))
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        round.messages.forEach { row -> key(row.id) {
                            var rowMenu by remember { mutableStateOf(false) }
                            val name = row.speakerName.ifBlank { if (row.role == "user") "我" else "TA" }
                            val words = if (row.role == "user") GroupCallWords(row.content.removePrefix(GROUP_CALL_USER))
                                else groupCallWords(row.content) ?: GroupCallWords(row.content)
                            Box {
                            Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClickLabel = "操作${name}的消息",
                                onLongClick = { rowMenu = true }), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Avatar(if (row.role == "user") userAvatar else row.speakerAvatar,
                                    row.speakerEmoji ?: avatarLetter(name, "TA"), 24.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(name + if (row.role == "user") " · 转写" else "", color = palette.contentSecondary, fontSize = 11.sp)
                                    if (words.spoken.isNotBlank()) MessageText(words.spoken, palette.content,
                                        TextStyle(fontSize = 14.sp, lineHeight = 21.sp))
                                    if (words.unfinished.isNotBlank()) {
                                        Text("这句未完整播完", color = palette.contentSecondary, fontSize = 11.sp)
                                        MessageText(words.unfinished, palette.contentSecondary, TextStyle(fontSize = 14.sp, lineHeight = 21.sp))
                                    }
                                    if (words.spoken.isBlank() && words.unfinished.isBlank()) Text(when (row.status) {
                                        "pending" -> "等会儿接话…"
                                        "running" -> "正在接话…"
                                        "cancelled", "interrupted" -> "这次没有说完"
                                        else -> "这次没有留下发言"
                                    }, color = palette.contentSecondary, fontSize = 13.sp)
                                    row.error?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, color = palette.contentSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                                    }
                                }
                            }
                            messageMenu(row, rowMenu) { rowMenu = false }
                            }
                        } }
                        Text("长按一条发言，可以复制、记进小本本或删除。", color = palette.contentSecondary,
                            fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}
