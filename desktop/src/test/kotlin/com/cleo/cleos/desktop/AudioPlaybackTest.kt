package com.cleo.cleos.desktop

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlaybackTest {
    @get:Rule val temp=TemporaryFolder()
    private fun fixture(extension:String):Path {
        val path=temp.newFolder().toPath().resolve("tone.$extension")
        javaClass.getResourceAsStream("/audio/synthetic-tone.$extension")!!.use {Files.copy(it,path)};return path
    }
    @Test fun `real Windows Media Foundation decodes WAV MP3 and AAC m4a without speaker output`() {
        for(extension in listOf("wav","mp3","m4a")) WindowsAudioInput(fixture(extension).toAbsolutePath()).use {input ->
            assertTrue(input.format.channels in 1..2);assertEquals(16,input.format.bits)
            val pcm=java.io.ByteArrayOutputStream()
            while(true) {val next=input.next()?:break;pcm.write(next)}
            val bytes=pcm.toByteArray();val duration=bytes.size.toDouble()/input.format.frameSize/input.format.rate
            assertTrue("$extension duration $duration",duration in 0.45..0.75)
            // Measure the central tone, excluding compressed codec delay/padding and tiny noise.
            var crossings=0;var armed=false
            val first=(input.format.rate*0.15).toInt();val last=(input.format.rate*0.4).toInt()
            for(frame in first until last) {val i=frame*input.format.frameSize;val sample=(bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)
                if(sample< -200)armed=true
                if(armed && sample>200) {crossings++;armed=false}
            }
            val hz=crossings/0.25
            assertTrue("$extension pitch $hz",hz in 420.0..460.0)
            println("$extension PCM ${input.format} duration=$duration pitch=$hz")
        }
    }
    @Test fun `unsupported audio missing path and unsafe relative URI are rejected`() {
        val path=temp.newFile("bad.m4a").toPath();Files.writeString(path,"not audio")
        assertThrows(Exception::class.java) {WindowsAudioInput(path)}
        assertThrows(Exception::class.java) {WindowsAudioInput(Path.of("https://example.com/voice.m4a"))}
    }
    private class FakeOutput:PcmOutput {
        val closed=AtomicBoolean();@Volatile var written=0L;@Volatile var paused=false
        override fun open(format:PcmFormat) {}
        override fun write(bytes:ByteArray,offset:Int,length:Int):Int {Thread.sleep(15);if(closed.get())return 0;written+=length;return length}
        override fun pause(paused:Boolean) {this.paused=paused}
        override val frames get()=written/2
        override fun finish() {}
        override fun close() {closed.set(true)}
    }
    @Test fun `pause resume switching clips and close use one worker and stop cleanly`() = runBlocking(Dispatchers.Swing) {
        val root=temp.newFolder().toPath();Files.createDirectories(root.resolve("media"))
        Files.copy(fixture("wav"),root.resolve("media/a.wav"));Files.copy(fixture("wav"),root.resolve("media/b.wav"))
        val outputs=java.util.Collections.synchronizedList(mutableListOf<FakeOutput>())
        val player=AudioPlayback(decode={object:PcmInput {
            override val format=PcmFormat(16000,1,16)
            override fun next()=ByteArray(3200)
            override fun close() {}
        }},output={FakeOutput().also {outputs+=it}})
        try {
            player.toggle(root,"media/a.wav");withTimeout(5000){while(!player.state.playing)delay(10)}
            player.toggle(root,"media/a.wav");assertTrue(player.state.paused);delay(60)
            val written=outputs.first().written;delay(80);assertEquals(written,outputs.first().written)
            player.toggle(root,"media/a.wav");assertFalse(player.state.paused)
            player.toggle(root,"media/b.wav");withTimeout(5000){while(outputs.size<2 || !player.state.playing)delay(10)}
            assertTrue(outputs.first().closed.get());assertEquals("media/b.wav",player.state.file)
            player.stop();withTimeout(5000){while(!outputs.last().closed.get())delay(10)};assertEquals("",player.state.file)
        }finally{player.close()}
    }
    @Test fun `failed decode stays local and error is recoverable on next clip`() = runBlocking(Dispatchers.Swing) {
        val root=temp.newFolder().toPath();Files.createDirectories(root.resolve("media"));Files.copy(fixture("wav"),root.resolve("media/a.wav"))
        val player=AudioPlayback(decode={throw TextFailure("合成解码失败")},output={error("must not open speakers")})
        try {
            player.toggle(root,"../escape.wav");assertTrue(player.state.error.contains("缺失"))
            player.toggle(root,"media/a.wav");withTimeout(5000){while(player.state.loading)delay(10)}
            assertEquals("合成解码失败",player.state.error);assertFalse(player.state.playing)
            player.stop();assertEquals("",player.state.error)
        }finally{player.close()}
    }
}
