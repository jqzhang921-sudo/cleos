package com.cleo.cleos.desktop

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import org.jetbrains.skia.Image
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class DesktopMediaUiTest {
    @get:Rule val temp=TemporaryFolder()
    private fun DesktopComposeUiTest.snapshot(name:String) {
        val dir=Path.of("build/reports/desktop-ui");Files.createDirectories(dir)
        val roots=onAllNodes(isRoot())
        Image.makeFromBitmap(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asSkiaBitmap()).use {Files.write(dir.resolve("$name.png"),it.encodeToData()!!.bytes)}
    }
    @Test fun `attach preview text model guard remove and vision setting work in narrow window`() = runDesktopComposeUiTest(width=820,height=850) {
        val source=syntheticImage(temp.newFolder().toPath().resolve("image.png"))
        val w=runOnUiThread {DesktopWorkspace(temp.newFolder().toPath(),MemoryVault())}
        try {
            setContent {WorkspaceApp(w,chooseImages={listOf(source)}) {null}};waitForIdle()
            onNodeWithText("＋ 图片").performClick()
            awaitExternal {w.controller!!.selected.draftImages.size==1}
            awaitExternal {onAllNodesWithContentDescription("待发送图片").fetchSemanticsNodes().isNotEmpty()}
            onNodeWithText("发送  ↑").performClick();awaitExternal {w.controller!!.notice.contains("看图")}
            onNodeWithText("配置 TA 的模型").performClick();waitForIdle()
            onNodeWithContentDescription("模型支持看图").performScrollTo().performClick()
            onNodeWithText("保存配置").performClick();waitForIdle()
            runOnIdle {assertTrue(w.controller!!.data.companions.first().endpoint.supportsImages)}
            snapshot("desktop-image-draft")
            onNodeWithText("移除图片").performClick();waitForIdle()
            runOnIdle {assertTrue(w.controller!!.selected.draftImages.isEmpty())}
            onNodeWithContentDescription("待发送图片").assertDoesNotExist()
            assertTrue(Files.exists(source))
        }finally{runOnIdle {w.close()}}
    }
    @Test fun `local audio controls pause resume and stop without opening microphone or speakers`() = runDesktopComposeUiTest(width=500,height=300) {
        val root=temp.newFolder().toPath();Files.createDirectories(root.resolve("media"));Files.write(root.resolve("media/fake.wav"),byteArrayOf(1))
        val player=runOnUiThread {AudioPlayback(decode={object:PcmInput {
            override val format=PcmFormat(16000,1,16)
            override fun next()=ByteArray(3200)
            override fun close() {}
        }},output={object:PcmOutput {
            @Volatile var closed=false;override fun open(format:PcmFormat){}
            override fun write(bytes:ByteArray,offset:Int,length:Int):Int{Thread.sleep(20);return if(closed)0 else length}
            override fun pause(paused:Boolean){}
            override fun finish(){}
            override val frames get()=0L
            override fun close(){closed=true}
        }})}
        try {
            setContent {MaterialTheme {CompositionLocalProvider(LocalAudioPlayback provides player) {AttachmentView(root,Attachment("media/fake.wav","audio"))}}};waitForIdle()
            onNodeWithText("播放录音").performClick();awaitExternal {player.state.playing}
            onNodeWithText("暂停录音").performClick();waitForIdle();assertTrue(player.state.paused)
            snapshot("desktop-audio-paused")
            onNodeWithText("继续播放").performClick();waitForIdle();assertFalse(player.state.paused)
            onNodeWithText("停止").performClick();waitForIdle();assertFalse(player.state.playing)
        }finally{runOnIdle {player.close()}}
    }
}
