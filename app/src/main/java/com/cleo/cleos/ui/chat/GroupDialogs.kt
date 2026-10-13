package com.cleo.cleos.ui.chat

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
import com.cleo.cleos.data.db.*
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.avatarLetter

@Composable
internal fun NewGroupDialog(tas: List<CompanionEntity>, busy: Boolean, problem: String?, onCancel: () -> Unit, onCreate: (String, List<Long>) -> Unit) {
    val palette = LocalGlassPalette.current
    var name by rememberSaveable { mutableStateOf("一起聊聊") }
    var selected by rememberSaveable { mutableStateOf(listOf<Long>()) }
    Dialog(onDismissRequest = { if (!busy) onCancel() }) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = 550.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("新建群聊", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("群名") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("选 2–4 位 TA，一起聊天。每位使用自己的角色和模型；普通消息请两位接话，@ 可以点名。", color = palette.contentSecondary, fontSize = 13.sp)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (tas.size < 2) Text("先在设置里添加另一位 TA，就可以一起建群。", color = palette.contentSecondary)
                    tas.forEach { ta ->
                        val picked = ta.id in selected
                        val enabled = !busy && (picked || selected.size < 4)
                        Row(Modifier.fillMaxWidth().clickable(enabled) { selected = if (picked) selected - ta.id else selected + ta.id }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(ta.avatar, ta.avatarEmoji ?: avatarLetter(ta.name, "TA"), 34.dp)
                            Column(Modifier.weight(1f)) {
                                Text(ta.name.ifBlank { "TA" }, color = palette.content)
                                Text(ta.apiModel, color = palette.contentSecondary, fontSize = 11.sp)
                            }
                            Checkbox(picked, null, enabled = enabled)
                        }
                    }
                }
                problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onCancel, enabled = !busy) { Text("取消", color = palette.content) }
                    TextButton({ onCreate(name, selected) }, enabled = !busy && name.isNotBlank() && selected.size in 2..4) {
                        Text(if (busy) "正在建群…" else "建好了", color = palette.accentContent)
                    }
                }
            }
        }
    }
}

@Composable
internal fun GroupMembersDialog(group: ChatGroupEntity, members: List<GroupMemberEntity>, active: Set<Long>,
    selected: List<Long>?, onPick: ((List<Long>) -> Unit)?, onDismiss: () -> Unit, onRename: (String) -> Unit,
    canManage: Boolean = true, onManage: () -> Unit = {}) {
    val palette = LocalGlassPalette.current
    var name by rememberSaveable(group.id) { mutableStateOf(group.title) }
    var picks by rememberSaveable(group.id) { mutableStateOf(selected.orEmpty()) }
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = 550.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (onPick != null) "想请谁接话" else "群里的伙伴", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (onPick == null) OutlinedTextField(name, { name = it.take(40) }, label = { Text("群名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    members.filter { onPick == null || it.active }.forEach { member ->
                        val available = member.active && member.companionId in active
                        Row(Modifier.fillMaxWidth().clickable(enabled = onPick != null && available) {
                            picks = if (member.companionId in picks) picks - member.companionId else picks + member.companionId
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(member.avatar, member.emoji ?: avatarLetter(member.name, "TA"), 34.dp)
                            Text(member.name + when { !member.active -> " · 已离群"; !available -> " · TA 已删除"; else -> "" },
                                color = palette.content, modifier = Modifier.weight(1f))
                            if (onPick != null) Checkbox(member.companionId in picks, null, enabled = available)
                        }
                    }
                }
                Text(if (onPick != null) "只请点名的 TA 回复；不选人时轮流请两位接话。" else "共同聊天与工具结果只留在本群。可查天气、读朋友圈、找公开图库照片并分享，跟随设置中的工具开关；私聊和私密日记各自保留。",
                    color = palette.contentSecondary, fontSize = 12.sp)
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onDismiss) { Text("取消", color = palette.content) }
                    if (onPick == null) TextButton(onManage, enabled = canManage) { Text("调整成员", color = palette.accentContent) }
                    TextButton({ if (onPick != null) onPick(picks.filter { id -> id in active && members.any { it.companionId == id && it.active } }) else onRename(name) },
                        enabled = onPick != null || name.isNotBlank()) {
                        Text(if (onPick != null) "选好了" else "保存群名", color = palette.accentContent)
                    }
                }
            }
        }
    }
}

@Composable
internal fun EditGroupMembersDialog(group: ChatGroupEntity, members: List<GroupMemberEntity>, tas: List<CompanionEntity>,
    busy: Boolean, problem: String?, onDismiss: () -> Unit, onSave: (List<Long>) -> Unit) {
    val palette = LocalGlassPalette.current
    val available = tas.map { it.id }.toSet()
    var selected by rememberSaveable(group.id) { mutableStateOf(members.filter { it.active && it.companionId in available }.map { it.companionId }) }
    val effective = selected.filter { it in available }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(20.dp)) {
            Column(Modifier.heightIn(max = 550.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("调整群成员", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("选 2–4 位 TA · 已选 ${effective.size} 位", color = palette.contentSecondary, fontSize = 13.sp)
                Text("加入后能看到本群已有聊天。离开后不再接话，过去的消息会保留。", color = palette.contentSecondary, fontSize = 12.sp)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    tas.forEach { ta ->
                        val previous = members.firstOrNull { it.companionId == ta.id }
                        val picked = ta.id in effective
                        val enabled = !busy && (picked || effective.size < 4)
                        Row(Modifier.fillMaxWidth().clickable(enabled) {
                            selected = if (picked) effective - ta.id else effective + ta.id
                        }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(previous?.avatar ?: ta.avatar, previous?.emoji ?: ta.avatarEmoji ?: avatarLetter(ta.name, "TA"), 34.dp)
                            Column(Modifier.weight(1f)) {
                                Text(previous?.name ?: ta.name.ifBlank { "TA" }, color = palette.content)
                                Text(when { previous?.active == true -> "当前在群里"; previous != null -> "已离群，可以重新加入"; else -> "还没加入这个群" },
                                    color = palette.contentSecondary, fontSize = 11.sp)
                            }
                            Checkbox(picked, null, enabled = enabled)
                        }
                    }
                }
                if (available.size < 2) Text("需要至少两位 TA，先在设置里添加一位伙伴。", color = palette.contentSecondary, fontSize = 12.sp)
                problem?.let { Text(it, color = palette.error, fontSize = 12.sp) }
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onDismiss, enabled = !busy) { Text("取消", color = palette.content) }
                    TextButton({ onSave(effective) }, enabled = !busy && effective.size in 2..4) {
                        Text(if (busy) "正在保存…" else "保存成员", color = palette.accentContent)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeleteGroupDialog(name: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalGlassPalette.current
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(Modifier.fillMaxWidth(), style = palette.card, contentPadding = PaddingValues(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("删除这个群聊？", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("「$name」的群消息会一起删除。成员的私聊会保留。", color = palette.contentSecondary)
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onDismiss) { Text("取消", color = palette.content) }
                    TextButton(onDelete) { Text("删除", color = palette.error) }
                }
            }
        }
    }
}
