package com.cleo.cleos.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun VoiceDraftView(vm:DesktopController) {
    val recording=vm.recorder.state
    if(recording.active) {
        Row(Modifier.fillMaxWidth()) {
            Text(if(recording.finishing)"正在保存录音…" else "● 正在录音 ${recording.seconds} 秒 · 最长 120 秒",Modifier.weight(1f),fontSize=12.sp)
            TextButton({vm.stopRecording()},enabled=!recording.finishing){Text("结束录音")}
            TextButton({vm.stopRecording(true)},enabled=!recording.finishing){Text("取消录音")}
        }
    }
    vm.selected.draftVoice?.let {voice->
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text("待发送语音 · %.1f 秒".format(voice.millis/1000.0),fontSize=12.sp)
            Row {
                TextButton({vm.audio.toggle(vm.store.directory,voice.file)},enabled=!vm.transcribing){Text(if(vm.audio.state.file==voice.file && vm.audio.state.playing && !vm.audio.state.paused)"暂停试听" else "试听录音")}
                TextButton(if(vm.transcribing)vm::cancelTranscription else vm::transcribeVoice){Text(if(vm.transcribing)"取消转写" else "转写录音")}
                TextButton(vm::removeVoice,enabled=!vm.mediaBusy && vm.busyThread==null){Text("移除录音")}
            }
            if(vm.audio.state.file==voice.file && vm.audio.state.error.isNotBlank())Text(vm.audio.state.error,fontSize=11.sp,color=MaterialTheme.colorScheme.error)
            Text(if(vm.transcribing)"正在转写，原录音草稿保留。" else "识别服务：${vm.data.transcription.baseUrl.ifBlank {"尚未配置"}}。点击转写只上传这段录音；发送前请核对文字。",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(voice.transcript.orEmpty(),vm::voiceTranscript,Modifier.fillMaxWidth().heightIn(max=100.dp).semantics {contentDescription="语音转写文字"},
                placeholder={Text("转写结果；也可自行填写这段语音的文字",fontSize=12.sp)},maxLines=3,enabled=!vm.transcribing)
        }
    }
}

@Composable internal fun VoiceSettingsDialog(vm:DesktopController,close:()->Unit) {
    val saved=vm.data.transcription
    var base by remember {mutableStateOf(saved.baseUrl)}
    var model by remember {mutableStateOf(saved.model)}
    var key by remember {mutableStateOf("")}
    var remove by remember {mutableStateOf(false)}
    var http by remember {mutableStateOf(saved.allowHttp)}
    var confirmHttp by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=close,title={Text("语音转文字")},text={
        Column(Modifier.widthIn(min=320.dp,max=520.dp).heightIn(max=470.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("使用兼容 /audio/transcriptions 的服务。只有点击「转写录音」才上传；不会发送聊天记录。Key 单独加密保存，留空保留已存的语音 Key。",fontSize=12.sp)
            OutlinedTextField(base,{base=it;http=false},Modifier.fillMaxWidth().semantics {contentDescription="转写接口地址"},label={Text("接口基础地址（含 /v1 等版本路径）")},singleLine=true)
            OutlinedTextField(model,{model=it},Modifier.fillMaxWidth().semantics {contentDescription="转写模型名"},label={Text("识别模型名")},singleLine=true)
            OutlinedTextField(key,{key=it},Modifier.fillMaxWidth().semantics {contentDescription="转写 API Key"},label={Text("API Key")},singleLine=true,visualTransformation=PasswordVisualTransformation())
            if(vm.hasVoiceKey())Row {Checkbox(remove,{remove=it});Text("移除这个语音接口已存的 Key",fontSize=12.sp)}
            if(base.trim().startsWith("http://",true))Row {Switch(http,{if(it)confirmHttp=true else http=false},Modifier.semantics {contentDescription="允许转写 HTTP"});Text("允许这个语音接口使用 HTTP",fontSize=12.sp)}
            if(vm.notice.isNotBlank())Text(vm.notice,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        }
    },confirmButton={TextButton({if(vm.configureVoice(TranscriptionSettings(base.trim(),model.trim(),http),key,remove))close()}){Text("保存语音配置")}},dismissButton={TextButton(close){Text("取消")}})
    if(confirmHttp)AlertDialog(onDismissRequest={confirmHttp=false},title={Text("允许明文传输？")},text={Text("HTTP 会明文传输录音和 API Key。仅在你信任的网络和服务上使用；确认只对这里填写的语音接口生效。")},
        confirmButton={TextButton({http=true;confirmHttp=false}){Text("了解风险，允许")}},dismissButton={TextButton({confirmHttp=false}){Text("取消")}})
}
