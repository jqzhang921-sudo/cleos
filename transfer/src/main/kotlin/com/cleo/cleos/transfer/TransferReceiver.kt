package com.cleo.cleos.transfer

import java.io.*
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.*

data class ReceiveState(val stage:String, val phone:String="", val received:Long=0, val total:Long=0, val message:String="")

/** Owns only this session's new cache directory, never an existing backup or library. */
class ReceivedBackup internal constructor(val file:File,private val directory:File):AutoCloseable {
    override fun close() {
        check(file.parentFile.canonicalFile==directory.canonicalFile && directory.name.startsWith("incoming-"))
        file.delete();File(directory,"phone.zip.part").delete();directory.delete()
    }
}

/** Call only while owning the workspace lock, before starting any receiver. */
fun removeAbandonedReceipts(cache:File) {
    if(Files.isSymbolicLink(cache.toPath()))return
    cache.listFiles()?.forEach { directory ->
        if(Regex("incoming-[a-f0-9-]{36}").matches(directory.name) && directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            val files=directory.listFiles().orEmpty()
            if(files.all { it.name in setOf("phone.zip","phone.zip.part") && it.isFile && !Files.isSymbolicLink(it.toPath()) }) {
                files.forEach { it.delete() };directory.delete()
            }
        }
    }
}

/** One ephemeral TLS listener, one authenticated client, one explicit approval, one file. */
class TransferReceiver(
    context:SSLContext, bind:InetAddress, fingerprint:String, private val cache:File,
    name:String="Cleos 电脑", private val pairingMillis:Long=300000,
    private val maxBytes:Long=MAX_TRANSFER_BYTES,
    private val onState:(ReceiveState)->Unit={}, private val onComplete:(ReceivedBackup)->Unit,
):AutoCloseable {
    private val server=run {
        checkTransfer(PairingTicket.privateIpv4(bind.hostAddress.orEmpty()) && pairingMillis>0 && maxBytes in 1..MAX_TRANSFER_BYTES)
        (context.serverSocketFactory.createServerSocket(0,2,bind) as SSLServerSocket).apply { enabledProtocols=arrayOf("TLSv1.3");soTimeout=500 }
    }
    val ticket=PairingTicket.create(bind.hostAddress!!,server.localPort,fingerprint,name)
    private val closed=AtomicBoolean(false)
    private val claimed=AtomicBoolean(false)
    private val decision=CompletableFuture<Boolean>()
    private val deadline=AtomicLong(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(pairingMillis))
    @Volatile private var active:SSLSocket?=null
    private val timer=Executors.newSingleThreadScheduledExecutor { r->Thread(r,"cleos-transfer-expiry").apply { isDaemon=true } }
    private val worker=Executors.newSingleThreadExecutor { r->Thread(r,"cleos-transfer-receive").apply { isDaemon=true } }
    init {
        timer.scheduleAtFixedRate({ if(!closed.get() && System.nanoTime()>deadline.get()) { emit(ReceiveState("expired",message="配对已过期，请重新生成二维码。"));close() } },100,100,TimeUnit.MILLISECONDS)
        worker.execute { runSession() }
    }
    private fun emit(state:ReceiveState) { runCatching { onState(state) } }
    fun approve() { decision.complete(true) }
    fun reject() { decision.complete(false) }
    private fun runSession() {
        var directory:File?=null
        try {
            var failures=0
            while(!closed.get() && !claimed.get()) {
                val socket=try { server.accept() as SSLSocket } catch(_:SocketTimeoutException){continue}
                active=socket
                try {
                    socket.soTimeout=7000;socket.startHandshake()
                    val input=DataInputStream(socket.inputStream.buffered());val output=DataOutputStream(socket.outputStream.buffered())
                    val id=ByteArray(16);val token=ByteArray(32)
                    checkTransfer(input.readInt()==TRANSFER_MAGIC);input.readFully(id);input.readFully(token)
                    checkTransfer(MessageDigest.isEqual(id,unhex(ticket.id)) && MessageDigest.isEqual(token,ticket.tokenBytes()))
                    checkTransfer(System.nanoTime()<=deadline.get() && claimed.compareAndSet(false,true))
                    server.close() // The QR can no longer accept a second connection.
                    val phone=input.readUTF();checkTransfer(phone.length in 1..60 && phone.none { it.isISOControl() })
                    checkTransfer(input.readUnsignedByte()==FULL_BACKUP)
                    emit(ReceiveState("approval",phone))
                    val allowed=decision.get(180,TimeUnit.SECONDS)
                    checkTransfer(!closed.get())
                    output.writeByte(if(allowed)1 else 0);output.flush()
                    if(!allowed){emit(ReceiveState("declined",message="已拒绝接收，没有保存手机备份。"));return}
                    deadline.set(System.nanoTime()+TimeUnit.MINUTES.toNanos(30));socket.soTimeout=120000
                    emit(ReceiveState("preparing",phone,message="手机正在整理备份…"))
                    val length=input.readLong();val expected=ByteArray(32);input.readFully(expected)
                    checkTransfer(length in 1..maxBytes,"备份过大或为空，未接收。")
                    Files.createDirectories(cache.toPath())
                    directory=File(cache,"incoming-${UUID.randomUUID()}");checkTransfer(directory.mkdir())
                    val part=File(directory,"phone.zip.part");val target=File(directory,"phone.zip")
                    output.writeByte(1);output.flush();socket.soTimeout=30000
                    val digest=MessageDigest.getInstance("SHA-256");var received=0L
                    FileOutputStream(part).use { file ->
                        val buffer=ByteArray(CHUNK_BYTES)
                        while(true) {
                            checkTransfer(!closed.get(),"传输已取消。")
                            val count=input.readInt();if(count==0)break
                            checkTransfer(count in 1..CHUNK_BYTES && count.toLong()<=length-received)
                            input.readFully(buffer,0,count);file.write(buffer,0,count);digest.update(buffer,0,count);received+=count
                            emit(ReceiveState("receiving",phone,received,length))
                        }
                        checkTransfer(received==length && MessageDigest.isEqual(digest.digest(),expected),"备份校验失败，未交给导入程序。")
                        file.fd.sync()
                    }
                    Files.move(part.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
                    checkTransfer(!closed.get(),"传输已取消。")
                    output.writeByte(1);output.flush()
                    val lease=ReceivedBackup(target,directory);directory=null
                    try { onComplete(lease) } catch(e:Exception){lease.close();throw e}
                    emit(ReceiveState("complete",phone,length,length));return
                } catch(e:Exception) {
                    if(claimed.get())throw e
                    if(++failures>=8)throw TransferException("尝试连接过多，请重新生成配对码。")
                } finally { runCatching { socket.close() };active=null }
            }
        } catch(e:Exception) {
            if(!closed.get())emit(ReceiveState("failed",message=(e as? TransferException)?.message?:"传输中断或等待超时，未导入；请重新扫码。"))
        } finally {
            directory?.let { dir->ReceivedBackup(File(dir,"phone.zip"),dir).close() }
            close()
        }
    }
    override fun close() {
        if(closed.compareAndSet(false,true)) {
            decision.complete(false);runCatching { server.close() };runCatching { active?.close() }
            timer.shutdownNow();worker.shutdown()
        }
    }
}
