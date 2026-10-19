package com.cleo.cleos.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*
import javax.swing.SwingUtilities

internal interface PcmOutput:AutoCloseable {
    fun open(format:PcmFormat)
    fun write(bytes:ByteArray,offset:Int,length:Int):Int
    fun pause(paused:Boolean)
    fun finish()
    val frames:Long
}
private class SpeakerOutput:PcmOutput {
    @Volatile private var line:SourceDataLine?=null
    override fun open(format:PcmFormat) {
        val audio=AudioFormat(format.rate.toFloat(),format.bits,format.channels,true,false)
        val next=AudioSystem.getSourceDataLine(audio);line=next
        next.open(audio,maxOf(format.frameSize,format.rate*format.frameSize/5));next.start()
    }
    override fun write(bytes:ByteArray,offset:Int,length:Int)=line?.write(bytes,offset,length)?:0
    override fun pause(paused:Boolean) {line?.let {if(paused)it.stop() else it.start()}}
    override val frames get()=line?.longFramePosition?:0
    override fun finish() {line?.drain()}
    override fun close() {line?.let {runCatching {it.stop();it.flush();it.close()}};line=null}
}
internal data class AudioState(val file:String="",val loading:Boolean=false,val playing:Boolean=false,val paused:Boolean=false,val seconds:Long=0,val error:String="")

/** One clip per open library. UI control never runs decode or waits for a playback worker. */
internal class AudioPlayback(private val decode:(Path)->PcmInput={WindowsAudioInput(it)},private val output:()->PcmOutput={SpeakerOutput()}):AutoCloseable {
    private class Session(val file:String) {val stopped=AtomicBoolean(false);val monitor=Object();@Volatile var paused=false;@Volatile var sink:PcmOutput?=null}
    private val executor=Executors.newSingleThreadExecutor {r->Thread(r,"cleos-local-audio").apply {isDaemon=true}}
    private val cleanup=Executors.newSingleThreadExecutor {r->Thread(r,"cleos-audio-stop").apply {isDaemon=true}}
    @Volatile private var session:Session?=null
    @Volatile private var closed=false
    var state by mutableStateOf(AudioState());private set
    fun toggle(root:Path,file:String) {
        if(closed)return
        val current=session
        if(current?.file==file && !current.stopped.get()) {
            current.paused=!current.paused
            runCatching {current.sink?.pause(current.paused)}
            synchronized(current.monitor){current.monitor.notifyAll()}
            state=state.copy(paused=current.paused);return
        }
        stop()
        val path=localMedia(root,file)
        if(path==null) {state=AudioState(file=file,error="录音文件缺失，无法播放。");return}
        val next=Session(file);session=next;state=AudioState(file=file,loading=true)
        executor.execute {
            try {
                decode(path.toAbsolutePath()).use {input ->
                    if(next.stopped.get())return@execute
                    val sink=output();next.sink=sink
                    try {
                        sink.open(input.format)
                        publish(next){it.copy(loading=false,playing=true,paused=next.paused)}
                        var last=0L
                        while(!next.stopped.get()) {
                            synchronized(next.monitor) {while(next.paused && !next.stopped.get())next.monitor.wait(200)}
                            if(next.stopped.get())break
                            val bytes=input.next()?:break
                            var offset=0
                            while(offset<bytes.size && !next.stopped.get()) {
                                synchronized(next.monitor) {while(next.paused && !next.stopped.get())next.monitor.wait(200)}
                                if(next.stopped.get())break
                                val written=sink.write(bytes,offset,bytes.size-offset)
                                if(written<=0)throw TextFailure("音频输出已停止，请检查声音设备。")
                                offset+=written
                            }
                            val now=System.nanoTime()
                            if(now-last>150_000_000) {val seconds=sink.frames/input.format.rate;publish(next){it.copy(seconds=seconds)};last=now}
                        }
                        if(!next.stopped.get()) {sink.finish();val seconds=sink.frames/input.format.rate;publish(next){it.copy(loading=false,playing=false,paused=false,seconds=seconds)}}
                    } finally {sink.close();next.sink=null}
                }
            } catch(e:Throwable) {
                if(e is VirtualMachineError || e is ThreadDeath)throw e
                if(!next.stopped.get())publish(next){it.copy(loading=false,playing=false,paused=false,error=(e as? TextFailure)?.message?:"这段录音暂时无法播放，请检查格式和声音设备。原文件保留。")}
            } finally {SwingUtilities.invokeLater {if(session===next && !state.playing && !state.loading)session=null}}
        }
    }
    private fun publish(expected:Session,change:(AudioState)->AudioState) {SwingUtilities.invokeLater {if(session===expected && !closed && !expected.stopped.get())state=change(state)}}
    fun stop() {
        val old=session;session=null
        if(old!=null) {
            old.stopped.set(true);synchronized(old.monitor){old.monitor.notifyAll()}
            old.sink?.let {sink->cleanup.execute {sink.close()}}
        }
        state=AudioState()
    }
    override fun close() {if(!closed){stop();closed=true;executor.shutdown();cleanup.shutdown()}}
}
