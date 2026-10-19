package com.cleo.cleos.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cleo.cleos.transfer.*
import kotlinx.coroutines.*
import java.io.File

/** The page owns this foreground-only operation; cancelling closes its socket immediately. */
class PhoneMigration(private val scope:CoroutineScope,private val prepareAndUse:suspend ((File)->Unit)->Unit) {
    var ticket by mutableStateOf<PairingTicket?>(null);private set
    var busy by mutableStateOf(false);private set
    var message by mutableStateOf("");private set
    private var job:Job?=null
    private var sender:TransferSender?=null
    private var generation=0
    fun scanned(text:String) {
        if(busy)return
        ticket=null
        try { ticket=PairingTicket.parse(text);message="" }
        catch(e:TransferException){message=e.message.orEmpty()}
    }
    fun cancel() { generation++;sender?.close();sender=null;job?.cancel();job=null;busy=false;ticket=null;message="已停止；未导入的内容不会写入电脑资料库。" }
    fun dismissTicket(){ticket=null}
    fun start() {
        if(busy)return
        val target=ticket?:return;val revision=++generation
        busy=true;message="正在核对电脑，连接后请在电脑上同意接收…"
        job=scope.launch {
            val client=TransferSender(target);sender=client
            try {
                withContext(Dispatchers.IO){client.requestApproval()}
                ensureActive();message="电脑已同意，正在整理备份…"
                var lastProgress=0L
                prepareAndUse { file ->
                    client.send(file){sent,total ->
                        val now=System.nanoTime()
                        if(sent==total || now-lastProgress>200000000) {
                            lastProgress=now
                            scope.launch { if(generation==revision)message="正在传输 ${sent/1024} / ${total/1024} KB" }
                        }
                    }
                }
                ensureActive();message="传输完成。请到电脑预览并确认导入；手机内容保留。";ticket=null
            } catch(e:CancellationException){throw e}
            catch(e:Exception) {
                if(generation==revision) {
                    message=if(e is TransferException)e.message.orEmpty() else if(e is javax.net.ssl.SSLException)"无法核对电脑的临时证书，请检查设备时间并重新扫码。" else "没有完成传输。请确认两台设备连同一 Wi-Fi、电脑仍在等待接收，再重新扫码。"
                    ticket=null
                }
            } finally {
                client.close()
                if(generation==revision){busy=false;sender=null}
            }
        }
    }
}
