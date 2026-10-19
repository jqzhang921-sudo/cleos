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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Path
import java.awt.datatransfer.Transferable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.cleo.cleos.transfer.ReceivedBackup

@Composable fun WorkspaceApp(workspace: DesktopWorkspace, chooseSaveBackup: suspend () -> Path? = { null },chooseImages:suspend ()->List<Path> = {emptyList()},clipboard:(()->Transferable?)?=null, chooseBackup: suspend () -> Path?) {
    val scope = rememberCoroutineScope()
    var selecting by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var receiving by remember { mutableStateOf(false) }
    var incoming by remember { mutableStateOf<ReceivedBackup?>(null) }
    var computerBackup by remember { mutableStateOf(false) }
    var desktopPreview by remember { mutableStateOf<DesktopBackupPreview?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var savedBackup by remember { mutableStateOf<Path?>(null) }
    DisposableEffect(workspace) { onDispose { incoming?.close() } }
    fun inspect(path:Path) {
        scope.launch {
            preparing=true
            try { preview=withContext(Dispatchers.IO) { MobileBackupImporter().inspect(path) } }
            catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){error=(e as? ImportFailure)?.message?:"这份备份暂时无法读取，已有资料未更改。";incoming?.close();incoming=null}
            finally { preparing=false }
        }
    }
    val controller = workspace.controller ?: return
    key(workspace.selected) {
        DesktopApp(controller, workspace.libraryName, {
            if (workspace.canSwitch && !preparing && !importing) selecting = true
        }, {
            if (workspace.canSwitch && !preparing && !importing) scope.launch {
                val path = chooseBackup() ?: return@launch
                inspect(path)
            }
        }, { if(workspace.canSwitch && !preparing && !importing) receiving=true }, {
            if(workspace.canSwitch && !preparing && !importing) { computerBackup=true;savedBackup=null }
        },chooseImages=chooseImages,clipboard=clipboard)
    }
    MaterialTheme {
        if (preparing) AlertDialog(onDismissRequest = {}, title = { Text("正在检查备份") }, text = { Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(14.dp)); Text("检查格式、记录与附件列表…")
        } }, confirmButton = {})
        preview?.let { p ->
            BackupPreviewDialog(p, importing, workspace.libraries.any { it.sourceSha256 == p.sha256 }, { preview = null;incoming?.close();incoming=null }) { name ->
                importing = true
                scope.launch {
                    try { if (workspace.importBackup(p, name)) {preview = null;incoming?.close();incoming=null} else error = workspace.problem }
                    finally { importing = false }
                }
            }
        }
        if (computerBackup) DesktopBackupDialog(workspace.libraryName, controller.data.importOrigin != null, exporting, savedBackup,
            dismiss = { if (!exporting) computerBackup = false }, cancel = { exportJob?.cancel() },
            export = {
                exporting=true;savedBackup=null
                exportJob = scope.launch {
                    try {
                        val destination = chooseSaveBackup() ?: return@launch
                        val result = workspace.exportBackup(destination)
                        if (result == null) error = workspace.problem else savedBackup = result.source
                    } catch (e:kotlinx.coroutines.CancellationException) { throw e }
                    catch (_:Exception) { error="保存窗口暂时无法打开，请稍后重试；原资料未更改。" }
                    finally { exporting=false;exportJob=null }
                }
            }, restore = {
                computerBackup=false;preparing=true
                scope.launch {
                    try {
                        val source = chooseBackup() ?: return@launch
                        val context=currentCoroutineContext()
                        desktopPreview=withContext(Dispatchers.IO) { DesktopBackupArchive().inspect(source) { context.ensureActive() } }
                    } catch(e:kotlinx.coroutines.CancellationException) { throw e }
                    catch(e:Exception) { error=(e as? ImportFailure)?.message?:"这份电脑备份暂时无法读取，原资料未更改。" }
                    finally { preparing=false }
                }
            })
        desktopPreview?.let { p ->
            DesktopRestoreDialog(p, importing, workspace.libraries.any { it.sourceSha256 == p.sha256 }, { desktopPreview=null }) { name ->
                importing=true
                scope.launch {
                    try { if(workspace.restoreBackup(p,name)) desktopPreview=null else error=workspace.problem }
                    finally { importing=false }
                }
            }
        }
        if(receiving) ReceivePhoneDialog(workspace.root.resolve("transfers").toFile(),{receiving=false},received={ lease ->
            incoming?.close();incoming=lease;receiving=false;inspect(lease.file.toPath())
        })
        if (selecting) AlertDialog(onDismissRequest = { selecting = false }, title = { Text("切换资料库") }, text = {
            Column(Modifier.width(400.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                workspace.libraries.forEach { library -> TextButton({
                    if (workspace.switch(library.id)) selecting = false else error = workspace.problem
                }, Modifier.fillMaxWidth()) { Text((if (workspace.selected == library.id) "当前 · " else "") + library.name, Modifier.fillMaxWidth()) } }
                Text("各资料库分开保存；切换不会覆盖另一份聊天。", fontSize = 12.sp)
            }
        }, confirmButton = { TextButton({ selecting = false }) { Text("关闭") } })
        if (error.isNotBlank()) AlertDialog(onDismissRequest = { error = "" }, title = { Text("这次没有完成") }, text = { Text(error) }, confirmButton = { TextButton({ error = "" }) { Text("知道了") } })
    }
}

@Composable internal fun BackupPreviewDialog(preview: BackupPreview, importing: Boolean, duplicate: Boolean, dismiss: () -> Unit, confirm: (String) -> Unit) {
    val day = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(preview.exportedAt).atZone(ZoneId.systemDefault()))
    var name by remember(preview.sha256) { mutableStateOf("手机资料 · ${day.take(10)}") }
    AlertDialog(onDismissRequest = { if (!importing) dismiss() }, title = { Text(if (duplicate) "这份备份已经导入过" else "预览手机备份") }, text = {
        Column(Modifier.width(470.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("备份时间：$day", fontSize = 12.sp)
            Text(preview.counts.filterValues { it > 0 }.entries.joinToString(" · ") { "${it.value} ${it.key}" }, fontSize = 14.sp, lineHeight = 24.sp)
            if (!duplicate) OutlinedTextField(name, { name = it }, label = { Text("新的资料库名称") }, singleLine = true, enabled = !importing, modifier = Modifier.fillMaxWidth())
            Text(if (duplicate) "将打开之前导入的资料库，保留在电脑上继续聊过的内容，不会重复导入。" else "导入为单独的资料库，原来的电脑聊天保留。导入后不会自动请求模型。", fontSize = 13.sp)
            HorizontalDivider()
            preview.warnings.forEach { Text(it, fontSize = 12.sp, lineHeight = 19.sp) }
            if (importing) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("正在复制并校验，完成前请稍候。", fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton({ confirm(name) }, enabled = !importing && name.isNotBlank()) { Text(if (duplicate) "打开已有资料库" else "确认导入") } },
        dismissButton = { TextButton(dismiss, enabled = !importing) { Text("取消") } })
}
