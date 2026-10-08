package com.cleo.cleos.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cleo.cleos.data.FeedComments
import com.cleo.cleos.data.FeedInteractions
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.FeedPostEntity
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.avatarLetter

/** An explicit, one-model invitation; opening this chooser never makes an API request. */
@Composable
internal fun FeedInviteDialog(post: FeedPostEntity, companions: List<CompanionEntity>, currentId: Long?,
    userName: String, busy: Boolean, problem: String?, onCancel: () -> Unit,
    onInvite: (Long, String?) -> Unit) {
    val palette = LocalGlassPalette.current
    val comments = remember(post.comments) { FeedComments.decode(post.comments) }
    fun targets(id: Long) = buildList<String?> {
        if (FeedInteractions.canInvite(post, id, null)) add(null)
        comments.takeLast(8).filter { FeedInteractions.canInvite(post, id, it.id) }.forEach { add(it.id) }
    }
    val eligible = companions.filter { targets(it.id).isNotEmpty() }
    var selected by rememberSaveable(post.id) { mutableStateOf(currentId?.takeIf { id -> eligible.any { it.id == id } } ?: eligible.firstOrNull()?.id) }
    var replyTo by rememberSaveable(post.id) { mutableStateOf(selected?.let { targets(it).lastOrNull() }) }
    val choices = selected?.let { targets(it) }.orEmpty()
    val selectionValid = selected != null && replyTo in choices
    fun name(id: Long) = if (id == 0L) userName.ifBlank { "我" } else companions.firstOrNull { it.id == id }?.name?.ifBlank { "TA" } ?: "TA"
    Dialog(onDismissRequest = { if (!busy) onCancel() }) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = 550.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("邀请 TA 看看", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("TA 可以点赞、回复，也可以只看看。会使用所选 TA 的模型，图文动态会带上照片。", color = palette.contentSecondary, fontSize = 13.sp)
                    if (eligible.isEmpty()) Text("这里暂时没有新的内容可邀请 TA 看。自己的帖子需要有朋友的评论，同一段内容不会重复邀请。", color = palette.contentSecondary, fontSize = 14.sp)
                    eligible.forEach { ta ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = ta.id; replyTo = targets(ta.id).lastOrNull() }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(ta.avatar, ta.avatarEmoji ?: avatarLetter(ta.name, "TA"), 32.dp)
                            Text(ta.name.ifBlank { "TA" }, color = palette.content, modifier = Modifier.weight(1f))
                            RadioButton(selected == ta.id, null, enabled = !busy)
                        }
                    }
                    if (choices.isNotEmpty()) {
                        HorizontalDivider(color = palette.content.copy(alpha = .1f))
                        Text("回应哪一段", color = palette.contentSecondary, fontSize = 13.sp)
                        choices.forEach { id ->
                            val comment = comments.firstOrNull { it.id == id }
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { replyTo = id }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(replyTo == id, null, enabled = !busy)
                                Text(if (comment == null) "这条动态" else "${name(comment.authorId)}：${comment.content.take(70)}",
                                    color = palette.content, fontSize = 14.sp, maxLines = 3, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                }
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onCancel) { Text(if (busy) "取消邀请" else "取消", color = palette.content) }
                    TextButton({ selected?.let { onInvite(it, replyTo) } }, enabled = !busy && selectionValid) {
                        Text(if (busy) "TA 正在看…" else "邀请看看", color = palette.accentContent)
                    }
                }
            }
        }
    }
}
