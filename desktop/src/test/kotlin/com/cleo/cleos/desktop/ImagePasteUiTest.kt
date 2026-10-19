package com.cleo.cleos.desktop

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.datatransfer.Transferable
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class ImagePasteUiTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `explicit paste and composer Ctrl V use injected image once and preserve typed draft`() = runDesktopComposeUiTest(width=820,height=850) {
        val w=runOnUiThread {DesktopWorkspace(temp.newFolder().toPath(),MemoryVault())}
        var reads=0
        val source=pixelTransfer()
        try {
            setContent {WorkspaceApp(w,clipboard={reads++;source}) {null}};waitForIdle()
            assertEquals(0,reads)
            onNodeWithContentDescription("输入消息").performTextInput("保留这段草稿")
            onNodeWithText("粘贴图片").performClick()
            awaitExternal {w.controller!!.selected.draftImages.size==1}
            onNodeWithContentDescription("输入消息").performClick().performKeyInput {keyDown(Key.CtrlLeft);pressKey(Key.V);keyUp(Key.CtrlLeft)}
            awaitExternal {w.controller!!.selected.draftImages.size==2}
            awaitExternal {onAllNodesWithContentDescription("待发送图片").fetchSemanticsNodes().size==2}
            assertEquals(2,reads)
            runOnIdle {assertEquals("保留这段草稿",w.controller!!.selected.draft);assertTrue(w.controller!!.selected.messages.isEmpty())}
            val dir=Path.of("build/reports/desktop-ui");Files.createDirectories(dir)
            val roots=onAllNodes(isRoot())
            Image.makeFromBitmap(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asSkiaBitmap()).use {Files.write(dir.resolve("desktop-pasted-images.png"),it.encodeToData()!!.bytes)}
            onAllNodesWithText("移除图片")[0].performClick();waitForIdle()
            assertEquals(1,w.controller!!.selected.draftImages.size)
            assertEquals(2,reads)
        }finally {runOnIdle {w.close()}}
    }
    @Test fun `explicit empty and unavailable clipboard show controlled feedback without adding messages`() = runDesktopComposeUiTest(width=820,height=850) {
        val w=runOnUiThread {DesktopWorkspace(temp.newFolder().toPath(),MemoryVault())}
        var provider:()->Transferable?={null}
        try {
            setContent {WorkspaceApp(w,clipboard={provider()}) {null}};waitForIdle()
            onNodeWithText("粘贴图片").performClick();waitForIdle()
            assertTrue(w.controller!!.notice.contains("没有图片"))
            provider={throw IllegalStateException("synthetic unavailable")}
            onNodeWithText("粘贴图片").performClick();waitForIdle()
            assertTrue(w.controller!!.notice.contains("暂时无法读取"))
            assertTrue(w.controller!!.selected.messages.isEmpty());assertTrue(w.controller!!.selected.draftImages.isEmpty())
        }finally {runOnIdle {w.close()}}
    }
}
