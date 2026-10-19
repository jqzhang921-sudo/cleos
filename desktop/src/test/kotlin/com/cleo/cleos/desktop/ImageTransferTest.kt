package com.cleo.cleos.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.awt.image.ImageObserver
import java.awt.image.ImageProducer
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

internal class SyntheticTransfer(private val values:Map<DataFlavor,Any>):Transferable {
    val reads=mutableListOf<DataFlavor>()
    override fun getTransferDataFlavors()=values.keys.toTypedArray()
    override fun isDataFlavorSupported(flavor:DataFlavor)=flavor in values
    override fun getTransferData(flavor:DataFlavor):Any {
        reads+=flavor
        return values[flavor] ?:throw UnsupportedFlavorException(flavor)
    }
}
internal fun syntheticPixels()=BufferedImage(48,32,BufferedImage.TYPE_INT_ARGB)
internal fun pixelTransfer()=SyntheticTransfer(mapOf(DataFlavor.imageFlavor to syntheticPixels()))

@OptIn(ExperimentalComposeUiApi::class)
class ImageTransferTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `flavor inspection reads no payload and local files take precedence over pixels`() {
        val source=syntheticImage(temp.newFolder().toPath().resolve("original.png"))
        val digest=fileDigest(source)
        val transfer=SyntheticTransfer(mapOf(DataFlavor.javaFileListFlavor to listOf(source.toFile()),DataFlavor.imageFlavor to syntheticPixels()))
        assertTrue(supportsImageOffer(transfer));assertTrue(transfer.reads.isEmpty())
        assertEquals(ImageOffer.Files(listOf(source)),readImageOffer(transfer))
        assertEquals(listOf(DataFlavor.javaFileListFlavor),transfer.reads)
        assertEquals(digest,fileDigest(source))
    }
    @Test fun `text URLs and unknown flavors are left untouched`() {
        val flavor=DataFlavor("application/x-cleos-test;class=java.lang.String")
        for(value in listOf("ordinary text","https://example.invalid/picture.png","file:///C:/picture.png")) {
            val transfer=SyntheticTransfer(mapOf(DataFlavor.stringFlavor to value,flavor to value))
            assertFalse(supportsImageOffer(transfer));assertNull(readImageOffer(transfer));assertTrue(transfer.reads.isEmpty())
        }
    }
    @Test fun `mixed invalid oversized relative and network file offers are rejected`() {
        val file=syntheticImage(temp.newFolder().toPath().resolve("image.png")).toFile()
        val invalid=listOf<Any>(emptyList<File>(),List(5){file},listOf(file,"https://example.invalid/a.png"),listOf(File("relative.png")),listOf(File("\\\\server\\share\\picture.png")),listOf(File("C:\\image.webp")),"not a list")
        for(value in invalid)assertThrows(TextFailure::class.java) {readImageOffer(SyntheticTransfer(mapOf(DataFlavor.javaFileListFlavor to value)))}
        assertThrows(TextFailure::class.java) {readImageOffer(SyntheticTransfer(mapOf(DataFlavor.imageFlavor to "not pixels")))}
    }
    @Test fun `clipboard pixels normalize to white bounded JPEG without mutating the source`() {
        val source=BufferedImage(2000,1000,BufferedImage.TYPE_INT_ARGB)
        val transfer=SyntheticTransfer(mapOf(DataFlavor.imageFlavor to source))
        val offer=readImageOffer(transfer) as ImageOffer.Pixels
        val decoded=ImageIO.read(ByteArrayInputStream(normalizedImage(offer.image)))
        assertEquals(1600,decoded.width);assertEquals(800,decoded.height)
        assertEquals(0xffffff,decoded.getRGB(1,1) and 0xffffff)
        assertEquals(0,source.getRGB(1,1));assertEquals(listOf(DataFlavor.imageFlavor),transfer.reads)
    }
    @Test fun `unready and oversized images are rejected before requesting pixels`() {
        for(dimensions in listOf(-1 to -1,0 to 30,100000 to 100000)) {
            val fake=object:Image() {
                override fun getWidth(observer:ImageObserver?)=dimensions.first
                override fun getHeight(observer:ImageObserver?)=dimensions.second
                override fun getSource():ImageProducer=error("must not decode")
                override fun getGraphics():java.awt.Graphics=error("must not draw")
                override fun getProperty(name:String?,observer:ImageObserver?):Any=UndefinedProperty
            }
            assertThrows(TextFailure::class.java) {normalizedImage(fake)}
        }
    }
    @Test fun `copy drop succeeds while move link disabled and malformed drop never claim success`() {
        val transfer=pixelTransfer();val offers=mutableListOf<ImageOffer>();val problems=mutableListOf<String>();val hints=mutableListOf<String?>()
        var enabled=true
        var current:Transferable=transfer
        val receiver=ImageDropReceiver({enabled},{offers+=it},{problems+=it},{hints+=it},{current})
        fun event(action:DragAndDropTransferAction?)=DragAndDropEvent(action,null,Offset.Zero)
        assertTrue(receiver.accepts(event(DragAndDropTransferAction.Copy)));assertTrue(transfer.reads.isEmpty())
        receiver.onEntered(event(DragAndDropTransferAction.Copy));assertTrue(hints.last()!!.contains("不会自动发送"))
        receiver.onExited(event(DragAndDropTransferAction.Copy));assertNull(hints.last())
        for(action in listOf(DragAndDropTransferAction.Move,DragAndDropTransferAction.Link,null)) {
            assertFalse(receiver.onDrop(event(action)));assertTrue(transfer.reads.isEmpty())
        }
        assertTrue(receiver.onDrop(event(DragAndDropTransferAction.Copy)));assertEquals(1,offers.size)
        assertEquals(1,transfer.reads.size);assertNull(hints.last())
        enabled=false
        assertFalse(receiver.accepts(event(DragAndDropTransferAction.Copy)))
        assertFalse(receiver.onDrop(event(DragAndDropTransferAction.Copy)));assertEquals(1,transfer.reads.size)
        enabled=true;current=SyntheticTransfer(mapOf(DataFlavor.javaFileListFlavor to listOf(File("bad.txt"))))
        assertFalse(receiver.onDrop(event(DragAndDropTransferAction.Copy)));assertEquals(1,offers.size);assertTrue(problems.isNotEmpty())
        receiver.onEnded(event(DragAndDropTransferAction.Copy));assertNull(hints.last())
    }
    @Test fun `pasted pixels remain an unsent per conversation draft through restart and backup restore`() = runBlocking(Dispatchers.Swing) {
        val root=temp.newFolder().toPath();val vm=DesktopController(LocalStore(root),MemoryVault())
        val original:DesktopData
        try {
            vm.draft("typed before pasting")
            vm.attachOffer(readImageOffer(pixelTransfer())!!)
            assertEquals("typed before pasting",vm.selected.draft);assertTrue(vm.selected.messages.isEmpty())
            assertEquals(1,vm.selected.draftImages.size)
            assertTrue(prompt(vm.data,vm.selected,vm.data.companions.first()).all {it.images.isEmpty()})
            original=vm.data
        }finally {vm.close()}
        LocalStore(root).use {assertEquals(original,it.load())}
        val file=temp.newFolder().toPath().resolve("pasted.zip");val archive=DesktopBackupArchive()
        archive.export(root,original,"test",file)
        val target=temp.newFolder().toPath();archive.write(archive.inspect(file),target)
        LocalStore(target).use {assertEquals(original,it.load());assertNotNull(localMedia(target,it.load().conversations.first().draftImages.single().file))}
    }
    @Test fun `stale conversation and fifth image are refused without creating extra files`() = runBlocking(Dispatchers.Swing) {
        val root=temp.newFolder().toPath();val vm=DesktopController(LocalStore(root),MemoryVault())
        try {
            val first=vm.selected.id;vm.addCompanion("second")
            vm.attachOffer(ImageOffer.Pixels(syntheticPixels()),first)
            assertTrue(vm.data.conversations.all {it.draftImages.isEmpty()});assertFalse(Files.exists(root.resolve("media")))
            repeat(4) {vm.attachOffer(ImageOffer.Pixels(syntheticPixels()))}
            val before=Files.list(root.resolve("media")).use {it.count()}
            vm.attachOffer(ImageOffer.Pixels(syntheticPixels()))
            assertEquals(4,vm.selected.draftImages.size);assertEquals(before,Files.list(root.resolve("media")).use {it.count()})
            assertTrue(vm.notice.contains("4"));assertTrue(vm.selected.messages.isEmpty())
        }finally {vm.close()}
    }
}
