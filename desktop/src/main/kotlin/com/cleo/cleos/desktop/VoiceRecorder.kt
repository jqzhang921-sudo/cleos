package com.cleo.cleos.desktop

import androidx.compose.runtime.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.file.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*
import javax.swing.SwingUtilities

internal interface Microphone:AutoCloseable {fun open();fun read(bytes:ByteArray):Int}
private class SystemMicrophone:Microphone {
    @Volatile private var line:TargetDataLine?=null
    override fun open(){val next=AudioSystem.getTargetDataLine(AudioFormat(16000f,16,1,true,false));line=next;next.open(AudioFormat(16000f,16,1,true,false),6400);next.start()}
    override fun read(bytes:ByteArray)=line?.read(bytes,0,bytes.size)?:-1
    override fun close(){line?.let {runCatching {it.stop();it.flush();it.close()}};line=null}
}
internal data class RecordingState(val chatId:String="",val active:Boolean=false,val seconds:Int=0,val finishing:Boolean=false,val error:String="")

/** No device is opened until Start. Stop/close never waits for a blocking device read on the UI thread. */
internal class VoiceRecorder(private val microphone:()->Microphone={SystemMicrophone()}):AutoCloseable {
    private class Session(val chat:String){val stopped=AtomicBoolean(false);val discard=AtomicBoolean(false);@Volatile var mic:Microphone?=null}
    private val executor=Executors.newSingleThreadExecutor {r->Thread(r,"cleos-recorder").apply {isDaemon=true}}
    private val cleanup=Executors.newSingleThreadExecutor {r->Thread(r,"cleos-mic-stop").apply {isDaemon=true}}
    private val deadline=Executors.newSingleThreadScheduledExecutor {r->Thread(r,"cleos-record-limit").apply {isDaemon=true}}
    private var session:Session?=null
    @Volatile private var closed=false
    var state by mutableStateOf(RecordingState());private set
    fun start(root:Path,chat:String,finished:(VoiceDraft?)->Unit) {
        if(closed || session!=null)return
        val next=Session(chat);session=next;state=RecordingState(chatId=chat,active=true)
        val limit=deadline.schedule({SwingUtilities.invokeLater {if(session===next && !closed)stop()}},120,java.util.concurrent.TimeUnit.SECONDS)
        executor.execute {
            var result:VoiceDraft?=null;var error="";var createdFile:String?=null
            try {
                val mic=microphone();next.mic=mic
                try {
                    if(!next.stopped.get())mic.open()
                    val pcm=ByteArrayOutputStream();val buffer=ByteArray(3200)
                    while(!next.stopped.get() && pcm.size()<3_840_000) {
                        val n=try {mic.read(buffer)}catch(e:Exception){if(next.stopped.get())break else throw e};if(n<0)break
                        if(n==0){if(!next.stopped.get())throw TextFailure("麦克风没有返回声音，请检查设备。")else break}
                        val length=minOf(n,3_840_000-pcm.size());pcm.write(buffer,0,length-length%2)
                        val seconds=pcm.size()/32000
                        publish(next){it.copy(seconds=seconds)}
                    }
                    if(!next.discard.get()) {
                        if(pcm.size()<16000)throw TextFailure("录音不足半秒，未保存；请重新录制。")
                        val folder=root.resolve("media")
                        if(Files.exists(folder,LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))throw TextFailure("媒体目录无法写入。")
                        Files.createDirectories(folder)
                        val name="desktop-voice-${newId()}.wav";val path=folder.resolve(name)
                        createdFile="media/$name"
                        val raw=pcm.toByteArray();Files.write(path,wavBytes(raw),StandardOpenOption.CREATE_NEW)
                        result=VoiceDraft("media/$name",(raw.size*1000L/32000).coerceAtMost(120000))
                    }
                }finally {mic.close();next.mic=null}
            }catch(e:Exception){if(result==null)createdFile?.let {discardVoiceFile(root,it)};if(!next.discard.get())error=(e as? TextFailure)?.message?:"麦克风暂时无法录音，请检查设备或系统权限后重试。"}
            val recording=result
            limit.cancel(false)
            SwingUtilities.invokeLater {
                if(session===next) {
                    session=null;state=RecordingState(error=error)
                    if(!closed && !next.discard.get())finished(recording)
                    else recording?.let {discardVoiceFile(root,it.file)}
                }
            }
        }
    }
    private fun publish(expected:Session,change:(RecordingState)->RecordingState){SwingUtilities.invokeLater {if(session===expected && !closed)state=change(state)}}
    fun stop(discard:Boolean=false) {
        val current=session?:return
        if(discard)current.discard.set(true)
        current.stopped.set(true);state=state.copy(finishing=true)
        current.mic?.let {mic->cleanup.execute {mic.close()}}
    }
    override fun close(){if(!closed){closed=true;stop(true);executor.shutdown();cleanup.shutdown();deadline.shutdownNow()}}
}
internal fun wavBytes(pcm:ByteArray):ByteArray {
    require(pcm.size%2==0 && pcm.size<=3_840_000)
    return ByteBuffer.allocate(pcm.size+44).order(LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray());putInt(pcm.size+36);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1)
        putInt(16000);putInt(32000);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.size);put(pcm)
    }.array()
}
internal fun discardVoiceFile(root:Path,file:String) {
    if(Regex("media/desktop-voice-[a-f0-9-]{36}\\.wav").matches(file))runCatching {localMedia(root,file)?.let {Files.deleteIfExists(it)}}
}
