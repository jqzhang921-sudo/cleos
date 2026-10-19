package com.cleo.cleos.desktop

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Image
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class DesktopBackupUiTest {
    @get:Rule val temp=TemporaryFolder()
    private suspend fun DesktopComposeUiTest.awaitText(text:String) = awaitExternal {onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    private fun DesktopComposeUiTest.snapshot(name:String) {
        val directory=Path.of("build/reports/desktop-ui");Files.createDirectories(directory)
        val roots=onAllNodes(isRoot())
        Image.makeFromBitmap(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asSkiaBitmap()).use {Files.write(directory.resolve("$name.png"),it.encodeToData()!!.bytes)}
    }
    @Test fun `export button saves current draft and cancelled restore preview preserves original`() = runDesktopComposeUiTest(width=1320,height=850) {
        val out=temp.newFolder().toPath().resolve("desktop.zip")
        val w=runOnUiThread {DesktopWorkspace(temp.newFolder().toPath(),MemoryVault())}
        try {
            setContent {WorkspaceApp(w,chooseSaveBackup={out}) {out}};waitForIdle()
            onNodeWithContentDescription("输入消息").performTextInput("还未发送的电脑草稿")
            openDesktopSettings();onNodeWithContentDescription("电脑备份").performClick();awaitText("选择位置并导出")
            onNodeWithText("选择位置并导出").performClick()
            awaitExternal {onAllNodesWithText("已保存并通过校验：",substring=true).fetchSemanticsNodes().isNotEmpty()}
            assertEquals("还未发送的电脑草稿",DesktopBackupArchive().inspect(out).data.conversations.first().draft)
            snapshot("desktop-backup-saved")
            onNodeWithText("恢复电脑备份").performClick();awaitText("预览电脑备份")
            onNodeWithText("取消").performClick();waitForIdle()
            runOnIdle {assertEquals(1,w.libraries.size);assertEquals("local",w.selected);assertEquals("还未发送的电脑草稿",w.controller!!.selected.draft)}
        } finally {runOnIdle {w.close()}}
    }
    @Test fun `restore confirm imports separately and narrow dialog remains usable`() = runDesktopComposeUiTest(width=820,height=650) {
        val archive=DesktopBackupArchive();val file=temp.newFolder().toPath().resolve("desktop.zip")
        archive.export(temp.newFolder().toPath(),DesktopData.fresh().let {it.copy(conversations=it.conversations.map {c ->c.copy(draft="备份中的草稿")})},"另一台电脑",file)
        val w=runOnUiThread {DesktopWorkspace(temp.newFolder().toPath(),MemoryVault())}
        try {
            setContent {WorkspaceApp(w) {file}};waitForIdle()
            openDesktopSettings();snapshot("desktop-settings-narrow")
            onNodeWithContentDescription("电脑备份").performClick();awaitText("恢复电脑备份")
            onNodeWithText("恢复电脑备份").performClick();awaitText("预览电脑备份")
            onNodeWithText("确认恢复").assertIsDisplayed();snapshot("desktop-backup-restore-preview")
            onNodeWithText("确认恢复").performClick();awaitExternal {w.libraries.size==2}
            runOnIdle {assertEquals("另一台电脑",w.libraryName);assertEquals("备份中的草稿",w.controller!!.selected.draft)}
        } finally {runOnIdle {w.close()}}
    }
}
