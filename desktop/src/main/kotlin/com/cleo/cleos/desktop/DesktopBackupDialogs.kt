package com.cleo.cleos.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun DesktopBackupDialog(name: String, phoneArchive: Boolean, exporting: Boolean, saved: Path?,
    dismiss: () -> Unit, cancel: () -> Unit, export: () -> Unit, restore: () -> Unit) {
    AlertDialog(onDismissRequest=dismiss, title={Text("电脑备份")}, text={
        Column(Modifier.width(470.dp).heightIn(max=420.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("当前资料库：$name")
            Text("保存这份资料库里的 TA、聊天、草稿、笔记、记忆和本地附件。其他资料库需切换后分别导出。",fontSize=13.sp)
            if(phoneArchive) Text("也会包含从手机导入的完整原始备份，其中可能有日记和小秘密。",fontSize=13.sp)
            Text("备份是未加密的 ZIP，请妥善保管。API Key 不导出；恢复后需要重新确认 HTTP 权限。",fontSize=13.sp)
            if(exporting) Row(verticalAlignment=Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp));Spacer(Modifier.width(12.dp));Text("正在打包并校验…",fontSize=13.sp)
            }
            saved?.let { Text("已保存并通过校验：\n$it",fontSize=13.sp) }
            HorizontalDivider()
            Text("换电脑时可恢复这份电脑备份，先预览，再建立独立资料库。当前还不能把电脑备份导回手机。",fontSize=12.sp)
            OutlinedButton(restore,enabled=!exporting,modifier=Modifier.fillMaxWidth()) {Text("恢复电脑备份")}
        }
    },confirmButton={TextButton(export,enabled=!exporting) {Text("选择位置并导出")}},
        dismissButton={if(exporting) TextButton(cancel) {Text("取消导出")} else TextButton(dismiss) {Text("关闭")}})
}

@Composable internal fun DesktopRestoreDialog(p:DesktopBackupPreview, importing:Boolean, duplicate:Boolean, dismiss:()->Unit, confirm:(String)->Unit) {
    var name by remember(p.sha256) {mutableStateOf(p.libraryName)}
    val day=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(p.exportedAt).atZone(ZoneId.systemDefault()))
    AlertDialog(onDismissRequest={if(!importing)dismiss()},title={Text(if(duplicate) "这份备份已经恢复过" else "预览电脑备份")},text={
        Column(Modifier.width(470.dp).heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("备份时间：$day",fontSize=12.sp)
            Text(p.counts.entries.joinToString(" · ") {"${it.value} ${it.key}"},fontSize=13.sp)
            if(!duplicate) OutlinedTextField(name,{name=it},label={Text("新的资料库名称")},singleLine=true,enabled=!importing,modifier=Modifier.fillMaxWidth())
            Text(if(duplicate) "将打开此前恢复的资料库，保留后来新增的聊天。" else "恢复到独立资料库，当前资料保留；不会合并或覆盖聊天。",fontSize=13.sp)
            Text("API Key 不在备份内，HTTP 权限需重新确认。恢复不会自动调用模型。",fontSize=12.sp)
            if(p.hasPhoneArchive) Text("包含完整手机原始归档，可能含日记和小秘密；未适配的内容继续保存在归档里。",fontSize=12.sp)
            if(p.missingMedia.isNotEmpty()) Text("${p.missingMedia.size} 个引用附件在备份前就已缺失，将保留缺失提示。",fontSize=12.sp)
            if(importing) Text("正在校验并恢复，请稍候…",fontSize=12.sp)
        }
    },confirmButton={TextButton({confirm(name)},enabled=!importing && name.isNotBlank()) {Text(if(duplicate) "打开已有资料库" else "确认恢复")}},
        dismissButton={TextButton(dismiss,enabled=!importing) {Text("取消")}})
}
