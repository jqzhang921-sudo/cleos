package com.cleo.cleos.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.BubblePreset
import com.cleo.cleos.glass.LocalGlassPalette

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun BubblePresetsEditor(presets: List<BubblePreset>, vm: SettingsViewModel) {
    val context = LocalContext.current
    val palette = LocalGlassPalette.current
    var expanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var exportId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<BubblePreset?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = exportId
        exportId = null
        if (uri != null && id != null) vm.exportBubblePreset(id) { context.contentResolver.openOutputStream(uri) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBubblePreset { context.contentResolver.openInputStream(uri) }
    }
    Chip(if (expanded) "收起我的方案" else "我的气泡方案 · ${presets.size}", expanded) { expanded = !expanded }
    if (expanded) {
        Text("保存当前双方主题、配色、留白与装饰；使用时应用到你和当前 TA。装饰与留白仍为全局设置。", color = palette.contentSecondary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("保存当前方案", false) { saving = true; name = "" }
            Chip(if (vm.bubblePresetBusy) "正在处理…" else "导入气泡包", false) { if (!vm.bubblePresetBusy) import.launch(arrayOf("*/*")) }
            Chip("撤回上次切换", false) { vm.undoBubblePreset() }
        }
        presets.forEach { preset -> key(preset.id) {
            Text(preset.name, color = palette.content)
            Text("${preset.decoration.components.size + (if (preset.decoration.faceEnabled) 1 else 0) + (if (preset.decoration.starsEnabled) 1 else 0)} 个装饰 · 留白 ${preset.paddingX}/${preset.paddingY}", color = palette.contentSecondary, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("使用", false) { if (!vm.bubblePresetBusy) vm.applyBubblePreset(preset) }
                Chip("导出", false) { if (!vm.bubblePresetBusy) { exportId = preset.id; export.launch("Cleos-${preset.name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")}.cleobubble") } }
                Chip("删除", false) { deleting = preset }
            }
        } }
        vm.bubblePresetMessage?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
    }
    if (saving) AlertDialog(onDismissRequest = { saving = false }, title = { Text("保存气泡方案") },
        text = { OutlinedTextField(name, { name = it.take(64) }, label = { Text("方案名称") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { vm.saveBubblePreset(name); saving = false }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { saving = false }) { Text("取消") } })
    deleting?.let { preset -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除「${preset.name}」？") },
        text = { Text("当前使用的气泡仍会保留。") },
        confirmButton = { TextButton(onClick = { vm.deleteBubblePreset(preset.id); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
