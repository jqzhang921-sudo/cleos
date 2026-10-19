package com.cleo.cleos.ui.chat

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.cleo.cleos.ai.GroupMemoryRules
import com.cleo.cleos.data.StickerText
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.*
import kotlinx.coroutines.launch

@Composable
internal fun GroupMemoriesDialog(notes: List<GroupMemoryEntity>, sources: Map<Long, GroupMessageEntity>, busy: Boolean,
    problem: String?, search: String, onSearch: (String) -> Unit, onDismiss: () -> Unit, onNew: () -> Unit, onEdit: (GroupMemoryEntity) -> Unit,
    onDelete: (GroupMemoryEntity) -> Unit, onSource: (GroupMessageEntity) -> Unit) {
    val palette = LocalGlassPalette.current
    val visible = notes.filter { search.isBlank() || it.title.contains(search, true) || it.content.contains(search, true) }
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = 570.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("群内小本本", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("只在这个群使用，新加入的伙伴也能看到。长按已发送的消息可以记下；回复时按话题选取相关记录。",
                    color = palette.contentSecondary, fontSize = 12.sp)
                OutlinedTextField(search, { onSearch(it.take(80)) }, label = { Text("找一条记录") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("${notes.size} / ${GroupMemoryRules.MAX_NOTES} 条${if (busy) " · 等这轮回复结束再整理" else ""}", color = palette.contentSecondary, fontSize = 12.sp)
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (visible.isEmpty()) item { Text(if (search.isBlank()) "还没有记下什么。约定、共同经历和玩笑，都可以放在这里。" else "没有找到这条记录。",
                        color = palette.contentSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp)) }
                    items(visible, key = { it.id }) { note ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(note.title, color = palette.content, fontWeight = FontWeight.SemiBold)
                            Text(note.content, color = palette.content, fontSize = 14.sp, maxLines = 4)
                            val original = sources[note.sourceMessageId]?.takeIf { it.groupId == note.groupId }
                            Text(GroupMemoryRules.sourceLabel(note, original), color = palette.contentSecondary, fontSize = 11.sp)
                            Row {
                                TextButton({ onEdit(note) }, enabled = !busy) { Text("修改", color = palette.accentContent) }
                                if (original != null) TextButton({ onSource(original) }) { Text("原消息", color = palette.content) }
                                TextButton({ onDelete(note) }, enabled = !busy) { Text("删除", color = palette.error) }
                            }
                            HorizontalDivider(color = palette.contentSecondary.copy(alpha = 0.15f))
                        }
                    }
                }
                problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onDismiss) { Text("关闭", color = palette.content) }
                    TextButton(onNew, enabled = !busy && notes.size < GroupMemoryRules.MAX_NOTES) { Text("记一条", color = palette.accentContent) }
                }
            }
        }
    }
}

@Composable
internal fun EditGroupMemoryDialog(note: GroupMemoryEntity?, source: GroupMessageEntity?, busy: Boolean, problem: String?,
    onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val palette = LocalGlassPalette.current
    var title by rememberSaveable(note?.id, source?.id) { mutableStateOf(note?.title.orEmpty()) }
    var content by rememberSaveable(note?.id, source?.id) { mutableStateOf(note?.content ?: source?.content.orEmpty().take(GroupMemoryRules.MAX_CONTENT)) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        GlassSurface(Modifier.fillMaxWidth().imePadding(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (note == null) "记到群里" else "修改群内记录", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("整理后保存，只有本群的伙伴会读到；不会写进各自的私人记忆。", color = palette.contentSecondary, fontSize = 12.sp)
                if (source != null) Text("来自${if (source.role == "user") "你的" else source.speakerName + "的"}群消息${
                    if (note == null && source.content.length > GroupMemoryRules.MAX_CONTENT) " · 已取前 ${GroupMemoryRules.MAX_CONTENT} 字，请整理" else ""}",
                    color = palette.contentSecondary, fontSize = 12.sp)
                OutlinedTextField(title, { title = it.take(GroupMemoryRules.MAX_TITLE) }, label = { Text("记录标题") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(content, { content = it.take(GroupMemoryRules.MAX_CONTENT) }, label = { Text("想记住的内容") },
                    minLines = 3, maxLines = 8, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("${content.length} / ${GroupMemoryRules.MAX_CONTENT} 字", color = palette.contentSecondary, fontSize = 11.sp)
                problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onDismiss, enabled = !busy) { Text("取消", color = palette.content) }
                    TextButton({ onSave(title, content) }, enabled = !busy && title.isNotBlank() && content.isNotBlank()) {
                        Text(if (busy) "正在保存…" else "保存记录", color = palette.accentContent)
                    }
                }
            }
        }
    }
}

@Composable
internal fun GroupMessageActionsMenu(row: GroupMessageEntity, expanded: Boolean, canRemember: Boolean, canDelete: Boolean, onDismiss: () -> Unit,
    onSelect: (String) -> Unit, onRemember: () -> Unit, onDeleteSingle: () -> Unit, onDeleteRound: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val transcript = if (row.role == "user") row.content.removePrefix(GROUP_CALL_USER)
        else groupCallWords(row.content)?.let { listOf(it.spoken, it.unfinished).filter(String::isNotBlank).joinToString("\n") } ?: row.content
    val words = StickerText.plain(transcript)
    val actions = buildList {
        if (words.isNotBlank()) {
            add(MessageMenuAction("复制") { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", words))) } })
            add(MessageMenuAction("选择文字") { onSelect(words) })
        }
        if (canRemember) add(MessageMenuAction("记到群里", onRemember))
        if (canDelete) {
            if (row.role == "assistant") add(MessageMenuAction("删除", onDeleteSingle))
            add(MessageMenuAction("删除这一轮", onDeleteRound))
        }
    }
    if (actions.isNotEmpty()) MessageActionMenu(expanded, onDismiss, actions, reactions = null, onReact = {})
}

@Composable
internal fun DeleteGroupMemoryDialog(note: GroupMemoryEntity, busy: Boolean, problem: String?, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalGlassPalette.current
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("删除这条群内记录？", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(note.title, color = palette.content)
                Text("原来的聊天消息会保留，之后的回复不再读取这条小本本记录。", color = palette.contentSecondary, fontSize = 13.sp)
                problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onDismiss, enabled = !busy) { Text("取消", color = palette.content) }
                    TextButton(onDelete, enabled = !busy) { Text("删除记录", color = palette.error) }
                }
            }
        }
    }
}
