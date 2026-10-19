package com.cleo.cleos.desktop

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class PhoneNotebookUiTest {
    @get:Rule val temp=TemporaryFolder()
    private fun DesktopComposeUiTest.snapshot(name:String) {
        val dir=Path.of("build/reports/desktop-ui");Files.createDirectories(dir)
        val roots=onAllNodes(isRoot())
        Image.makeFromBitmap(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asSkiaBitmap()).use {Files.write(dir.resolve("$name.png"),it.encodeToData()!!.bytes)}
    }
    @Test fun `notebook tabs preserve secret boundaries search todo status and return to chat`() = runDesktopComposeUiTest(width=1000,height=850) {
        val source=notebookFixture(temp.newFolder().toPath());val p=MobileBackupImporter().inspect(source)
        val root=temp.newFolder().toPath();MobileBackupImporter().write(p,root)
        val w=runOnUiThread {DesktopWorkspace(root,MemoryVault())}
        try {
            setContent {WorkspaceApp(w){null}};waitForIdle()
            onNodeWithContentDescription("输入消息").performTextReplacement("打开导航仍保留的合成草稿")
            onNodeWithContentDescription("日记导航").performClick()
            awaitExternal {onAllNodesWithText("合成日记：散步之后").fetchSemanticsNodes().isNotEmpty()}
            onAllNodes(isDialog()).assertCountEquals(0)
            onNodeWithContentDescription("输入消息").assertDoesNotExist()
            onNodeWithContentDescription("聊天导航").assertIsDisplayed()
            onNodeWithText("自己的合成秘密").assertDoesNotExist()
            onNodeWithText("合成日记：散步之后").performClick();waitForIdle()
            onNodeWithText("合成正文：图片前的一段").assertExists();onNodeWithText("合成正文：图片后的一段").assertExists()
            snapshot("desktop-notebook-diary")
            onNodeWithText("小秘密").performClick();waitForIdle()
            onNodeWithText("不可泄露的标题").assertDoesNotExist();onNodeWithText("不可泄露的正文").assertDoesNotExist()
            onNodeWithText("TA 的小秘密").performClick();waitForIdle()
            onNodeWithText("仅愿意分享的摘录").assertExists();onNodeWithText("不可泄露的正文").assertDoesNotExist()
            snapshot("desktop-notebook-secret")
            onNodeWithContentDescription("待办导航").performClick();waitForIdle()
            onAllNodes(isDialog()).assertCountEquals(0)
            onNodeWithContentDescription("待办导航").assertIsSelected()
            onNodeWithText("合成待办：买铅笔").assertExists()
            onNodeWithText("未完成",substring=false).performClick();waitForIdle()
            onNodeWithText("合成待办：买铅笔").assertDoesNotExist()
            onNodeWithText("合成待办：整理书架").performClick();waitForIdle()
            onNodeWithText("合成备注：把书按颜色排列").assertExists();snapshot("desktop-notebook-todo")
            onNodeWithContentDescription("日记导航").performClick();waitForIdle()
            onNodeWithText("仅愿意分享的摘录").assertExists()
            onNodeWithText("不可泄露的正文").assertDoesNotExist()
            onNodeWithContentDescription("待办导航").performClick();waitForIdle()
            onNodeWithText("合成备注：把书按颜色排列").assertExists()
            onNodeWithText("返回聊天").performClick();waitForIdle()
            onNodeWithContentDescription("输入消息").assertExists()
            onNodeWithContentDescription("聊天导航").assertIsSelected()
            runOnIdle {assertEquals("打开导航仍保留的合成草稿",w.controller!!.selected.draft)}
        }finally {runOnIdle {w.close()}}
    }
    @Test fun `local library navigation fits minimum window and preserves chat while opening notebook and settings`() = runDesktopComposeUiTest(width=780,height=600) {
        val w=runOnUiThread {DesktopWorkspace(temp.newFolder().toPath(),MemoryVault())}
        try {
            setContent {WorkspaceApp(w){null}};waitForIdle()
            val conversation=w.controller!!.selected.id
            onNodeWithContentDescription("输入消息").performTextInput("最小窗口的合成草稿")
            onNodeWithContentDescription("待办导航").assertIsDisplayed();onNodeWithContentDescription("设置导航").assertIsDisplayed();onNodeWithContentDescription("个人菜单").assertIsDisplayed()
            onNodeWithContentDescription("日记导航").performClick()
            awaitExternal {onAllNodesWithText("这份资料库还没有手机日记与待办。",substring=true).fetchSemanticsNodes().isNotEmpty()}
            snapshot("desktop-notebook-empty")
            onNodeWithText("返回聊天").performClick();waitForIdle();onNodeWithContentDescription("输入消息").assertExists()
            onNodeWithContentDescription("设置导航").performClick();waitForIdle();onNodeWithText("数据与备份").assertIsDisplayed()
            onAllNodes(isDialog()).assertCountEquals(0)
            snapshot("desktop-settings-page-minimum")
            onNodeWithContentDescription("聊天导航").performClick();waitForIdle()
            onNodeWithText("配置 TA 的模型").assertIsDisplayed()
            onNodeWithContentDescription("聊天导航").assertIsSelected();snapshot("desktop-navigation-minimum")
            runOnIdle {assertEquals(conversation,w.controller!!.selected.id);assertEquals("最小窗口的合成草稿",w.controller!!.selected.draft)}
        }finally {runOnIdle {w.close()}}
    }
}
