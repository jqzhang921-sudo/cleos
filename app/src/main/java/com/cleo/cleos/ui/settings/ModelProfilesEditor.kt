package com.cleo.cleos.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.ModelProfile
import com.cleo.cleos.data.ModelProfileRules
import com.cleo.cleos.glass.LocalGlassPalette

@Composable
internal fun ModelProfilesEditor(fields: EndpointFields) {
    val profiles by fields.profiles.collectAsStateWithLifecycle()
    val palette = LocalGlassPalette.current
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ModelProfile?>(null) }
    var deleting by remember { mutableStateOf<ModelProfile?>(null) }
    var name by remember { mutableStateOf("") }
    val active = profiles.firstOrNull { ModelProfileRules.matches(it, fields.baseUrl, fields.model) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(active?.name ?: "选择配置 · ${profiles.size}", selected = active != null) { if (!fields.profileBusy) picking = true }
        Chip("保存当前配置", false) { if (!fields.profileBusy) { name = active?.name ?: fields.model; renaming = null; naming = true } }
    }
    Text("地址和模型会保留为历史配置，也可以命名保存。聊天和电话共用这个列表；Key 仍按接口地址加密保存，切换后无需重填。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    fields.profileMessage?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
    if (picking) AlertDialog(onDismissRequest = { picking = false }, title = { Text("已保存的模型配置") },
        text = {
            if (profiles.isEmpty()) Text("还没有保存的配置，填好连接后点「保存当前配置」。")
            else LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(profiles, key = { it.id }) { profile ->
                    Column(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().clickable(enabled = !fields.profileBusy) { fields.useProfile(profile); picking = false }.padding(vertical = 4.dp)) {
                            Text((if (profile.id == active?.id) "✓ " else "") + profile.name)
                            Text(profile.model, fontSize = 12.sp, color = palette.contentSecondary)
                            Text(profile.baseUrl, fontSize = 11.sp, color = palette.contentSecondary)
                        }
                        Row {
                            TextButton(enabled = !fields.profileBusy, onClick = { fields.useProfile(profile); picking = false }) { Text("使用") }
                            TextButton(onClick = { renaming = profile; name = profile.name; naming = true }) { Text("重命名") }
                            TextButton(onClick = { deleting = profile }) { Text("删除") }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { picking = false }) { Text("关闭") } })
    if (naming) AlertDialog(onDismissRequest = { naming = false }, title = { Text(if (renaming == null) "保存模型配置" else "重命名配置") },
        text = { OutlinedTextField(name, { name = it.take(64) }, label = { Text("配置名称") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !fields.profileBusy, onClick = {
            val profile = renaming
            if (profile == null) fields.saveProfile(name) else fields.renameProfile(profile.id, name)
            naming = false
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { naming = false }) { Text("取消") } })
    deleting?.let { profile -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除「${profile.name}」？") },
        text = { Text("仅移除列表里的配置，当前 TA 的连接、聊天记录和已保存的 Key 都会保留。") },
        confirmButton = { TextButton(enabled = !fields.profileBusy, onClick = { fields.deleteProfile(profile); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
