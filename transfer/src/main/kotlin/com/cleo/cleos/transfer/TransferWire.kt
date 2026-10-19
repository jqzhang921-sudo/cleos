package com.cleo.cleos.transfer

import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

const val MAX_TRANSFER_BYTES: Long = 2L * 1024 * 1024 * 1024
internal const val CHUNK_BYTES = 65536
internal const val TRANSFER_MAGIC = 0x434c5431
internal const val FULL_BACKUP = 1
fun digestFile(file: File): ByteArray {
    val digest=MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input -> val buffer=ByteArray(CHUNK_BYTES);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
    return digest.digest()
}
internal fun pinnedContext(ticket:PairingTicket): SSLContext {
    val manager=object:X509TrustManager {
        override fun getAcceptedIssuers():Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain:Array<out X509Certificate>?,authType:String?) { throw java.security.cert.CertificateException("Client certificates are not used") }
        override fun checkServerTrusted(chain:Array<out X509Certificate>?,authType:String?) {
            val leaf=chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Missing pinned certificate")
            if(!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(leaf.encoded),unhex(ticket.fingerprint)))
                throw java.security.cert.CertificateException("Cleos certificate mismatch")
            // The QR pins this fresh ephemeral certificate directly; no global trust store is changed.
            leaf.checkValidity()
        }
    }
    return SSLContext.getInstance("TLS").apply { init(null,arrayOf(manager),SecureRandom()) }
}

/** Phone-side connection. No backup may be generated/sent before requestApproval succeeds. */
class TransferSender(private val ticket:PairingTicket) : AutoCloseable {
    @Volatile private var closed=false
    @Volatile private var socket:SSLSocket?=null
    private lateinit var input:DataInputStream
    private lateinit var output:DataOutputStream
    private var approved=false
    fun requestApproval(phoneName:String="Cleos 手机") {
        checkTransfer(!closed)
        val connection=pinnedContext(ticket).socketFactory.createSocket() as SSLSocket
        socket=connection
        if(closed){connection.close();throw TransferException("传输已取消。")}
        try {
            connection.enabledProtocols=arrayOf("TLSv1.3");connection.soTimeout=15000
            connection.connect(java.net.InetSocketAddress(PairingTicket.address(ticket.host),ticket.port),10000)
            connection.startHandshake()
            input=DataInputStream(connection.inputStream.buffered());output=DataOutputStream(connection.outputStream.buffered())
            output.writeInt(TRANSFER_MAGIC);output.write(unhex(ticket.id));output.write(ticket.tokenBytes())
            output.writeUTF(phoneName.filterNot { it.isISOControl() }.take(60).ifBlank { "Cleos 手机" });output.writeByte(FULL_BACKUP);output.flush()
            connection.soTimeout=180000
            checkTransfer(input.readUnsignedByte()==1,"电脑没有同意接收，未发送备份。")
            connection.soTimeout=30000;approved=true
        } catch(e:Exception){close();throw e}
    }
    fun send(file:File,progress:(Long,Long)->Unit={_,_->}) {
        checkTransfer(approved && !closed,"请先完成设备确认。")
        try {
            val length=file.length();checkTransfer(file.isFile && length in 1..MAX_TRANSFER_BYTES,"备份过大或为空，未发送。")
            val digest=digestFile(file)
            output.writeLong(length);output.write(digest);output.flush()
            checkTransfer(input.readUnsignedByte()==1,"电脑未准备好接收，未发送备份。")
            var sent=0L
            file.inputStream().buffered().use { source -> val buffer=ByteArray(CHUNK_BYTES);while(true){
                checkTransfer(!closed,"传输已取消。")
                val n=source.read(buffer);if(n<0)break
                sent+=n;checkTransfer(sent<=length,"备份在发送时发生了变化，请重试。")
                output.writeInt(n);output.write(buffer,0,n);output.flush();progress(sent,length)
            } }
            checkTransfer(sent==length);output.writeInt(0);output.flush()
            checkTransfer(input.readUnsignedByte()==1,"电脑校验未通过，请重新传输。")
            approved=false
        } finally { close() }
    }
    override fun close(){closed=true;runCatching { socket?.close() }}
}
