package com.cleo.cleos.desktop

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.sun.net.httpserver.HttpServer
import org.jetbrains.skia.Image
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalTestApi::class)
class ComposerVoiceUiTest {
    @get:Rule val temp=TemporaryFolder()
    private fun DesktopComposeUiTest.snapshot(name:String) {
        val dir=Path.of("build/reports/desktop-ui");Files.createDirectories(dir);val roots=onAllNodes(isRoot())
        Image.makeFromBitmap(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asSkiaBitmap()).use {Files.write(dir.resolve("$name.png"),it.encodeToData()!!.bytes)}
    }
    @Test fun `Enter sends committed Chinese Shift Enter makes newline and Ctrl Enter and numpad still send`() = runDesktopComposeUiTest(width=1000,height=850) {
        val requests=AtomicInteger();val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/"){e->e.requestBody.readAllBytes();requests.incrementAndGet();val response="""{"choices":[{"message":{"content":"合成回答"},"finish_reason":"stop"}]}""".toByteArray();e.sendResponseHeaders(200,response.size.toLong());e.responseBody.use {it.write(response)}};server.start()
        val vm=runOnUiThread {DesktopController(LocalStore(temp.newFolder().toPath()),MemoryVault())}
        try {
            runOnUiThread {vm.configure(vm.data.companions.first().copy(endpoint=Endpoint("http://127.0.0.1:${server.address.port}/v1","test",true)),"fixture",false)}
            setContent {DesktopApp(vm)};waitForIdle()
            val input=onNodeWithContentDescription("输入消息")
            input.performClick().performKeyInput {pressKey(Key.Enter)};waitForIdle();assertEquals(0,requests.get())
            input.performTextInput("中文第一行");input.performKeyInput {keyDown(Key.ShiftLeft);pressKey(Key.Enter);keyUp(Key.ShiftLeft)};input.performTextInput("第二行")
            runOnIdle {assertEquals("中文第一行\n第二行",vm.selected.draft);assertTrue(vm.selected.messages.isEmpty())}
            input.performKeyInput {pressKey(Key.Enter)}
            awaitExternal {requests.get()==1 && vm.busyThread==null}
            assertEquals("中文第一行\n第二行",vm.selected.messages.first().text);assertEquals("",vm.selected.draft)
            input.performTextInput("Ctrl 回车");input.performKeyInput {keyDown(Key.CtrlLeft);pressKey(Key.Enter);keyUp(Key.CtrlLeft)}
            awaitExternal {requests.get()==2 && vm.busyThread==null}
            input.performTextInput("小键盘回车");input.performKeyInput {pressKey(Key.NumPadEnter)}
            awaitExternal {requests.get()==3 && vm.busyThread==null}
            onNodeWithText("Enter 发送  ·  Shift + Enter 换行").assertIsDisplayed();snapshot("desktop-enter-send")
        }finally {runOnUiThread {vm.close()};server.stop(0)}
    }
    @Test fun `IME confirmation and key repeat cannot become a send until a fresh Enter press`() {
        val gate=ComposerEnterGate()
        assertEquals(EnterAction.Pass,gate.press(true,false,false,false,false))
        assertEquals(EnterAction.Consume,gate.press(false,false,false,false,false))
        gate.release();assertEquals(EnterAction.Send,gate.press(false,false,false,false,false))
        assertEquals(EnterAction.Consume,gate.press(false,false,false,false,false))
        gate.release();assertEquals(EnterAction.Pass,gate.press(false,true,false,false,false))
        gate.release();assertEquals(EnterAction.Consume,gate.press(false,false,false,false,true))
        assertEquals(EnterAction.Consume,gate.press(false,false,false,false,false))
    }
    @Test fun `record stop transcribe edit and remove controls use synthetic microphone without automatic upload`() = runDesktopComposeUiTest(width=820,height=850) {
        val count=AtomicInteger();val release=CountDownLatch(1);var transcriptions=0
        val vm=runOnUiThread {DesktopController(LocalStore(temp.newFolder().toPath()),MemoryVault()).also {controller->
            controller.recorder.close();controller.recorder=VoiceRecorder {object:Microphone {
                override fun open(){}
                override fun read(bytes:ByteArray):Int {if(count.incrementAndGet()<=10){bytes.fill(1);return bytes.size};release.await(5,TimeUnit.SECONDS);return -1}
                override fun close(){release.countDown()}
            }}
            controller.transcriber=VoiceTranscriber {_,_,_->transcriptions++;"合成语音转写，可编辑"}
            controller.configureVoice(TranscriptionSettings("https://example.invalid/v1","test"),"synthetic",false)
        }}
        try {
            setContent {DesktopApp(vm)};waitForIdle();assertEquals(0,count.get())
            onNodeWithText("录音",substring=false).performClick();awaitExternal {count.get()>10}
            assertEquals(0,transcriptions);onNodeWithText("结束录音").performClick()
            awaitExternal {vm.selected.draftVoice!=null};assertEquals(0,transcriptions)
            onNodeWithText("试听录音").assertExists()
            onNodeWithText("转写录音",substring=false).performClick();awaitExternal {vm.selected.draftVoice?.transcript!=null}
            assertEquals(1,transcriptions);onNodeWithContentDescription("语音转写文字").performTextReplacement("人工核对后的合成文字")
            snapshot("desktop-voice-draft")
            assertTrue(vm.selected.messages.isEmpty())
            onNodeWithText("移除录音").performClick();waitForIdle();assertNull(vm.selected.draftVoice)
        }finally {runOnUiThread {vm.close()}}
    }
    @Test fun `voice settings show isolated key and explicit HTTP confirmation in a small page`() = runDesktopComposeUiTest(width=780,height=600) {
        val vm=runOnUiThread {DesktopController(LocalStore(temp.newFolder().toPath()),MemoryVault())}
        try {
            setContent {DesktopApp(vm)};waitForIdle()
            onNodeWithContentDescription("设置导航").performClick()
            onNodeWithContentDescription("语音转文字").performScrollTo().performClick()
            onNodeWithContentDescription("转写接口地址").performTextReplacement("http://127.0.0.1:65500/v1")
            onNodeWithContentDescription("转写模型名").performTextReplacement("synthetic-asr")
            onNodeWithContentDescription("转写 API Key").performTextReplacement("synthetic-voice-key")
            onNodeWithContentDescription("允许转写 HTTP").performScrollTo().performClick()
            onNodeWithText("允许明文传输？").assertIsDisplayed()
            onNodeWithText("了解风险，允许").performClick();onNodeWithText("保存语音配置").performClick();waitForIdle()
            assertTrue(vm.data.transcription.allowHttp);assertTrue(vm.hasVoiceKey());assertFalse(vm.hasKey(vm.data.companions.first()))
        }finally {runOnUiThread {vm.close()}}
    }
    @Test fun `minimum window keeps composer and send accessible with voice and image drafts`() = runDesktopComposeUiTest(width=780,height=600) {
        val root=temp.newFolder().toPath();val voice=syntheticVoice(root).copy(transcript="最小窗口的合成转写")
        val image=addLocalImage(root,syntheticImage(temp.newFolder().toPath().resolve("image.png")))
        val data=DesktopData.fresh().let {d->d.copy(conversations=d.conversations.map {it.copy(draftVoice=voice,draftImages=listOf(image),draft="最小窗口草稿")})}
        LocalStore(root).use {it.save(data)}
        val vm=runOnUiThread {DesktopController(LocalStore(root),MemoryVault())}
        try {
            setContent {DesktopApp(vm)};waitForIdle()
            onNodeWithContentDescription("输入消息").assertIsDisplayed()
            onNodeWithText("发送  ↑").assertIsDisplayed();onNodeWithText("Enter 发送  ·  Shift + Enter 换行").assertIsDisplayed()
            onNodeWithContentDescription("语音转写文字").performScrollTo().assertIsDisplayed();snapshot("desktop-voice-minimum")
        }finally{runOnUiThread {vm.close()}}
    }
}
