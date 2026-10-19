package com.cleo.cleos.desktop

import com.cleo.cleos.transfer.*
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.*
import javax.net.ssl.*

class TransferIntegrationTest {
    @get:Rule val temp=TemporaryFolder()
    private val executor=Executors.newCachedThreadPool()
    private val servers=mutableListOf<TransferReceiver>()
    @After fun stop(){servers.forEach { it.close() };executor.shutdownNow()}
    private class Fixture(val cache:File,val receiver:TransferReceiver,val states:BlockingQueue<ReceiveState>,val received:CompletableFuture<ReceivedBackup>)
    private fun fixture(approve:Boolean?=true,limit:Long=MAX_TRANSFER_BYTES,expiry:Long=300000):Fixture {
        val certificate=TransferCertificate.create();val cache=File(temp.newFolder(),"incoming")
        val states=LinkedBlockingQueue<ReceiveState>();val received=CompletableFuture<ReceivedBackup>()
        lateinit var receiver:TransferReceiver
        receiver=TransferReceiver(certificate.context,InetAddress.getByName("127.0.0.1"),certificate.fingerprint,cache,pairingMillis=expiry,maxBytes=limit,onState={
            states.add(it);if(it.stage=="approval" && approve!=null){if(approve)receiver.approve()else receiver.reject()}
        },onComplete={received.complete(it)})
        servers+=receiver;return Fixture(cache,receiver,states,received)
    }
    private fun await(f:Fixture,stage:String):ReceiveState {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(System.nanoTime()<deadline){val state=f.states.poll(100,TimeUnit.MILLISECONDS)?:continue;if(state.stage==stage)return state}
        error("Did not observe transfer state $stage")
    }
    private fun assertClean(f:Fixture) { await(f,"failed");f.receiver.close();for(i in 0..100){if(f.cache.listFiles().isNullOrEmpty())return;Thread.sleep(10)};assertTrue(f.cache.listFiles().isNullOrEmpty()) }
    private fun raw(ticket:PairingTicket):SSLSocket {
        val trust=object:X509TrustManager {
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
            override fun checkClientTrusted(c:Array<out X509Certificate>?,a:String?)=error("unused")
            override fun checkServerTrusted(c:Array<out X509Certificate>?,a:String?) { assertEquals(ticket.fingerprint,MessageDigest.getInstance("SHA-256").digest(c!!.first().encoded).joinToString(""){"%02x".format(it)}) }
        }
        val context=SSLContext.getInstance("TLS").apply {init(null,arrayOf(trust),null)}
        return (context.socketFactory.createSocket("127.0.0.1",ticket.port) as SSLSocket).apply {enabledProtocols=arrayOf("TLSv1.3");soTimeout=5000;startHandshake()}
    }
    private fun auth(socket:SSLSocket,t:PairingTicket):Pair<DataInputStream,DataOutputStream> {
        val input=DataInputStream(socket.inputStream);val output=DataOutputStream(socket.outputStream)
        output.writeInt(0x434c5431);output.write(t.id.chunked(2).map{it.toInt(16).toByte()}.toByteArray());output.write(java.util.Base64.getUrlDecoder().decode(t.token));output.writeUTF("合成手机");output.writeByte(1);output.flush();assertEquals(1,input.readUnsignedByte())
        return input to output
    }
    @Test fun `QR pixels decode to the exact temporary ticket`() {
        val f=fixture();val text=f.receiver.ticket.encode();val matrix=pairingQr(text)
        val scale=6;val width=matrix.width*scale
        val pixels=IntArray(width*width){i->if(matrix[(i%width)/scale,(i/width)/scale])0xff000000.toInt() else 0xffffffff.toInt()}
        val decoded=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width,width,pixels)))).text
        assertEquals(text,decoded);assertEquals(f.receiver.ticket.verificationCode,PairingTicket.parse(decoded).verificationCode)
    }
    @Test fun `pinned TLS transfers real Android serializer ZIP and importer preserves it`() {
        val f=fixture();val file=mobileFixture(temp.newFolder().toPath()).toFile()
        TransferSender(f.receiver.ticket).use { it.requestApproval();it.send(file) }
        f.received.get(5,TimeUnit.SECONDS).use { lease ->
            assertArrayEquals(digestFile(file),digestFile(lease.file));assertTrue(file.exists())
            val preview=MobileBackupImporter().inspect(lease.file.toPath());assertEquals(2,preview.counts["TA"])
            MobileBackupImporter().write(preview,temp.newFolder().toPath())
        }
        assertTrue(f.cache.listFiles().isNullOrEmpty())
        assertThrows(Exception::class.java){TransferSender(f.receiver.ticket).use {it.requestApproval()}}
    }
    @Test fun `large backup crosses chunk boundaries with correct progress`() {
        val f=fixture();val file=File(temp.newFolder(),"synthetic.zip").apply {writeBytes(ByteArray(250123){(it%251).toByte()})}
        var sent=0L
        TransferSender(f.receiver.ticket).use {it.requestApproval();it.send(file){a,b->assertTrue(a<=b);sent=a}}
        f.received.get(5,TimeUnit.SECONDS).use {assertArrayEquals(file.readBytes(),it.file.readBytes())};assertEquals(file.length(),sent)
    }
    @Test fun `no archive or directory is created while waiting for human approval`() {
        val f=fixture(null);val sender=TransferSender(f.receiver.ticket)
        val task=executor.submit<Unit>{sender.requestApproval()}
        await(f,"approval");assertFalse(f.cache.exists());assertFalse(task.isDone)
        f.receiver.approve();task.get(3,TimeUnit.SECONDS);assertFalse(f.cache.exists());sender.close();assertClean(f)
    }
    @Test fun `decline sends no backup and consumes the ticket`() {
        val f=fixture(false)
        assertThrows(TransferException::class.java){TransferSender(f.receiver.ticket).use {it.requestApproval()}}
        await(f,"declined");assertFalse(f.cache.exists());assertFalse(f.received.isDone)
    }
    @Test fun `wrong certificate pin prevents device request`() {
        val f=fixture();val t=f.receiver.ticket
        val bad=PairingTicket(t.host,t.port,"00".repeat(32),t.id,t.token,t.name)
        assertThrows(SSLException::class.java){TransferSender(bad).use {it.requestApproval()}}
        assertTrue(f.states.isEmpty());assertFalse(f.cache.exists())
    }
    @Test fun `wrong token does not ask for approval or consume a valid QR`() {
        val f=fixture();val t=f.receiver.ticket
        val token=if(t.token[0]=='A')"B"+t.token.drop(1) else "A"+t.token.drop(1)
        val bad=PairingTicket(t.host,t.port,t.fingerprint,t.id,token,t.name)
        assertThrows(Exception::class.java){TransferSender(bad).use {it.requestApproval()}}
        assertTrue(f.states.isEmpty());assertFalse(f.cache.exists())
        val file=mobileFixture(temp.newFolder().toPath()).toFile()
        TransferSender(t).use {it.requestApproval();it.send(file)};f.received.get(5,TimeUnit.SECONDS).close()
    }
    @Test fun `expired QR closes its listener without creating files`() {
        val f=fixture(expiry=20);await(f,"expired")
        assertThrows(Exception::class.java){TransferSender(f.receiver.ticket).use {it.requestApproval()}}
        assertFalse(f.cache.exists())
    }
    @Test fun `cancel waiting approval unblocks the phone promptly`() {
        val f=fixture(null);val sender=TransferSender(f.receiver.ticket)
        val task=executor.submit<Boolean> {runCatching {sender.requestApproval()}.isFailure}
        await(f,"approval");f.receiver.close();assertTrue(task.get(3,TimeUnit.SECONDS));assertFalse(f.cache.exists());sender.close()
    }
    @Test fun `oversize metadata is rejected before any disk write`() {
        val f=fixture(limit=1024)
        raw(f.receiver.ticket).use { socket->val(input,output)=auth(socket,f.receiver.ticket);output.writeLong(2048);output.write(ByteArray(32));output.flush();assertThrows(Exception::class.java){input.readUnsignedByte()} }
        assertClean(f);assertFalse(f.received.isDone)
    }
    @Test fun `truncated payload cleans the partial file`() {
        val f=fixture()
        raw(f.receiver.ticket).use { socket->val(input,output)=auth(socket,f.receiver.ticket);output.writeLong(2000);output.write(ByteArray(32));output.flush();assertEquals(1,input.readUnsignedByte());output.writeInt(1000);output.write(ByteArray(50));output.flush() }
        assertClean(f);assertFalse(f.received.isDone)
    }
    @Test fun `wrong content digest is rejected and never reaches preview`() {
        val f=fixture()
        raw(f.receiver.ticket).use { socket->val(input,output)=auth(socket,f.receiver.ticket);output.writeLong(10);output.write(ByteArray(32));output.flush();assertEquals(1,input.readUnsignedByte());output.writeInt(10);output.write(ByteArray(10));output.writeInt(0);output.flush();assertThrows(Exception::class.java){input.readUnsignedByte()} }
        assertClean(f);assertFalse(f.received.isDone)
    }
    @Test fun `chunk cannot overrun declared length or buffer limit`() {
        for(count in listOf(-1,11,65537)) {
            val f=fixture()
            raw(f.receiver.ticket).use {socket->val(input,output)=auth(socket,f.receiver.ticket);output.writeLong(10);output.write(ByteArray(32));output.flush();assertEquals(1,input.readUnsignedByte());output.writeInt(count);output.flush();assertThrows(Exception::class.java){input.readUnsignedByte()} }
            assertClean(f);assertFalse(f.received.isDone)
        }
    }
}
