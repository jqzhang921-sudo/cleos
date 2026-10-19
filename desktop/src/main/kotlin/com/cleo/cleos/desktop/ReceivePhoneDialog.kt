package com.cleo.cleos.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.cleo.cleos.transfer.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import java.io.File
import java.net.NetworkInterface

internal data class LanAddress(val address:String,val adapter:String)
internal fun lanAddresses():List<LanAddress> = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { adapter ->
    adapter.inetAddresses.toList().mapNotNull { ip -> ip.hostAddress?.takeIf { !ip.isLoopbackAddress && PairingTicket.privateIpv4(it) }?.let { LanAddress(it,adapter.displayName.orEmpty()) } }
}.sortedBy { if(Regex("wi.?fi|wlan|wireless|以太|ethernet",RegexOption.IGNORE_CASE).containsMatchIn(it.adapter))0 else 1 }
internal fun pairingQr(text:String)=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,1,1,mapOf(EncodeHintType.MARGIN to 4))

@Composable internal fun ReceivePhoneDialog(cache:File,dismiss:()->Unit,received:(ReceivedBackup)->Unit,addressProvider:()->List<LanAddress> = ::lanAddresses) {
    val scope=rememberCoroutineScope()
    val clipboard=LocalClipboardManager.current
    var addresses by remember { mutableStateOf(emptyList<LanAddress>()) }
    var selected by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf<TransferReceiver?>(null) }
    var state by remember { mutableStateOf(ReceiveState("idle")) }
    var creating by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val found=withContext(Dispatchers.IO) { runCatching { addressProvider() }.getOrDefault(emptyList()) }
        selected=found.firstOrNull()?.address.orEmpty();addresses=found
    }
    DisposableEffect(Unit) { onDispose { receiver?.close() } }
    fun start() {
        val attempt=++revision
        creating=true;state=ReceiveState("idle")
        scope.launch {
            var created:TransferReceiver?=null
            try {
                withContext(Dispatchers.IO) {
                    val identity=TransferCertificate.create()
                    created=TransferReceiver(identity.context,PairingTicket.address(selected),identity.fingerprint,cache,onState={ update->scope.launch { if(revision==attempt)state=update } },onComplete={ lease->
                        if(!scope.isActive)lease.close() else scope.launch { received(lease) }.invokeOnCompletion { if(it!=null)lease.close() }
                    })
                }
                receiver=created;state=ReceiveState("waiting")
            } catch(e:CancellationException){throw e}
            catch(_:Exception){state=ReceiveState("failed",message="无法在这个地址接收，请检查网络或换一个局域网地址。")}
            finally { creating=false;if(!isActive)created?.close() }
        }
    }
    AlertDialog(onDismissRequest=dismiss,title={Text("从手机迁移到电脑")},text={
        Column(Modifier.width(480.dp).heightIn(max=590.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("手机和电脑连接同一 Wi-Fi。手机打开「设置 → 数据与备份 → 迁移到电脑」。",fontSize=13.sp)
            if(receiver==null || state.stage in setOf("expired","failed","declined")) {
                if(addresses.isEmpty())Text("没有找到局域网地址，请连上 Wi-Fi 后重新打开。",fontSize=12.sp)
                addresses.forEach { address -> TextButton({selected=address.address},enabled=!creating) { Text((if(selected==address.address)"✓ " else "")+address.address+" · "+address.adapter.take(28),fontSize=12.sp) } }
                Text("选择与手机同一 Wi-Fi 的地址；代理或虚拟网卡通常无法连接。",fontSize=12.sp)
                if(state.message.isNotBlank())Text(state.message,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                Button({receiver?.close();receiver=null;start()},enabled=selected.isNotBlank()&&!creating) { Text(if(creating)"正在准备…" else "生成临时二维码") }
            } else receiver?.ticket?.let { ticket ->
                if(state.stage=="waiting") {
                    val matrix=remember(ticket){pairingQr(ticket.encode())}
                    Canvas(Modifier.size(280.dp).align(Alignment.CenterHorizontally).semantics { contentDescription="手机迁移配对二维码" }) {
                        drawRect(Color.White);val cell=size.width/matrix.width
                        for(y in 0 until matrix.height)for(x in 0 until matrix.width)if(matrix[x,y])drawRect(Color.Black,Offset(x*cell,y*cell),Size(cell,cell))
                    }
                    Text("核对码  ${ticket.verificationCode}",Modifier.align(Alignment.CenterHorizontally))
                    TextButton({clipboard.setText(AnnotatedString(ticket.encode()))},Modifier.align(Alignment.CenterHorizontally)){Text("复制配对码",fontSize=12.sp)}
                    Text("二维码 5 分钟内有效，只用一次；包含临时配对凭证，请勿分享。",fontSize=12.sp)
                }
                if(state.stage=="approval") {
                    Text("${state.phone} 请求发送整份 Cleos 备份",fontSize=15.sp)
                    Text("请核对手机上的数字：${ticket.verificationCode}。只有你正在操作的手机才点同意。",fontSize=13.sp)
                    Row { TextButton({receiver?.reject()}){Text("拒绝")};Button({receiver?.approve()}){Text("同意接收")} }
                }
                if(state.stage=="preparing") { CircularProgressIndicator(Modifier.size(24.dp));Text("手机正在整理备份…",fontSize=13.sp) }
                if(state.stage=="receiving") { LinearProgressIndicator(progress={if(state.total>0)state.received.toFloat()/state.total else 0f},modifier=Modifier.fillMaxWidth());Text("已接收 ${state.received/1024} / ${state.total/1024} KB",fontSize=12.sp) }
            }
            Text("范围：全部 TA、私聊与群聊、记忆、日记（含小秘密）、信件、待办和媒体。不含 API Key。",fontSize=12.sp,lineHeight=19.sp)
            Text("传完仍需预览并确认导入；原电脑资料保留。这是一次迁移，不会持续同步。",fontSize=12.sp,lineHeight=19.sp)
        }
    },confirmButton={TextButton(dismiss){Text("关闭并停止接收")}})
}
