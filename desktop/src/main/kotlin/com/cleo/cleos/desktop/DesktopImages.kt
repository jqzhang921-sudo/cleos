package com.cleo.cleos.desktop

import java.awt.image.BufferedImage
import java.awt.Image
import java.io.ByteArrayOutputStream
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** Bound image dimensions before allocating pixels; re-encoding removes file metadata. */
internal fun normalizedImage(source:Path):ByteArray {
    if(!Files.isRegularFile(source,NOFOLLOW_LINKS) || Files.size(source) !in 1..20L*1024*1024)
        throw TextFailure("请选择不超过 20 MiB 的本地图片。")
    Files.newInputStream(source,NOFOLLOW_LINKS).use {input -> MemoryCacheImageInputStream(input).use {stream ->
        val readers=ImageIO.getImageReaders(stream)
        if(!readers.hasNext())throw TextFailure("图片格式无法读取，请使用 PNG 或 JPEG。")
        val reader=readers.next()
        try {
            if(reader.formatName.lowercase() !in setOf("png","jpeg","jpg"))throw TextFailure("目前支持 PNG 和 JPEG 图片。")
            reader.input=stream
            val width=reader.getWidth(0);val height=reader.getHeight(0)
            if(width<=0 || height<=0 || width.toLong()*height>24_000_000)throw TextFailure("图片尺寸过大，请缩小到 2400 万像素以内。")
            return normalizedImage(reader.read(0))
        } finally {reader.dispose()}
    }}
}
internal fun normalizedImage(original:Image):ByteArray {
    val width=original.getWidth(null);val height=original.getHeight(null)
    if(width<=0 || height<=0 || width.toLong()*height>24_000_000)throw TextFailure("截图尚未就绪或尺寸超过 2400 万像素，请重新复制或缩小图片。")
    val scale=minOf(1.0,1600.0/maxOf(width,height))
    val image=BufferedImage(maxOf(1,(width*scale).toInt()),maxOf(1,(height*scale).toInt()),BufferedImage.TYPE_INT_RGB)
    image.createGraphics().useGraphics {g ->
        g.color=java.awt.Color.WHITE;g.fillRect(0,0,image.width,image.height)
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        if(!g.drawImage(original,0,0,image.width,image.height,null))throw TextFailure("截图还没有复制完整，请稍后再试。")
    }
    return ByteArrayOutputStream().also {ImageIO.write(image,"jpeg",it)}.toByteArray().also {
        if(it.size>4*1024*1024)throw TextFailure("处理后的图片仍过大，请选择更小的图片。")
    }
}
private inline fun java.awt.Graphics2D.useGraphics(block:(java.awt.Graphics2D)->Unit) {try{block(this)}finally{dispose()}}

internal fun addLocalImage(directory:Path,source:Path):Attachment {
    return storeImage(directory,normalizedImage(source))
}
internal fun addRasterImage(directory:Path,source:Image):Attachment = storeImage(directory,normalizedImage(source))
private fun storeImage(directory:Path,bytes:ByteArray):Attachment {
    val media=directory.resolve("media")
    if(Files.exists(media,NOFOLLOW_LINKS) && !Files.isDirectory(media,NOFOLLOW_LINKS))throw TextFailure("媒体目录暂时不可用。")
    Files.createDirectories(media)
    val name="desktop-${newId()}.jpg";val temporary=Files.createTempFile(media,".image-",".partial")
    try {Files.write(temporary,bytes);Files.move(temporary,media.resolve(name))}finally{Files.deleteIfExists(temporary)}
    return Attachment("media/$name",label="用户上传")
}

/** Only controlled local media references become data URLs; URLs/paths in text are never fetched. */
internal fun prepareImages(messages:List<WireMessage>,root:Path,enabled:Boolean,checkCancelled:()->Unit={}):List<WireMessage> {
    var budget=8
    var bytesLeft=12*1024*1024
    return messages.asReversed().map {message ->
        checkCancelled()
        val refs=if(enabled && message.role=="user")message.images.take(minOf(4,budget)) else emptyList()
        val images=refs.mapNotNull {relative ->
            checkCancelled()
            val file=localMedia(root,relative) ?: return@mapNotNull null
            try {
                val bytes=normalizedImage(file)
                if(bytes.size>bytesLeft)return@mapNotNull null
                bytesLeft-=bytes.size
                "data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(bytes)
            }catch(_:Exception){null}
        }
        budget-=images.size
        val omitted=message.images.size-images.size
        message.copy(content=message.content+if(omitted>0) "\n[$omitted 张图片未附入本次请求，可能缺失、不可解码或超过本次图片数量上限；不能假装看过]" else "",images=images)
    }.asReversed()
}
