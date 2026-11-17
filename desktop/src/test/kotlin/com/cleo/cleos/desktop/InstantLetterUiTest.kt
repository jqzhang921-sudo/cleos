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

@OptIn(ExperimentalTestApi::class)
class InstantLetterUiTest {
    @get:Rule val temp=TemporaryFolder()
    private fun imported():Path {
        val p=MobileBackupImporter().inspect(instantFixture(temp.newFolder().toPath()))
        return temp.newFolder().toPath().also {MobileBackupImporter().write(p,it)}
    }
    private fun DesktopComposeUiTest.snapshot(name:String) {
        val dir=Path.of("build/reports/desktop-ui");Files.createDirectories(dir)
        val roots=onAllNodes(isRoot())
        Image.makeFromBitmap(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asSkiaBitmap()).use {Files.write(dir.resolve("$name.png"),it.encodeToData()!!.bytes)}
    }
    @Test fun `bitmap material preserves card bounds and real reader interaction`() = runDesktopComposeUiTest(width=780,height=600) {
        val p=MobileBackupImporter().inspect(instantFixture(temp.newFolder().toPath()))
        val letter=p.data.mobileLetters.first{it.id==AI_LETTER}
        val theme=androidx.compose.runtime.mutableStateOf(letter.envelope)
        setContent {androidx.compose.material3.MaterialTheme {MobileLetterCard(letter.copy(envelope=theme.value))}}
        waitForIdle()
        val card=onNodeWithContentDescription("拆开手机信件 $AI_LETTER")
        val before=card.fetchSemanticsNode().boundsInRoot
        runOnIdle {theme.value=letter.envelope.copy(id="custom-material",decoration="frost-fiber-v1")}
        waitForIdle()
        assertEquals(before,card.fetchSemanticsNode().boundsInRoot)
        snapshot("desktop-material-envelope")
        card.performClick();waitForIdle()
        onNodeWithText(INSTANT_BODY).assertIsDisplayed()
        onNodeWithContentDescription("合上手机信件").performClick()
        onAllNodes(isDialog()).assertCountEquals(0)
    }
    @Test fun `chat envelope opens closes and retains draft while mailbox and favorite use the same frozen letter`() = runDesktopComposeUiTest(width=1320,height=850) {
        val w=runOnUiThread {DesktopWorkspace(imported(),MemoryVault())}
        try {
            setContent {WorkspaceApp(w){null}};waitForIdle()
            runOnIdle {w.controller!!.select("mobile-private-11")};waitForIdle()
            onNodeWithContentDescription("输入消息").performTextReplacement("拆信之前保留的合成草稿")
            onNodeWithText(INSTANT_BODY).assertDoesNotExist()
            onNodeWithContentDescription("拆开手机信件 $AI_LETTER").performScrollTo().performClick();waitForIdle()
            onNodeWithText(INSTANT_BODY).assertIsDisplayed();snapshot("desktop-instant-open")
            onNodeWithContentDescription("合上手机信件").performClick();waitForIdle()
            onAllNodes(isDialog()).assertCountEquals(0)
            runOnIdle {assertEquals("拆信之前保留的合成草稿",w.controller!!.selected.draft)}
            onNodeWithContentDescription("信件导航").performClick()
            awaitExternal {onAllNodesWithText("即时信件",substring=false).fetchSemanticsNodes().isNotEmpty()}
            onNodeWithText("即时信件",substring=false).performClick()
            onNodeWithText(INSTANT_TITLE,substring=false).performClick();waitForIdle()
            onNodeWithText(INSTANT_BODY).assertIsDisplayed();snapshot("desktop-instant-mailbox")
            onNodeWithContentDescription("收藏导航").performClick()
            awaitExternal {onAllNodesWithText(INSTANT_TITLE,substring=false).fetchSemanticsNodes().isNotEmpty()}
            onNodeWithText(INSTANT_TITLE,substring=false).performClick();waitForIdle()
            onNodeWithContentDescription("拆开手机信件 $AI_LETTER").performScrollTo().performClick();waitForIdle()
            onNodeWithText(INSTANT_BODY).assertIsDisplayed();snapshot("desktop-instant-favorite")
        } finally {runOnIdle {w.close()}}
    }
    @Test fun `small window envelope reader keeps body and close control accessible`() = runDesktopComposeUiTest(width=780,height=600) {
        val p=MobileBackupImporter().inspect(instantFixture(temp.newFolder().toPath()))
        val l=p.data.mobileLetters.first{it.id==AI_LETTER}
        setContent {androidx.compose.material3.MaterialTheme {MobileLetterCard(l)}};waitForIdle()
        onNodeWithContentDescription("拆开手机信件 $AI_LETTER").performClick();waitForIdle()
        onNodeWithText(INSTANT_BODY).assertIsDisplayed();onNodeWithContentDescription("合上手机信件").assertIsDisplayed()
        snapshot("desktop-instant-small")
        onNodeWithContentDescription("合上手机信件").performClick();onAllNodes(isDialog()).assertCountEquals(0)
    }
}
