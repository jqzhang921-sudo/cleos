package com.cleo.cleos.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.EventQueue
import java.awt.Frame
import java.nio.file.Path
import javax.swing.JOptionPane
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val DESKTOP_VERSION = "0.13.0-preview.1"

/** Leave Compose's frame dispatcher before entering AWT's nested modal event loop. */
internal suspend fun chooseMobileBackup(owner: Frame): Path? = suspendCancellableCoroutine { continuation ->
    EventQueue.invokeLater {
        if (!continuation.isActive) return@invokeLater
        val chooser = FileDialog(owner, "选择 Cleos ZIP 备份", FileDialog.LOAD)
        continuation.invokeOnCancellation { EventQueue.invokeLater { chooser.dispose() } }
        try {
            chooser.filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".zip", true) }
            chooser.isVisible = true
            val path = chooser.file?.let { Path.of(chooser.directory, it) }
            if (continuation.isActive) continuation.resume(path)
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resumeWithException(e)
        } finally { chooser.dispose() }
    }
}

internal suspend fun chooseDesktopBackupDestination(owner: Frame): Path? = suspendCancellableCoroutine { continuation ->
    EventQueue.invokeLater {
        if (!continuation.isActive) return@invokeLater
        val chooser=FileDialog(owner,"保存 Cleos 电脑备份（请选择新文件名）",FileDialog.SAVE)
        continuation.invokeOnCancellation { EventQueue.invokeLater { chooser.dispose() } }
        try {
            val day=java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            chooser.file="Cleos-Desktop-$day.zip";chooser.isVisible=true
            val path=chooser.file?.let { name -> Path.of(chooser.directory,if(name.endsWith(".zip",true)) name else "$name.zip") }
            if(continuation.isActive) continuation.resume(path)
        } catch(e:Exception) {if(continuation.isActive)continuation.resumeWithException(e)}
        finally {chooser.dispose()}
    }
}

internal suspend fun chooseDesktopImages(owner:Frame):List<Path> = suspendCancellableCoroutine {continuation ->
    EventQueue.invokeLater {
        if(!continuation.isActive)return@invokeLater
        val chooser=FileDialog(owner,"选择图片（每条消息最多 4 张）",FileDialog.LOAD)
        continuation.invokeOnCancellation {EventQueue.invokeLater {chooser.dispose()}}
        try {
            chooser.isMultipleMode=true
            chooser.filenameFilter=java.io.FilenameFilter {_,name->name.substringAfterLast('.').lowercase() in setOf("png","jpg","jpeg")}
            chooser.isVisible=true
            if(continuation.isActive)continuation.resume(chooser.files.map {it.toPath()})
        } catch(e:Exception) {if(continuation.isActive)continuation.resumeWithException(e)}
        finally {chooser.dispose()}
    }
}

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.name", "Cleos")
    val path = args.indexOf("--data-dir").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        ?: System.getenv("CLEOS_DESKTOP_DATA_DIR")
        ?: Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Cleos", "Desktop").toString()
    val workspace = try { DesktopWorkspace(Path.of(path)) }
    catch (e: Exception) { JOptionPane.showMessageDialog(null, e.message ?: "Cleos 暂时无法打开资料库。", "Cleos", JOptionPane.ERROR_MESSAGE); return }
    application {
        val state = rememberWindowState(width = 1320.dp, height = 850.dp)
        Window(onCloseRequest = { workspace.close(); exitApplication() }, title = "Cleos · 电脑版预览", state = state) {
            window.minimumSize = Dimension(780, 600)
            WorkspaceApp(workspace,chooseSaveBackup={chooseDesktopBackupDestination(window)},chooseImages={chooseDesktopImages(window)},clipboard=::readClipboardTransfer) { chooseMobileBackup(window) }
        }
    }
}
