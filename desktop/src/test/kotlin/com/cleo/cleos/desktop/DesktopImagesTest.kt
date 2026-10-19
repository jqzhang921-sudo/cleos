package com.cleo.cleos.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

internal fun syntheticImage(path:Path,width:Int=32,height:Int=24):Path {
    val image=BufferedImage(width,height,BufferedImage.TYPE_INT_RGB)
    image.createGraphics().let {g->g.color=java.awt.Color.PINK;g.fillRect(0,0,width,height);g.dispose()}
    ImageIO.write(image,"png",path.toFile());return path
}
class DesktopImagesTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `images normalize to bounded JPEG and original is untouched`() {
        val source=syntheticImage(temp.newFolder().toPath().resolve("original.png"),2000,1000);val before=fileDigest(source)
        val root=temp.newFolder().toPath();val image=addLocalImage(root,source)
        assertEquals(before,fileDigest(source));assertEquals("image",image.kind)
        val bitmap=ImageIO.read(root.resolve(image.file).toFile());assertEquals(1600,bitmap.width);assertEquals(800,bitmap.height)
        assertTrue(Files.size(root.resolve(image.file))<4*1024*1024)
    }
    @Test fun `invalid nonimage overlarge and unsafe remote references are never opened`() {
        val bad=temp.newFile().toPath();Files.writeString(bad,"not a picture")
        assertThrows(TextFailure::class.java) {normalizedImage(bad)}
        val root=temp.newFolder().toPath()
        val m=WireMessage("user","看图",listOf("https://127.0.0.1/private.jpg","../private.png","file:///C:/private.jpg"))
        val output=prepareImages(listOf(m),root,true).single();assertTrue(output.images.isEmpty());assertTrue(output.content.contains("3 张图片未附入"))
    }
    @Test fun `image budget takes latest user pictures only and text mode stays text`() {
        val root=temp.newFolder().toPath();val a=addLocalImage(root,syntheticImage(temp.newFolder().toPath().resolve("a.png")))
        val messages=(1..12).map {WireMessage("user","$it",listOf(a.file))}+WireMessage("assistant","assistant",listOf(a.file))
        val prepared=prepareImages(messages,root,true)
        assertEquals(8,prepared.sumOf {it.images.size});assertTrue(prepared.take(4).all {it.images.isEmpty()});assertTrue(prepared.last().images.isEmpty())
        assertTrue(prepareImages(messages,root,false).all {it.images.isEmpty()})
    }
    @Test fun `draft images persist through restart export restore and do not enter prompt before send`() = runBlocking(Dispatchers.Swing) {
        val root=temp.newFolder().toPath();val image=syntheticImage(temp.newFolder().toPath().resolve("image.png"))
        val vm=DesktopController(LocalStore(root),MemoryVault())
        vm.attachImages(listOf(image));assertEquals(1,vm.selected.draftImages.size)
        assertTrue(prompt(vm.data,vm.selected,vm.data.companions.first()).all {it.images.isEmpty()})
        val original=vm.data;vm.close()
        val file=temp.newFolder().toPath().resolve("desktop.zip");val archive=DesktopBackupArchive()
        archive.export(root,original,"test",file);val preview=archive.inspect(file)
        val restored=temp.newFolder().toPath();archive.write(preview,restored)
        LocalStore(restored).use {assertEquals(original,it.load());assertNotNull(localMedia(restored,it.load().conversations.first().draftImages.single().file))}
    }
    @Test fun `only text models cannot send image only draft and batch failure leaves no orphan media`() = runBlocking(Dispatchers.Swing) {
        val root=temp.newFolder().toPath();val image=syntheticImage(temp.newFolder().toPath().resolve("image.png"))
        val vm=DesktopController(LocalStore(root),MemoryVault())
        try {
            val bad=temp.newFile().toPath();Files.writeString(bad,"broken")
            vm.attachImages(listOf(image,bad));assertTrue(vm.selected.draftImages.isEmpty())
            assertEquals(0,Files.list(root.resolve("media")).use {it.count()}.toInt())
            vm.attachImages(listOf(image));vm.send();assertEquals(1,vm.selected.draftImages.size);assertTrue(vm.selected.messages.isEmpty());assertTrue(vm.notice.contains("看图"))
            vm.removeDraftImage(vm.selected.draftImages.single().file);assertTrue(vm.selected.draftImages.isEmpty())
        } finally {vm.close()}
    }
    @Test fun `actual mixed group request sends image array to vision model and plain content to text model`() {
        val requests=java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/v1/chat/completions") {e ->
            requests+=Json.parseToJsonElement(e.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
            val bytes="""{"choices":[{"message":{"content":"合成回复"},"finish_reason":"stop"}]}""".toByteArray()
            e.responseHeaders.add("Content-Type","application/json");e.sendResponseHeaders(200,bytes.size.toLong());e.responseBody.use {it.write(bytes)}
        };server.start()
        try {runBlocking(Dispatchers.Swing) {
            val vm=DesktopController(LocalStore(temp.newFolder().toPath()),MemoryVault())
            try {
                val endpoint=Endpoint("http://127.0.0.1:${server.address.port}/v1","text",true)
                val a=vm.data.companions.first().copy(endpoint=endpoint);assertTrue(vm.configure(a,"fake",false))
                val b=vm.addCompanion("看图成员").copy(endpoint=endpoint.copy(model="vision",supportsImages=true));assertTrue(vm.configure(b,"fake",false))
                assertTrue(vm.createGroup("合成群",listOf(a.id,b.id)))
                vm.attachImages(listOf(syntheticImage(temp.newFolder().toPath().resolve("pink.png"))));vm.draft("这张图呢");vm.send()
                withTimeout(10000) {while(vm.busyThread!=null)delay(10)}
                assertTrue(vm.selected.draftImages.isEmpty());assertEquals(1,vm.selected.messages.first().attachments.size)
                val text=requests.first()["messages"]!!.jsonArray.map {it.jsonObject}.last()
                assertTrue(text["content"] is JsonPrimitive);assertTrue(text["content"]!!.jsonPrimitive.content.contains("未发送给模型"))
                val vision=requests.last()["messages"]!!.jsonArray.map {it.jsonObject}.first {it["content"] is JsonArray}
                val data=vision["content"]!!.jsonArray.last().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                assertTrue(data.startsWith("data:image/jpeg;base64,"))
                assertEquals(32,ImageIO.read(java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(data.substringAfter(',')))).width)
                assertTrue(vm.selected.messages.drop(1).all {it.status=="complete"})
            } finally {vm.close()}
        }}finally {server.stop(0)}
    }
    @Test fun `old schema migrates without inventing image permissions`() {
        val old=DesktopData.fresh().copy(version=2).checked()
        assertEquals(4,old.version);assertFalse(old.companions.first().endpoint.supportsImages);assertTrue(old.conversations.first().draftImages.isEmpty())
    }
}
