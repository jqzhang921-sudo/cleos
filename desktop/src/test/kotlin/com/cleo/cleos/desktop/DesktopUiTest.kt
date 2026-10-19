package com.cleo.cleos.desktop

import androidx.compose.ui.test.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Image
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class DesktopUiTest {
    private lateinit var compose: DesktopComposeUiTest
    @get:Rule val temp = TemporaryFolder()
    private fun start(): DesktopController {
        val store=LocalStore(temp.newFolder().toPath())
        val a=DesktopTa(name="布局验收 TA",persona="这是一位合成测试角色。",notes="只包含合成数据。")
        val b=DesktopTa(name="另一位 TA",tone=1)
        val c=Conversation(memberIds=listOf(a.id),messages=listOf(Message(text="这是合成的布局验收消息。"),Message(speakerId=a.id,text="左边切换对话，中间聊天，右边查找消息和附件。\n\n在大一点的窗口里，继续慢慢聊。",reasoning="用于检查折叠区域的合成内容。")))
        val group=Conversation(title="合成测试群",memberIds=listOf(a.id,b.id),group=true)
        store.save(DesktopData(companions=listOf(a,b),conversations=listOf(c,group),selectedId=c.id))
        val vm=compose.runOnUiThread { DesktopController(store,MemoryVault()) }
        compose.setContent { DesktopApp(vm) };compose.waitForIdle();return vm
    }
    private fun snapshot(name:String) {
        val dir=Path.of(System.getProperty("cleos.qa.out","build/reports/desktop-ui"));Files.createDirectories(dir)
        val bitmap=compose.onRoot().captureToImage().asSkiaBitmap()
        val image=Image.makeFromBitmap(bitmap)
        image.use { Files.write(dir.resolve("$name.png"),it.encodeToData()!!.bytes) }
    }
    @Test fun `wide window shows three panes and drafts and notes persist`() = runDesktopComposeUiTest(width=1320,height=850) {
        compose=this
        val vm=start()
        try {
            compose.onNodeWithContentDescription("聊天导航").assertIsSelected()
            compose.onNodeWithContentDescription("搜索当前聊天记录").assertDoesNotExist()
            compose.onNodeWithText("角色与模型设置  ↗").assertDoesNotExist()
            compose.onNodeWithText("内部思考",substring=true).assertDoesNotExist()
            snapshot("desktop-navigation-wide")
            compose.onNodeWithContentDescription("常用功能").performClick()
            compose.onNodeWithText("常用").assertIsDisplayed()
            compose.onNodeWithContentDescription("输入消息").performTextInput("尚未发送的桌面草稿")
            compose.runOnIdle { assertEquals("尚未发送的桌面草稿",vm.selected.draft) }
            compose.onNodeWithContentDescription("查看对话资料").performClick()
            compose.onNodeWithText("角色与模型设置  ↗").assertIsDisplayed()
            compose.onNodeWithContentDescription("给 TA 的笔记").performTextReplacement("更新后的合成笔记")
            compose.onNodeWithText("保存笔记").performClick()
            compose.runOnIdle { assertEquals("更新后的合成笔记",vm.data.companions.first().notes) }
            compose.onNodeWithText("关闭资料").performClick()
            compose.onNodeWithText("收起常用 ›").performClick()
            compose.onNodeWithContentDescription("搜索当前聊天记录").assertDoesNotExist()
            compose.onNodeWithContentDescription("常用功能").performClick()
            compose.onNodeWithContentDescription("搜索当前聊天记录").assertIsDisplayed()
            compose.runOnIdle { assertEquals("尚未发送的桌面草稿",vm.selected.draft) }
        } finally { compose.runOnIdle { vm.close() } }
    }
    @Test fun `narrow window keeps composer visible and switching group selects its own history`() = runDesktopComposeUiTest(width=820,height=850) {
        compose=this
        val vm=start()
        try {
            compose.onNodeWithContentDescription("输入消息").assertIsDisplayed()
            compose.onNodeWithText("角色与模型设置  ↗").assertDoesNotExist()
            snapshot("desktop-navigation-narrow")
            compose.onNodeWithText("合成测试群").performClick()
            compose.onNodeWithText("2 位 TA · 轮流回复").assertIsDisplayed()
            compose.onNodeWithText("这是合成的布局验收消息。").assertDoesNotExist()
        } finally { compose.runOnIdle { vm.close() } }
    }
    @Test fun `reasoning is collapsed by default and opens only on request`() = runDesktopComposeUiTest(width=1320,height=850) {
        compose=this
        val vm=start()
        try {
            compose.onNodeWithText("用于检查折叠区域的合成内容。").assertDoesNotExist()
            compose.onNodeWithText("✦  思考  ⌄").performClick()
            compose.onNodeWithText("用于检查折叠区域的合成内容。").assertIsDisplayed()
            compose.onNodeWithText("✦  收起思考  ⌃").performClick()
            compose.onNodeWithText("用于检查折叠区域的合成内容。").assertDoesNotExist()
        } finally { compose.runOnIdle { vm.close() } }
    }
}
