package com.cleo.cleos.desktop

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun transcriptionUrl(settings:TranscriptionSettings):String {
    val base=settings.baseUrl.trim().trimEnd('/').removeSuffix("/audio/transcriptions").trimEnd('/')
    val url=base.toHttpUrlOrNull()?:throw TextFailure("请在语音设置中填写完整的 HTTPS 接口地址。")
    if(url.username.isNotEmpty() || url.password.isNotEmpty() || url.query!=null || url.fragment!=null)throw TextFailure("转写地址不能包含用户名、密码、查询参数或片段。")
    if(!url.isHttps && !settings.allowHttp)throw TextFailure("HTTP 会明文传输录音和 Key，请先在语音设置中明确确认。")
    return "$base/audio/transcriptions"
}
internal fun voiceKeyAddress(base:String)="voice:"+base.trim().trimEnd('/').removeSuffix("/audio/transcriptions").trimEnd('/')
internal fun interface VoiceTranscriber {suspend fun transcribe(settings:TranscriptionSettings,key:String,file:Path):String}

/** Upload is explicit and limited to a single locally recorded WAV; no redirects, history or images. */
internal class VoiceClient(private val http:OkHttpClient=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS)
    .callTimeout(120,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()):VoiceTranscriber {
    @OptIn(InternalCoroutinesApi::class)
    override suspend fun transcribe(settings:TranscriptionSettings,key:String,file:Path):String=withContext(Dispatchers.IO) {
        val url=transcriptionUrl(settings)
        if(settings.model.isBlank() || key.isBlank())throw TextFailure("请先配置语音转文字的模型和 API Key。")
        if(!Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(file) !in 45..3_840_044)throw TextFailure("录音文件缺失或超过转写上限，原草稿保留。")
        val header=Files.newInputStream(file,java.nio.file.LinkOption.NOFOLLOW_LINKS).use {it.readNBytes(44)}
        val wav=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if(header.size!=44 || String(header,0,4)!="RIFF" || String(header,8,8)!="WAVEfmt " || wav.getShort(20).toInt()!=1 || wav.getShort(22).toInt()!=1 || wav.getInt(24)!=16000 || wav.getShort(34).toInt()!=16 || String(header,36,4)!="data" || wav.getInt(40).toLong()!=Files.size(file)-44)
            throw TextFailure("这不是本机录制的有效 WAV，未上传；原草稿保留。")
        val body=MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model",settings.model.trim()).addFormDataPart("response_format","json")
            .addFormDataPart("file","recording.wav",file.toFile().asRequestBody("audio/wav".toMediaType())).build()
        val call=http.newCall(Request.Builder().url(url).header("Authorization","Bearer ${key.trim()}").post(body).build())
        val cancellation=currentCoroutineContext().job.invokeOnCompletion(onCancelling=true,invokeImmediately=true){if(it!=null)call.cancel()}
        try {call.execute().use {response->
            currentCoroutineContext().ensureActive()
            if(!response.isSuccessful)throw TextFailure(failureText(response.code,response.peekBody(8192).string()))
            val source=response.body.source();val buffer=okio.Buffer();var count=0L
            while(true){currentCoroutineContext().ensureActive();val n=source.read(buffer,(200_001-count).coerceAtMost(8192));if(n<0)break;count+=n;if(count>200000)throw TextFailure("转写结果过长，原录音草稿保留。");}
            val result=runCatching {Json.parseToJsonElement(buffer.readUtf8()).jsonObject["text"]?.jsonPrimitive?.takeIf {it.isString}?.content}.getOrNull()?.trim()
            result?.takeIf {it.isNotBlank()}?:throw TextFailure("没有识别到可用文字，原录音保留，可重试或自行填写。")
        }}catch(e:java.io.IOException){currentCoroutineContext().ensureActive();throw TextFailure("转写连接中断或超时，录音草稿保留，可稍后重试。")}
        finally {cancellation.dispose()}
    }
}
