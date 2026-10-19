package com.cleo.cleos.desktop

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.skia.Image
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

@OptIn(ExperimentalTestApi::class)
class PhoneKeepsUiTest {
    @get:Rule val temp=TemporaryFolder()
    private fun imported():Path {
        val preview=MobileBackupImporter().inspect(keepsFixture(temp.newFolder().toPath()))
        return temp.newFolder().toPath().also {MobileBackupImporter().write(preview,it)}
    }
    private fun DesktopComposeUiTest.snapshot(name:String) {
        val dir=Path.of("build/reports/desktop-ui");Files.createDirectories(dir)
        val roots=onAllNodes(isRoot())
        Image.makeFromBitmap(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asSkiaBitmap()).use {Files.write(dir.resolve("$name.png"),it.encodeToData()!!.bytes)}
    }
    @Test fun `letters favorites are primary pages with filters media and retained drafts and detail`() = runDesktopComposeUiTest(width=1320,height=850) {
        val root=imported()
        // Keep this generated synthetic library for a separate packaged-EXE smoke check.
        val native=Path.of("build/reports/desktop-ui/keeps-native");Files.createDirectories(native)
        Files.walk(root).use {paths->paths.forEach {p->val target=native.resolve(root.relativize(p).toString());if(Files.isDirectory(p))Files.createDirectories(target)else Files.copy(p,target,REPLACE_EXISTING)}}
        val w=runOnUiThread {DesktopWorkspace(root,MemoryVault())}
        try {
            setContent {WorkspaceApp(w){null}};waitForIdle()
            onNodeWithContentDescription("输入消息").performTextReplacement("信件浏览后的合成草稿")
            onNodeWithContentDescription("信件导航").performClick()
            awaitExternal {onAllNodesWithText("合成来信：窗外的晚风",substring=true).fetchSemanticsNodes().isNotEmpty()}
            onAllNodes(isDialog()).assertCountEquals(0);onNodeWithContentDescription("输入消息").assertDoesNotExist()
            onNodeWithText("绝不能提前显示的来信").assertDoesNotExist()
            onNodeWithText("收到的",substring=false).performClick()
            onNodeWithText("合成草稿：尚未寄出",substring=true).assertDoesNotExist()
            onNodeWithText("合成来信：窗外的晚风",substring=false).performClick()
            onNodeWithText("合成来信：窗外的晚风\n合成来信全文。",substring=false).assertExists()
            snapshot("desktop-keeps-letter")
            onNodeWithContentDescription("收藏导航").performClick();waitForIdle()
            onAllNodes(isDialog()).assertCountEquals(0)
            onNodeWithContentDescription("收藏 TA 筛选").performClick()
            onNodeWithContentDescription("筛选 TA 7").performClick();waitForIdle()
            onAllNodesWithText("合成收藏：书架旁的话",substring=false).assertCountEquals(1)
            onNodeWithText("合成收藏：书架旁的话",substring=false).performClick();waitForIdle()
            onNodeWithText("合成用户",substring=true).assertExists();onNodeWithText("历史署名",substring=true).assertExists()
            onNodeWithText("播放录音",substring=false).assertExists()
            awaitExternal {onAllNodesWithContentDescription("合成图片来源").fetchSemanticsNodes().isNotEmpty()}
            onNodeWithText("CC BY 4.0",substring=true).assertExists()
            snapshot("desktop-keeps-favorite")
            onNodeWithContentDescription("信件导航").performClick();waitForIdle()
            onNodeWithText("合成来信：窗外的晚风\n合成来信全文。",substring=false).assertExists()
            onNodeWithContentDescription("收藏导航").performClick();waitForIdle()
            onNodeWithText("合成收藏：独立保存的回答",substring=false).assertExists()
            onNodeWithContentDescription("聊天导航").performClick();waitForIdle()
            runOnIdle {assertEquals("信件浏览后的合成草稿",w.controller!!.selected.draft)}
        }finally {runOnIdle {w.close()}}
    }
    @Test fun `letter search categories and TA filter exclude other entries`() = runDesktopComposeUiTest(width=900,height=850) {
        val w=runOnUiThread {DesktopWorkspace(imported(),MemoryVault())}
        try {
            setContent {WorkspaceApp(w){null}};waitForIdle();onNodeWithContentDescription("信件导航").performClick()
            awaitExternal {onAllNodesWithText("合成来信：窗外的晚风",substring=true).fetchSemanticsNodes().isNotEmpty()}
            onNodeWithContentDescription("信件 TA 筛选").performClick();onNodeWithContentDescription("筛选 TA 8").performClick()
            onNodeWithText("合成草稿：尚未寄出",substring=true).assertDoesNotExist()
            onAllNodesWithText("另一位 TA 的合成来信",substring=true).assertCountEquals(1)
            onNodeWithContentDescription("信件 TA 筛选").performClick();onNodeWithContentDescription("筛选全部 TA").performClick()
            onNodeWithText("草稿",substring=false).performClick();waitForIdle()
            onNodeWithText("合成草稿：尚未寄出",substring=false).assertExists()
            onNodeWithText("合成来信：窗外的晚风",substring=true).assertDoesNotExist()
            onNodeWithContentDescription("搜索信件").performTextReplacement("不会匹配的合成查询")
            onNodeWithText("当前分类没有匹配的信件。").assertExists()
            onNodeWithContentDescription("搜索信件").performTextReplacement("")
            snapshot("desktop-keeps-draft")
        }finally {runOnIdle {w.close()}}
    }
    @Test fun `minimum height navigation scrolls while settings and personal controls remain accessible`() = runDesktopComposeUiTest(width=780,height=600) {
        val w=runOnUiThread {DesktopWorkspace(imported(),MemoryVault())}
        try {
            setContent {WorkspaceApp(w){null}};waitForIdle()
            onNodeWithContentDescription("收藏导航").performScrollTo().performClick()
            awaitExternal {onAllNodesWithText("合成收藏：书架旁的话",substring=true).fetchSemanticsNodes().isNotEmpty()}
            onNodeWithContentDescription("设置导航").assertIsDisplayed();onNodeWithContentDescription("个人菜单").assertIsDisplayed()
            snapshot("desktop-keeps-minimum")
            onNodeWithContentDescription("搜索收藏").performTextReplacement("想留下")
            onNodeWithContentDescription("设置导航").performClick();onNodeWithText("数据与备份").assertIsDisplayed()
            onNodeWithContentDescription("收藏导航").performScrollTo().performClick();waitForIdle()
            onNodeWithContentDescription("搜索收藏").assertTextContains("想留下")
            onNodeWithText("返回聊天").performClick();onNodeWithContentDescription("输入消息").assertExists()
        }finally {runOnIdle {w.close()}}
    }
}
