package com.cleo.cleos.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.db.ConversationEntity
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationsScreen(onBack: () -> Unit, onSearch: () -> Unit, onGroup: (Long) -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    // Only the current TA's: each TA's conversations are theirs alone.
    val ta by remember { c.companions.current }.collectAsStateWithLifecycle(null)
    val conversations by remember(ta?.id) {
        ta?.let { c.db.conversations().observeFor(it.id) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val current by remember { c.settings.currentConversation }.collectAsStateWithLifecycle(null)
    var confirm by remember { mutableStateOf<ConversationEntity?>(null) }
    val groups by remember { c.db.groups().observeGroups() }.collectAsStateWithLifecycle(emptyList())
    val currentGroup by c.settings.currentGroup.collectAsStateWithLifecycle(null)
    val tas by c.companions.all.collectAsStateWithLifecycle(emptyList())
    var groupTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    var confirmGroup by remember { mutableStateOf<com.cleo.cleos.data.db.ChatGroupEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var groupProblem by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = if (groupTab) "大家的群聊" else ta?.name?.takeIf { it.isNotBlank() }?.let { "和${it}的对话" } ?: "对话记录",
                subtitle = "长按可以删除",
                backdrop = page,
                leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
                trailing = {
                    if (!groupTab) GlassIconButton(Icons.Rounded.Search, "查找聊天记录", onSearch, page)
                    Spacer(Modifier.width(10.dp))
                    GlassIconButton(Icons.Rounded.AddComment, if (groupTab) "新建群聊" else "新对话", {
                        if (groupTab) { groupProblem = null; newGroup = true } else { ta?.let { t ->
                            c.appScope.launch { c.settings.setCurrentConversation(c.chat.newConversation(t.id)) }
                        }
                        onBack()
                        }
                    }, page)
                },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .fadeUnderTopBar(statusTop + TopBarHeight),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = statusTop + TopBarHeight + 10.dp, bottom = navBottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "conversation-tabs") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.cleo.cleos.ui.settings.Chip("私聊", !groupTab) { groupTab = false }
                    com.cleo.cleos.ui.settings.Chip("群聊", groupTab) { groupTab = true }
                }
            }
            if (groupTab) {
                groupProblem?.takeIf { !newGroup }?.let { error -> item { Text(error, color = palette.error) } }
                if (groups.isEmpty()) item { Text("还没有群聊。点右上角，选几位 TA 一起聊聊。", color = palette.contentSecondary, modifier = Modifier.padding(16.dp)) }
                items(groups, key = { "group-${it.id}" }) { group ->
                    val members by remember(group.id) { c.db.groups().observeMembers(group.id) }.collectAsStateWithLifecycle(emptyList())
                    GlassSurface(Modifier.fillMaxWidth().combinedClickable(onClick = { onGroup(group.id) }, onLongClick = { confirmGroup = group }),
                        shape = GlassShape.Rounded(22.dp), contentPadding = PaddingValues(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { members.filter { it.active }.take(4).forEach { member ->
                                com.cleo.cleos.ui.common.Avatar(member.avatar, member.emoji ?: com.cleo.cleos.ui.common.avatarLetter(member.name, "TA"), 24.dp)
                            } }
                            Column(Modifier.weight(1f)) {
                                Text(group.title, color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("${members.count { it.active }} 位 TA · ${Dates.chatStamp(group.updatedAt)}", color = palette.contentSecondary, fontSize = 12.sp)
                            }
                            if (group.id == currentGroup) Box(Modifier.size(8.dp).background(palette.accentContent, CircleShape))
                        }
                    }
                }
            } else items(conversations, key = { it.id }) { conv ->
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                c.appScope.launch { c.settings.setCurrentConversation(conv.id) }
                                onBack()
                            },
                            onLongClick = { confirm = conv },
                        ),
                    shape = GlassShape.Rounded(22.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(conv.title, color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Dates.chatStamp(conv.updatedAt), color = palette.contentSecondary, fontSize = 12.sp)
                        }
                        if (conv.id == current) {
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.size(8.dp).background(palette.accentContent, CircleShape))
                        }
                    }
                }
            }
        }
    }

    if (newGroup) NewGroupDialog(tas, creating, groupProblem, { if (!creating) newGroup = false }) { name, ids ->
        scope.launch {
            creating = true; groupProblem = null
            try { val id = c.groupChat.create(name, ids); newGroup = false; onGroup(id) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { groupProblem = e.message ?: "群聊没有建好，请重试" }
            finally { creating = false }
        }
    }
    confirmGroup?.let { group -> DeleteGroupDialog(group.title, { confirmGroup = null }) {
        scope.launch {
            try { c.groupChat.delete(group.id); confirmGroup = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { groupProblem = e.message }
        }
    } }

    confirm?.let { conv ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("删除这段对话？") },
            text = { Text("「${conv.title}」里的消息会一起删掉，删了找不回来。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    c.chat.deleteConversation(conv.id)
                }) { Text("删除", color = LocalGlassPalette.current.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}
