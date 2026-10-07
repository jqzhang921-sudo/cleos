package com.cleo.cleos.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.ModelProfile
import com.cleo.cleos.data.ModelProfileRules
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.GlassShape

@Composable
internal fun ModelProfilesEditor(fields: EndpointFields) {
    val profiles by fields.profiles.collectAsStateWithLifecycle()
    val legacy by fields.legacyProfiles.collectAsStateWithLifecycle()
    val palette = LocalGlassPalette.current
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ModelProfile?>(null) }
    var deleting by remember { mutableStateOf<ModelProfile?>(null) }
    var name by remember { mutableStateOf("") }
    var showingLegacy by remember { mutableStateOf(false) }
    val active = profiles.firstOrNull { ModelProfileRules.matches(it, fields.activeUrl, fields.activeModel) }
    LaunchedEffect(fields.savingProfile) {
        if (fields.savingProfile) name = profiles.firstOrNull { ModelProfileRules.matches(it, fields.baseUrl, fields.model) }?.name ?: fields.model
    }
    Text("当前使用 · ${active?.name ?: fields.activeModel.ifBlank { "尚未配置" }}", color = palette.content, fontSize = 15.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("选择已保存的配置 · ${profiles.size}", selected = false) { if (!fields.profileBusy) { showingLegacy = false; picking = true } }
    }
    Text("点「使用」切换。下面填写的是草稿，保存后才启用；返回不会改变当前配置。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    fields.profileMessage?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
    if (picking) Dialog(onDismissRequest = { picking = false }) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            style = palette.card,
            shape = GlassShape.Rounded(28.dp),
            contentPadding = PaddingValues(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("已保存的模型配置", color = palette.content, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                if (legacy.isNotEmpty()) TextButton(onClick = { showingLegacy = !showingLegacy }) {
                    Text(if (showingLegacy) "返回我的配置" else "整理旧版记录")
                }
                val entries = if (showingLegacy) legacy else profiles
                if (entries.isEmpty()) Text("还没有保存的配置，填好连接后点「保存当前配置」。", color = palette.contentSecondary, fontSize = 14.sp)
                else LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.id }) { profile ->
                        val isActive = profile.id == active?.id
                        Column(
                            Modifier.fillMaxWidth()
                                .background(palette.content.copy(alpha = if (isActive) 0.08f else 0.035f), RoundedCornerShape(16.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Column(Modifier.fillMaxWidth().clickable(enabled = !fields.profileBusy) { fields.useProfile(profile); picking = false }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(profile.name, modifier = Modifier.weight(1f), color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (isActive) Text("使用中", color = palette.contentSecondary, fontSize = 11.sp)
                                }
                                Text(profile.model, fontSize = 12.sp, color = palette.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(profile.baseUrl, fontSize = 11.sp, color = palette.contentSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(enabled = !fields.profileBusy, onClick = { fields.useProfile(profile); picking = false }) { Text("使用") }
                                TextButton(onClick = { renaming = profile; name = profile.name; naming = true }) { Text("重命名") }
                                TextButton(onClick = { deleting = profile }) { Text("删除") }
                            }
                        }
                    }
                }
                TextButton(onClick = { picking = false }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
    if (naming || fields.savingProfile) AlertDialog(onDismissRequest = { naming = false; fields.savingProfile = false }, title = { Text(if (fields.savingProfile) "保存并启用模型配置" else "重命名配置") },
        text = { OutlinedTextField(name, { name = it.take(64) }, label = { Text("配置名称") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !fields.profileBusy, onClick = {
            val profile = if (fields.savingProfile) null else renaming
            if (profile == null) fields.saveProfile(name) else fields.renameProfile(profile.id, name)
            naming = false
            fields.savingProfile = false
        }) { Text(if (fields.savingProfile) "保存并启用" else "保存") } }, dismissButton = { TextButton(onClick = { naming = false; fields.savingProfile = false }) { Text("取消") } })
    deleting?.let { profile -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除「${profile.name}」？") },
        text = { Text("仅移除列表里的配置，当前 TA 的连接、聊天记录和已保存的 Key 都会保留。") },
        confirmButton = { TextButton(enabled = !fields.profileBusy, onClick = { fields.deleteProfile(profile); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
