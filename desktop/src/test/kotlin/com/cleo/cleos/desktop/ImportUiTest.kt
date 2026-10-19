package com.cleo.cleos.desktop

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Image
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class ImportUiTest {
    @get:Rule val temp=TemporaryFolder()
    private suspend fun DesktopComposeUiTest.awaitText(text:String) { awaitExternal { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun DesktopComposeUiTest.snapshot(name:String) {
        val directory=Path.of("build/reports/desktop-ui");Files.createDirectories(directory)
        val roots=onAllNodes(isRoot())
        Image.makeFromBitmap(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asSkiaBitmap()).use { Files.write(directory.resolve("$name.png"),it.encodeToData()!!.bytes) }
    }
    @Test fun `cancel import preview never changes the original library`() = runDesktopComposeUiTest(width=1320,height=850) {
        val root=temp.newFolder().toPath();val fixture=mobileFixture(temp.newFolder().toPath())
        val w=runOnUiThread { DesktopWorkspace(root,MemoryVault()) }
        try {
            setContent { WorkspaceApp(w) { fixture } };waitForIdle()
            onNodeWithContentDescription("输入消息").performTextInput("原来电脑上的草稿")
            onNodeWithContentDescription("个人菜单").performClick();awaitExternal {onAllNodesWithContentDescription("个人菜单设置").fetchSemanticsNodes().isNotEmpty()};snapshot("desktop-personal-menu")
            onNodeWithContentDescription("个人菜单设置").performClick();awaitText("数据与备份");snapshot("desktop-settings-wide")
            onNodeWithContentDescription("导入手机备份").performClick();awaitText("预览手机备份")
            onNodeWithText("关闭设置").assertDoesNotExist()
            onNodeWithText("确认导入").assertIsDisplayed();snapshot("mobile-backup-preview")
            onNodeWithText("取消").performClick();waitForIdle()
            runOnIdle { assertEquals(1,w.libraries.size);assertEquals("local",w.selected);assertEquals("原来电脑上的草稿",w.controller!!.selected.draft) }
            assertFalse(Files.exists(root.resolve("libraries")))
        } finally { runOnIdle { w.close() } }
    }
    @Test fun `confirm import shows readonly history images memory and switch back preserves drafts`() = runDesktopComposeUiTest(width=1320,height=850) {
        val fixture=rewriteBackup(mobileFixture(temp.newFolder().toPath()),temp.newFolder().toPath().resolve("image-last.zip")) { parts ->
            changeDocument(parts) { JsonObject(it+("messages" to JsonArray(it["messages"]!!.jsonArray.take(1)))) }
        }
        val w=runOnUiThread { DesktopWorkspace(temp.newFolder().toPath(),MemoryVault()) }
        try {
            setContent { WorkspaceApp(w) { fixture } };waitForIdle()
            onNodeWithContentDescription("输入消息").performTextInput("切回电脑还能看到")
            openDesktopSettings();onNodeWithContentDescription("导入手机备份").performClick();awaitText("预览手机备份")
            onNodeWithText("确认导入").performClick()
            awaitExternal { w.libraries.size==2 }
            onNodeWithText("只读的旧群").performClick();awaitText("只读历史 · 已离开的成员不会重新加入")
            onNodeWithContentDescription("输入消息").assertDoesNotExist()
            onNodeWithText("从手机来的私聊").performClick();waitForIdle()
            onNodeWithContentDescription("查看对话资料").performClick();waitForIdle()
            onNodeWithText("手机记忆 1 · 设定 1").performClick();awaitText("手机保留的记录")
            onNodeWithText("只属于甲的私人记忆",substring=true).assertIsDisplayed()
            onNodeWithText("关闭记录").performClick();waitForIdle()
            onNodeWithText("关闭资料").performClick();waitForIdle()
            awaitExternal { onAllNodesWithContentDescription("用户上传").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("用户上传").performClick();awaitText("关闭图片")
            onNodeWithContentDescription("图片大图").assertIsDisplayed()
            onNodeWithText("关闭图片").performClick();waitForIdle();snapshot("imported-private-image")
            onNodeWithContentDescription("个人菜单").performClick();awaitText("切换资料库")
            onNodeWithContentDescription("切换资料库").performClick();awaitText("各资料库分开保存；切换不会覆盖另一份聊天。")
            onNodeWithText("我的电脑资料库").performClick();waitForIdle()
            runOnIdle { assertEquals("local",w.selected);assertEquals("切回电脑还能看到",w.controller!!.selected.draft) }
        } finally { runOnIdle { w.close() } }
    }
    @Test fun `invalid backup leaves a dismissible error with composer intact`() = runDesktopComposeUiTest(width=820,height=850) {
        val broken=temp.newFolder().toPath().resolve("broken.zip");Files.writeString(broken,"invalid archive")
        val w=runOnUiThread { DesktopWorkspace(temp.newFolder().toPath(),MemoryVault()) }
        try {
            setContent { WorkspaceApp(w) { broken } };waitForIdle()
            openDesktopSettings();onNodeWithContentDescription("导入手机备份").performClick();awaitText("这次没有完成")
            onNodeWithText("知道了").performClick();waitForIdle()
            onNodeWithContentDescription("聊天导航").performClick();waitForIdle()
            onNodeWithContentDescription("输入消息").assertIsDisplayed()
            runOnIdle { assertEquals(1,w.libraries.size);assertEquals("local",w.selected) }
        } finally { runOnIdle { w.close() } }
    }
}
