package com.cleo.cleos.transfer

import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class TransferException(message: String) : Exception(message)
internal fun checkTransfer(ok: Boolean, message: String = "传输数据不完整，请重新配对。") { if (!ok) throw TransferException(message) }
internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
internal fun unhex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** No DNS, public address, URL navigation, or proxy is involved in a pairing ticket. */
class PairingTicket(val host: String, val port: Int, val fingerprint: String, val id: String, val token: String, val name: String) {
    init {
        checkTransfer(privateIpv4(host) && port in 1024..65535, "配对地址必须是局域网 IPv4 地址。")
        checkTransfer(Regex("[a-f0-9]{64}").matches(fingerprint) && Regex("[a-f0-9]{32}").matches(id))
        checkTransfer(Regex("[A-Za-z0-9_-]{43}").matches(token) && tokenBytes().size == 32)
        checkTransfer(name.isNotBlank() && name.length <= 60 && name.none { it.isISOControl() })
    }
    internal fun tokenBytes(): ByteArray = Base64.getUrlDecoder().decode(token)
    val verificationCode: String get() {
        val hash=MessageDigest.getInstance("SHA-256").digest((fingerprint+id+token).toByteArray(Charsets.UTF_8))
        val value=((hash[0].toInt() and 255).toLong() shl 24) or ((hash[1].toInt() and 255).toLong() shl 16) or ((hash[2].toInt() and 255).toLong() shl 8) or (hash[3].toInt() and 255).toLong()
        return (value % 1000000).toString().padStart(6,'0')
    }
    fun encode(): String = "cleos-transfer://pair/v1?" + linkedMapOf("host" to host,"port" to port.toString(),"fp" to fingerprint,"id" to id,"token" to token,"name" to name)
        .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value,"UTF-8")}" }
    // Prevent tickets / bearer secrets being interpolated into logs and errors.
    override fun toString() = "CleosPairingTicket(redacted)"
    companion object {
        fun create(host:String,port:Int,fingerprint:String,name:String="Cleos 电脑"): PairingTicket {
            val random=SecureRandom();val bytes=ByteArray(32).also(random::nextBytes)
            return PairingTicket(host,port,fingerprint,hex(ByteArray(16).also(random::nextBytes)),Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),name)
        }
        fun parse(text:String): PairingTicket = try {
            checkTransfer(text.length <= 1200)
            val uri=URI(text.trim())
            checkTransfer(uri.scheme=="cleos-transfer" && uri.host=="pair" && uri.path=="/v1" && uri.rawUserInfo==null && uri.port==-1 && uri.fragment==null,"请扫描 Cleos 电脑版当前显示的二维码。")
            val entries=uri.rawQuery.orEmpty().split('&').map { entry -> val pair=entry.split('=',limit=2);checkTransfer(pair.size==2);pair[0] to URLDecoder.decode(pair[1],"UTF-8") }
            checkTransfer(entries.map { it.first }.distinct().size==6 && entries.size==6)
            val values=entries.toMap();checkTransfer(values.keys==setOf("host","port","fp","id","token","name"))
            PairingTicket(values.getValue("host"),values.getValue("port").toInt(),values.getValue("fp"),values.getValue("id"),values.getValue("token"),values.getValue("name"))
        } catch (e:TransferException) { throw e } catch (_:Exception) { throw TransferException("二维码无法识别，请重新扫描电脑上的 Cleos 配对码。") }
        fun privateIpv4(value:String): Boolean {
            val parts=value.split('.');if(parts.size!=4 || parts.any { !Regex("0|[1-9][0-9]{0,2}").matches(it) })return false
            val n=parts.map { it.toInt() };if(n.any { it !in 0..255 } || n[3]==0 || n[3]==255)return false
            // Loopback supports local protocol verification; the desktop never advertises it.
            return value=="127.0.0.1" || n[0]==10 || (n[0]==172 && n[1] in 16..31) || (n[0]==192 && n[1]==168)
        }
        /** Byte parsing bypasses hostname resolution. */
        fun address(host:String): InetAddress { checkTransfer(privateIpv4(host));return InetAddress.getByAddress(host.split('.').map { it.toInt().toByte() }.toByteArray()) }
    }
}
