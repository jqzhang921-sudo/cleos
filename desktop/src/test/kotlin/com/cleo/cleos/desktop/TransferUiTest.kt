package com.cleo.cleos.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.graphics.asSkiaBitmap
import com.cleo.cleos.transfer.*
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.jetbrains.skia.Image
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class TransferUiTest {
    @get:Rule val temp=TemporaryFolder()
    private suspend fun DesktopComposeUiTest.awaitAddress() {
        try { awaitExternal{onAllNodesWithText("127.0.0.1",substring=true).fetchSemanticsNodes().isNotEmpty()} }
        catch(e:Throwable){val roots=onAllNodes(isRoot());println(roots[roots.fetchSemanticsNodes().lastIndex].printToString());throw e}
    }
    private suspend fun DesktopComposeUiTest.ticket():PairingTicket {
        awaitExternal{onAllNodes(hasText("生成临时二维码") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        onNodeWithText("生成临时二维码").performClick()
        try { awaitExternal{onAllNodesWithContentDescription("手机迁移配对二维码").fetchSemanticsNodes().isNotEmpty()} }
        catch(e:Throwable){val roots=onAllNodes(isRoot());println(roots[roots.fetchSemanticsNodes().lastIndex].printToString());throw e}
        val bytes=Image.makeFromBitmap(onNodeWithContentDescription("手机迁移配对二维码").captureToImage().asSkiaBitmap()).use {it.encodeToData()!!.bytes}
        val qr=ImageIO.read(ByteArrayInputStream(bytes));val pixels=qr.getRGB(0,0,qr.width,qr.height,null,0,qr.width)
        return PairingTicket.parse(QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width,qr.height,pixels)))).text)
    }
    @Test fun `scan visible QR approve on desktop and deliver only the verified archive`()=runDesktopComposeUiTest(width=1320,height=850) {
        val cache=temp.newFolder();val delivered=AtomicReference<ReceivedBackup?>();var show by mutableStateOf(true)
        val executor=Executors.newSingleThreadExecutor();var sender:TransferSender?=null
        try {
            setContent { if(show)ReceivePhoneDialog(cache,{show=false},{delivered.set(it);show=false},{listOf(LanAddress("127.0.0.1","合成局域网"))}) }
            awaitAddress()
            val ticket=ticket();val file=mobileFixture(temp.newFolder().toPath()).toFile();val client=TransferSender(ticket);sender=client
            val task=executor.submit<Unit>{client.requestApproval("界面验收手机");client.send(file)}
            awaitExternal{onAllNodesWithText("界面验收手机 请求发送整份 Cleos 备份").fetchSemanticsNodes().isNotEmpty()}
            onNodeWithText("请核对手机上的数字：${ticket.verificationCode}",substring=true).assertIsDisplayed()
            assertNull(delivered.get());onNodeWithText("同意接收").performClick()
            awaitExternal{delivered.get()!=null};task.get(5,TimeUnit.SECONDS)
            assertArrayEquals(digestFile(file),digestFile(delivered.get()!!.file))
            assertEquals(15,MobileBackupImporter().inspect(delivered.get()!!.file.toPath()).version)
        } finally { runOnIdle{show=false};sender?.close();delivered.get()?.close();executor.shutdownNow() }
    }
    @Test fun `close QR invalidates connection and leaves no archive`()=runDesktopComposeUiTest(width=1320,height=850) {
        val cache=temp.newFolder();var show by mutableStateOf(true)
        try {
            setContent { if(show)ReceivePhoneDialog(cache,{show=false},{it.close();error("must not receive")},{listOf(LanAddress("127.0.0.1","合成局域网"))}) }
            awaitAddress()
            val ticket=ticket()
            val output=Path.of("build/reports/desktop-ui");Files.createDirectories(output)
            val roots=onAllNodes(isRoot());Image.makeFromBitmap(roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asSkiaBitmap()).use {Files.write(output.resolve("phone-transfer-qr.png"),it.encodeToData()!!.bytes)}
            onNodeWithText("关闭并停止接收").performClick();waitForIdle()
            assertThrows(Exception::class.java){TransferSender(ticket).use {it.requestApproval()}}
            assertTrue(cache.listFiles().isNullOrEmpty())
        } finally {runOnIdle{show=false}}
    }
}
