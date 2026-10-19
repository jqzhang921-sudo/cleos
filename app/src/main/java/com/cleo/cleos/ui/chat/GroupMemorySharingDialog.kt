package com.cleo.cleos.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cleo.cleos.ai.GroupMemoryGrant
import com.cleo.cleos.ai.GroupPrivateMemories
import com.cleo.cleos.ai.MemoryKinds
import com.cleo.cleos.data.MemoryDetails
import com.cleo.cleos.data.db.ChatGroupEntity
import com.cleo.cleos.data.db.GroupMemberEntity
import com.cleo.cleos.data.db.MemoryEntity
import com.cleo.cleos.glass.*

@Composable
internal fun GroupMemorySharingDialog(group: ChatGroupEntity, member: GroupMemberEntity, memories: List<MemoryEntity>?,
    busy: Boolean, problem: String?, memoryEnabled: Boolean, onDismiss: () -> Unit, onSave: (Boolean, List<GroupMemoryGrant>) -> Unit) {
    val palette = LocalGlassPalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var enabled by rememberSaveable(group.id, group.createdAt, member.companionId) { mutableStateOf(member.sharePrivateMemories) }
    var selectedJson by rememberSaveable(group.id, group.createdAt, member.companionId) { mutableStateOf(member.privateMemoryGrants) }
    var search by rememberSaveable(group.id, member.companionId) { mutableStateOf("") }
    var expanded by rememberSaveable(group.id, member.companionId) { mutableStateOf(emptyList<Long>()) }
    val selected = remember(selectedJson) { GroupPrivateMemories.decode(selectedJson).associateBy { it.id } }
    val eligible = remember(memories, member.companionId) {
        memories.orEmpty().filter { it.companionId == member.companionId && GroupPrivateMemories.shareable(it) }
    }
    val fingerprints = remember(eligible) { eligible.associate { it.id to GroupPrivateMemories.fingerprint(it) } }
    val current = selected.values.filter { fingerprints[it.id] == it.fingerprint }
    val stale = selected.size - current.size
    val canSave = !busy && memories != null && (!enabled || current.isNotEmpty())
    val visible = remember(eligible, search) {
        eligible.filter { search.isBlank() || it.name.contains(search, true) || it.summary.contains(search, true) ||
            MemoryDetails.decode(it.details).any { detail -> detail.contains(search, true) } }
    }
    fun toggle(memory: MemoryEntity) {
        val grant = GroupPrivateMemories.grant(memory)
        val next = if (selected[memory.id] == grant) current.filterNot { it.id == memory.id }
            else if (current.size < GroupPrivateMemories.MAX_SELECTED) current.filterNot { it.id == memory.id } + grant else current
        selectedJson = GroupPrivateMemories.encode(next)
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        GlassSurface(Modifier.fillMaxWidth().imePadding(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${member.name}的记忆共享", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!imeVisible) Text("勾选整条记忆（含细节），只供这位 TA 在「${group.title}」参考。TA 在群里说出的内容，其他伙伴也能看到。",
                    color = palette.contentSecondary, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("在这个群使用", color = palette.content, modifier = Modifier.weight(1f))
                    Switch(enabled, { enabled = it }, enabled = !busy && memories != null)
                }
                OutlinedTextField(search, { search = it.take(80) }, singleLine = true, label = { Text("查找这位 TA 的记忆") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }),
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                if (memories == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else {
                    Text("已选择 ${current.size} / ${GroupPrivateMemories.MAX_SELECTED} 条${if (!enabled) " · 共享已关闭" else ""}${if (stale > 0) " · $stale 条待确认" else ""}",
                        color = palette.contentSecondary, fontSize = 12.sp)
                }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!memoryEnabled) item {
                        Text("记忆能力当前已关闭，勾选的记忆也不会读取；需在「能做的事」中开启记忆。",
                            color = palette.contentSecondary, fontSize = 12.sp)
                    }
                    if (memories != null && memories.size > eligible.size) item {
                        Text("有 ${memories.size - eligible.size} 条格式异常，暂不能共享；可以先在记忆页整理。",
                            color = palette.contentSecondary, fontSize = 12.sp)
                    }
                    if (memories != null && visible.isEmpty()) item {
                        Text(if (eligible.isEmpty()) "这位 TA 还没有可共享的记忆。可以先在私聊的记忆页记下一些共同经历。" else "没有找到这条记忆。",
                            color = palette.contentSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
                    }
                    items(visible, key = { it.id }) { memory ->
                        val picked = selected[memory.id]?.fingerprint == fingerprints[memory.id]
                        val canPick = !busy && enabled && (picked || current.size < GroupPrivateMemories.MAX_SELECTED)
                        val details = remember(memory.details) { MemoryDetails.decode(memory.details) }
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth().toggleable(picked, enabled = canPick, role = Role.Checkbox) { toggle(memory) },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(memory.name, color = palette.content, fontWeight = FontWeight.SemiBold)
                                    Text(MemoryKinds.of(memory.kind)?.label.orEmpty(), color = palette.contentSecondary, fontSize = 11.sp)
                                }
                                Checkbox(picked, null, enabled = canPick)
                            }
                            if (memory.summary.isNotBlank()) Text(memory.summary, color = palette.content, fontSize = 13.sp)
                            if (selected.containsKey(memory.id) && !picked) Text("这条内容已更新，需重新勾选", color = palette.contentSecondary, fontSize = 12.sp)
                            if (details.isNotEmpty()) {
                                TextButton({ expanded = if (memory.id in expanded) expanded - memory.id else expanded + memory.id }) {
                                    Text(if (memory.id in expanded) "收起细节" else "查看全部细节（${details.size} 条）", color = palette.accentContent)
                                }
                                if (memory.id in expanded) details.forEach { Text("· $it", color = palette.content, fontSize = 13.sp) }
                            }
                            HorizontalDivider(color = palette.contentSecondary.copy(alpha = 0.15f))
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (stale > 0) Text("有 $stale 条已修改、删除或不可用，不会继续共享。请查看当前内容后重新勾选。",
                                color = palette.contentSecondary, fontSize = 12.sp)
                            Text("回复时按话题取用整条记忆及其细节。未勾选、新增或修改后的内容不会自动带入。",
                                color = palette.contentSecondary, fontSize = 11.sp)
                            Text("关闭后停止后续读取，已发进群里的内容仍保留。离群后授权清空；导入 Cleos 备份后需重新设置。",
                                color = palette.contentSecondary, fontSize = 11.sp)
                        }
                    }
                }
                problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onDismiss, enabled = !busy) { Text("取消", color = palette.content) }
                    TextButton({ onSave(enabled, if (enabled) current else emptyList()) },
                        enabled = canSave) {
                        Text(if (busy) "正在保存…" else "保存", color = if (canSave) palette.accentContent else palette.contentSecondary)
                    }
                }
            }
        }
    }
}
