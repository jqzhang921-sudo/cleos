package com.cleo.cleos.desktop

import com.sun.jna.*
import com.sun.jna.Function
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Files
import java.nio.file.Path

internal data class PcmFormat(val rate:Int,val channels:Int,val bits:Int) {val frameSize get()=channels*bits/8}
internal interface PcmInput:AutoCloseable {val format:PcmFormat;fun next():ByteArray?}

/** Local-file-only Media Foundation decoding. COM objects stay on the playback worker thread.
 * ABI declarations verified against Microsoft's WinSDK headers; no sample source is copied.
 * https://learn.microsoft.com/en-us/windows/win32/medfound/tutorial--decoding-audio
 */
internal class WindowsAudioInput(path:Path):PcmInput {
    private interface MfPlat:StdCallLibrary {
        fun MFStartup(version:Int,flags:Int):Int
        fun MFShutdown():Int
        fun MFCreateMediaType(type:PointerByReference):Int
    }
    private interface MfReader:StdCallLibrary {
        fun MFCreateSourceReaderFromURL(url:WString,attributes:Pointer?,reader:PointerByReference):Int
    }
    private val plat: MfPlat=Native.load("mfplat",MfPlat::class.java)
    private val readWrite: MfReader=Native.load("mfreadwrite",MfReader::class.java)
    private var com=false;private var started=false;private var reader:Pointer?=null;private var ended=false
    private var firstChunk:ByteArray?=null
    override lateinit var format:PcmFormat;private set
    init {
        try {
            require(path.isAbsolute && Files.isRegularFile(path) && Files.size(path) in 1..128L*1024*1024)
            require(path.fileName.toString().substringAfterLast('.').lowercase() in setOf("m4a","mp3","wav","aac"))
            checkHr(Ole32.INSTANCE.CoInitializeEx(null,0).toInt());com=true
            checkHr(plat.MFStartup(0x00020070,0));started=true
            val ref=PointerByReference()
            checkHr(readWrite.MFCreateSourceReaderFromURL(WString(path.toString()),null,ref));reader=ref.value
            call(reader!!,4,-2,0);call(reader!!,4,-3,1)
            val partial=PointerByReference();checkHr(plat.MFCreateMediaType(partial))
            try {
                setGuid(partial.value,"48eba18e-f8c9-4687-bf11-0a74c9f96a8f","73647561-0000-0010-8000-00aa00389b71")
                setGuid(partial.value,"f7e34c9a-42e8-4714-b74b-cb29d72c35e5","00000001-0000-0010-8000-00aa00389b71")
                call(reader!!,7,-3,null,partial.value)
            } finally {release(partial.value)}
            refreshFormat()
            // Some AAC decoders negotiate channels/rate on the first ReadSample. Resolve that
            // before opening the speaker line; keep the first PCM chunk instead of dropping it.
            firstChunk=readNext(true)
        } catch(e:Exception) {close();throw e}
    }
    override fun next():ByteArray? {
        firstChunk?.let {firstChunk=null;return it}
        return readNext(false)
    }
    private fun refreshFormat() {
        val current=PointerByReference();call(reader!!,6,-3,current)
        try {
            format=PcmFormat(attribute(current.value,"5faeeae7-0290-4c31-9e8a-c534f68d9dba"),
                attribute(current.value,"37e48bf5-645e-4c5b-89de-ada9e29b696a"),attribute(current.value,"f2deb57f-40fa-4764-aa33-ed4f2d1ff669"))
            require(format.rate in 8000..192000 && format.channels in 1..8 && format.bits in setOf(16,24,32))
        } finally {release(current.value)}
    }
    private fun readNext(allowFormatChange:Boolean):ByteArray? {
        if(ended)return null
        repeat(1000) {
            val sample=PointerByReference();val flags=IntByReference()
            call(reader?:error("closed"),9,-3,0,null,flags,null,sample)
            try {
                if(flags.value and 1!=0)throw TextFailure("录音解码中断。")
                if(flags.value and (16 or 32)!=0) {
                    if(!allowFormatChange)throw TextFailure("录音中途改变了音频格式，已停止播放。")
                    refreshFormat()
                }
                ended=flags.value and 2!=0
                val value=sample.value
                if(value!=null) {
                    val buffer=PointerByReference();call(value,41,buffer)
                    try {
                        val bytes=PointerByReference();val size=IntByReference();call(buffer.value,3,bytes,null,size)
                        try {
                            require(size.value in 0..4*1024*1024 && size.value%format.frameSize==0)
                            if(size.value>0)return bytes.value.getByteArray(0,size.value)
                        } finally {call(buffer.value,4)}
                    } finally {release(buffer.value)}
                }
                if(ended)return null
            } finally {release(sample.value)}
        }
        throw TextFailure("录音没有可播放的音频数据。")
    }
    override fun close() {
        reader?.let {release(it)};reader=null
        if(started){plat.MFShutdown();started=false}
        if(com){Ole32.INSTANCE.CoUninitialize();com=false}
    }
    private fun guid(value:String)=Guid.GUID(value).also {it.write()}.pointer
    private fun setGuid(target:Pointer,key:String,value:String)=call(target,24,guid(key),guid(value))
    private fun attribute(target:Pointer,key:String):Int=IntByReference().also {call(target,7,guid(key),it)}.value
    private fun call(target:Pointer,index:Int,vararg args:Any?):Int {
        val function=Function.getFunction(target.getPointer(0).getPointer(index.toLong()*Native.POINTER_SIZE),Function.ALT_CONVENTION)
        return function.invokeInt(arrayOf(target,*args)).also(::checkHr)
    }
    private fun release(target:Pointer?) {if(target!=null)runCatching {Function.getFunction(target.getPointer(0).getPointer(2L*Native.POINTER_SIZE),Function.ALT_CONVENTION).invokeInt(arrayOf(target))}}
    private fun checkHr(code:Int) {if(code<0)throw TextFailure("Windows 无法解码这段录音（${Integer.toHexString(code)}），原文件保留。")}
}
