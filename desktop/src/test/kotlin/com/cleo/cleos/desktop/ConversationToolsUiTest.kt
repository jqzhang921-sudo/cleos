package com.cleo.cleos.desktop

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import org.jetbrains.skia.Image
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class ConversationToolsUiTest {
    @get:Rule val temp=TemporaryFolder()
    private fun DesktopComposeUiTest.snapshot(name:String) {
        val dir=Path.of("build/reports/desktop-ui");Files.createDirectories(dir)
        val roots=onAllNodes(isRoot())
        Image.makeFromBitmap(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asSkiaBitmap()).use {Files.write(dir.resolve("$name.png"),it.encodeToData()!!.bytes)}
    }
    @Test fun `search and recent attachment locate old messages without leaking into another conversation`() = runDesktopComposeUiTest(width=1320,height=850) {
        val root=temp.newFolder().toPath();Files.createDirectories(root.resolve("media"));syntheticImage(root.resolve("media/fixture.png"))
        val ta=DesktopTa(name="合成朋友",persona="合成角色设定",notes="合成的用户笔记")
        val second=DesktopTa(name="另一位合成朋友")
        val messages=(0..85).map {i->Message(id="m$i",speakerId=if(i%2==0)Message.USER else ta.id,text=if(i==20)"needle 合成的旧消息" else "合成消息 $i",reasoning=if(i==30)"仅思考里的隐藏词" else "",attachments=if(i==20)listOf(Attachment("media/fixture.png"))else emptyList())}
        val chat=Conversation(memberIds=listOf(ta.id),messages=messages)
        val group=Conversation(title="另一段合成群聊",memberIds=listOf(ta.id,second.id),group=true,messages=listOf(Message(text="群里独立的历史")))
        val data=DesktopData(companions=listOf(ta,second),conversations=listOf(chat,group),selectedId=chat.id)
        LocalStore(root).use {it.save(data)}
        val vm=runOnUiThread {DesktopController(LocalStore(root),MemoryVault())}
        try {
            setContent {DesktopApp(vm)};waitForIdle()
            onNodeWithTag("消息-m85").assertIsDisplayed()
            onNodeWithText("合成角色设定").assertDoesNotExist()
            onNodeWithContentDescription("常用功能").performClick();waitForIdle()
            onNodeWithContentDescription("搜索当前聊天记录").performTextInput("needle")
            mainClock.advanceTimeBy(200)
            awaitExternal {onAllNodesWithTag("搜索结果-m20").fetchSemanticsNodes().isNotEmpty()}
            onNodeWithTag("搜索结果-m20").performClick();waitForIdle()
            onNodeWithTag("消息-m20").assertIsDisplayed();snapshot("desktop-tools-search")
            onNodeWithText("清除").performClick();waitForIdle()
            onNodeWithText("↓  回到最新消息").performClick();waitForIdle();onNodeWithTag("消息-m85").assertIsDisplayed()
            onNodeWithTag("附件定位-m20-media/fixture.png").performClick();waitForIdle();onNodeWithTag("消息-m20").assertIsDisplayed()
            onNodeWithContentDescription("日记导航").performClick();waitForIdle()
            onAllNodes(isDialog()).assertCountEquals(0)
            onNodeWithContentDescription("输入消息").assertDoesNotExist()
            onNodeWithContentDescription("聊天导航").performClick();waitForIdle()
            onNodeWithTag("消息-m20").assertIsDisplayed()
            onNodeWithTag("消息-m85").assertDoesNotExist()
            snapshot("desktop-tools-wide")
            onNodeWithContentDescription("查看对话资料").performClick();waitForIdle();onNodeWithText("合成角色设定").assertIsDisplayed();snapshot("desktop-details-dialog")
            onNodeWithText("关闭资料").performClick();waitForIdle()
            onNodeWithContentDescription("搜索当前聊天记录").performTextInput("needle")
            mainClock.advanceTimeBy(200)
            awaitExternal {onAllNodesWithTag("搜索结果-m20").fetchSemanticsNodes().isNotEmpty()}
            onNodeWithText("另一段合成群聊").performClick();waitForIdle()
            onNodeWithContentDescription("搜索当前聊天记录").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
            onNodeWithTag("搜索结果-m20").assertDoesNotExist();onNodeWithTag("附件定位-m20-media/fixture.png").assertDoesNotExist()
            onNodeWithTag("消息-${group.messages.single().id}").assertIsDisplayed()
            runOnIdle {assertEquals(data.copy(selectedId=group.id),vm.data)}
        }finally {runOnIdle {vm.close()}}
    }
    @Test fun `narrow tools and navigation open separate windows and todo opens the correct category`() = runDesktopComposeUiTest(width=820,height=700) {
        val archive=notebookFixture(temp.newFolder().toPath());val preview=MobileBackupImporter().inspect(archive)
        val root=temp.newFolder().toPath();MobileBackupImporter().write(preview,root)
        val w=runOnUiThread {DesktopWorkspace(root,MemoryVault())}
        try {
            setContent {WorkspaceApp(w){null}};waitForIdle()
            onNodeWithContentDescription("搜索当前聊天记录").assertDoesNotExist()
            onNodeWithContentDescription("常用功能").performClick();waitForIdle()
            onNodeWithContentDescription("搜索当前聊天记录").assertIsDisplayed();snapshot("desktop-tools-narrow")
            onNodeWithText("关闭常用").performClick();waitForIdle()
            onNodeWithContentDescription("待办导航").performClick()
            awaitExternal {onAllNodesWithText("合成待办：买铅笔").fetchSemanticsNodes().isNotEmpty()}
            onAllNodes(isDialog()).assertCountEquals(0)
            onNodeWithText("合成日记：散步之后").assertDoesNotExist()
            onNodeWithText("返回聊天").performClick();waitForIdle()
            onNodeWithContentDescription("输入消息").assertIsDisplayed()
            onNodeWithContentDescription("搜索当前聊天记录").assertDoesNotExist()
        }finally {runOnIdle {w.close()}}
    }
}
