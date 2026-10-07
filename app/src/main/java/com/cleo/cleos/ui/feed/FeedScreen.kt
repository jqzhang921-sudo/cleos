package com.cleo.cleos.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.FeedPostEntity
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FeedScreen(onBack: () -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val posts by c.feed.posts.collectAsStateWithLifecycle(emptyList())
    val companions by c.companions.all.collectAsStateWithLifecycle(emptyList())
    val current by c.companions.current.collectAsStateWithLifecycle(null)
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var onlyCurrent by rememberSaveable { mutableStateOf(false) }
    var composing by rememberSaveable { mutableStateOf(false) }
    var commenting by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<FeedPostEntity?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action(); problem = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem = e.message ?: "操作没完成，请重试" }
            finally { busy = false }
        }
    }
    GlassPage(overlay = { page ->
        GlassTopBar(title = "动态", subtitle = "你和 TA 们的小广场", backdrop = page,
            leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
            trailing = { GlassIconButton(Icons.Rounded.Edit, "发动态", { draft = ""; composing = true }, page, enabled = !busy) })
    }) {
        LazyColumn(Modifier.fillMaxSize().fadeUnderTopBar(top + TopBarHeight),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + TopBarHeight + 12.dp, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!onlyCurrent, { onlyCurrent = false }, label = { Text("全部") })
                    FilterChip(onlyCurrent, { onlyCurrent = true }, label = { Text(current?.name?.ifBlank { "当前 TA" } ?: "当前 TA") })
                }
            }
            problem?.let { item { Text(it, color = palette.content, fontSize = 13.sp) } }
            val visible = posts.filter { !onlyCurrent || it.authorId == 0L || it.authorId == current?.id }
            if (visible.isEmpty()) item {
                GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.DynamicFeed, null, tint = palette.accent, modifier = Modifier.size(30.dp))
                        Text("今天想分享什么？", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("一个发现、一句想法，或者想一起聊的话题。", color = palette.content.copy(alpha = .7f), fontSize = 14.sp)
                        TextButton({ draft = ""; composing = true }) { Text("发第一条动态", color = palette.accent) }
                    }
                }
            }
            items(visible, key = { it.id }) { post ->
                val author = companions.firstOrNull { it.id == post.authorId }
                val name = if (post.authorId == 0L) settings.userName.ifBlank { "我" } else author?.name?.ifBlank { "TA" } ?: "TA"
                val comments = remember(post.comments) { FeedComments.decode(post.comments) }
                var expanded by remember(post.id) { mutableStateOf(false) }
                GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(if (post.authorId == 0L) settings.userAvatar else author?.avatar,
                                if (post.authorId == 0L) avatarLetter(name, "我") else author?.avatarEmoji ?: avatarLetter(name, "TA"), 34.dp)
                            Column(Modifier.weight(1f)) {
                                Text(name, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(post.createdAt)), color = palette.content.copy(alpha = .55f), fontSize = 11.sp)
                            }
                            if (post.authorId == 0L) IconButton({ deleting = post }, enabled = !busy, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.DeleteOutline, "删除动态", tint = palette.content.copy(alpha = .5f), modifier = Modifier.size(18.dp))
                            }
                        }
                        Text(post.content, color = palette.content, fontSize = 16.sp, lineHeight = 24.sp)
                        post.sourceUrl?.let { link ->
                            Text(post.sourceTitle ?: "查看来源", color = palette.accent, fontSize = 12.sp,
                                modifier = Modifier.clickable { runCatching { uri.openUri(link) }.onFailure { problem = "无法打开来源" } })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton({ act { c.feed.like(post.id) } }, enabled = !busy) {
                                Icon(if (post.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp)); Text(if (post.liked) "已喜欢" else "喜欢", color = palette.content, fontSize = 13.sp)
                            }
                            TextButton({ draft = ""; commenting = post.id }, enabled = !busy) {
                                Icon(Icons.Rounded.ChatBubbleOutline, null, tint = palette.content, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp)); Text(if (comments.isEmpty()) "回复" else "回复 ${comments.size}", color = palette.content, fontSize = 13.sp)
                            }
                        }
                        if (comments.isNotEmpty()) {
                            HorizontalDivider(color = palette.content.copy(alpha = .1f))
                            (if (expanded) comments else comments.takeLast(3)).forEach { comment ->
                                val who = if (comment.authorId == 0L) settings.userName.ifBlank { "我" }
                                    else companions.firstOrNull { it.id == comment.authorId }?.name ?: "TA"
                                Text("$who：${comment.content}", color = palette.content, fontSize = 14.sp, lineHeight = 21.sp)
                            }
                            if (comments.size > 3) TextButton({ expanded = !expanded }) { Text(if (expanded) "收起回复" else "查看全部回复", color = palette.accent) }
                        }
                    }
                }
            }
        }
    }
    if (composing || commenting != null) Dialog(onDismissRequest = { if (!busy) { composing = false; commenting = null } }) {
        GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (composing) "发动态" else "回复动态", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(draft, { draft = it.take(4000) }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp), enabled = !busy,
                    placeholder = { Text("分享你的想法…") })
                problem?.let { Text(it, color = palette.content, fontSize = 12.sp) }
                Row(Modifier.align(Alignment.End)) {
                    TextButton({ composing = false; commenting = null }, enabled = !busy) { Text("取消", color = palette.content) }
                    TextButton({
                        val id = commenting; val text = draft
                        act {
                            if (id == null) c.feed.publish(text) else c.feed.comment(id, text)
                            composing = false; commenting = null; draft = ""
                        }
                    }, enabled = !busy && draft.isNotBlank()) { Text(if (busy) "保存中…" else "发布", color = palette.accent) }
                }
            }
        }
    }
    deleting?.let { post -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条动态？") },
        text = { Text("这条动态和下面的回复会一起删除。") },
        confirmButton = { TextButton({ act { c.feed.delete(post.id); deleting = null } }, enabled = !busy) { Text("删除") } },
        dismissButton = { TextButton({ deleting = null }) { Text("取消") } }) }
}
