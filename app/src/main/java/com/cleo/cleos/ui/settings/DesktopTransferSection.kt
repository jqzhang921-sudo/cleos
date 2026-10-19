package com.cleo.cleos.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.cleo.cleos.glass.LocalGlassPalette
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class TransferScannerActivity:CaptureActivity()

@Composable internal fun DesktopTransferSection(transfer:PhoneMigration,otherBusy:Boolean) {
    val palette=LocalGlassPalette.current
    var paste by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val scanner=rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(transfer::scanned) }
    DisposableEffect(transfer){onDispose{transfer.cancel()}}
    LifecycleEventEffect(Lifecycle.Event.ON_STOP){if(transfer.busy)transfer.cancel()}
    Section("迁移到电脑") {
        Text("电脑打开 Cleos 0.3 或更新预览版，点「扫码接收手机」。两台设备连接同一 Wi-Fi，再扫描电脑上的二维码。",color=palette.contentSecondary,fontSize=12.sp,lineHeight=19.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton({scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setCaptureActivity(TransferScannerActivity::class.java).setOrientationLocked(false).setBeepEnabled(false).setBarcodeImageEnabled(false).setPrompt("扫描 Cleos 电脑版的临时配对码"))},enabled=!transfer.busy&&!otherBusy){Text("扫描电脑二维码",color=palette.content)}
            TextButton({paste=true},enabled=!transfer.busy&&!otherBusy){Text("粘贴配对码",color=palette.content)}
        }
        Text("不会修改手机内容，也不持续同步。传输时请留在此页；切后台或离开会停止，可重新扫码。",color=palette.contentSecondary,fontSize=12.sp,lineHeight=18.sp)
        if(transfer.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(transfer::cancel){Text("停止传输",color=palette.content)}
        }
        if(transfer.message.isNotBlank())Text(transfer.message,color=palette.content,fontSize=13.sp,lineHeight=19.sp)
    }
    transfer.ticket?.takeUnless { transfer.busy }?.let { ticket ->
        AlertDialog(onDismissRequest=transfer::dismissTicket,title={Text("把备份传给这台电脑？")},text={
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("${ticket.name}\n${ticket.host}:${ticket.port}\n核对码 ${ticket.verificationCode}")
                Text("请先核对电脑也显示相同数字。本次只支持发送整份 Cleos 备份：全部 TA、聊天、记忆、日记（含小秘密）、信件、待办与媒体。不含 API Key。",fontSize=13.sp,lineHeight=20.sp)
                Text("电脑同意接收后才整理和发送。传完需在电脑预览并确认导入，原电脑资料不会被覆盖。",fontSize=12.sp)
            }
        },confirmButton={TextButton(transfer::start,enabled=!otherBusy){Text("确认并传输全部备份")}},dismissButton={TextButton(transfer::dismissTicket){Text("取消")}})
    }
    if(paste)AlertDialog(onDismissRequest={paste=false;code=""},title={Text("粘贴电脑的配对码")},text={OutlinedTextField(code,{code=it},label={Text("Cleos 配对码")},maxLines=4)},confirmButton={TextButton({transfer.scanned(code);code="";paste=false},enabled=code.isNotBlank()){Text("核对设备")}},dismissButton={TextButton({paste=false;code=""}){Text("取消")}})
}
