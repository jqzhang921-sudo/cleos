package com.cleo.cleos.desktop

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Path

internal sealed interface ImageOffer {
    data class Files(val paths:List<Path>):ImageOffer
    data class Pixels(val image:Image):ImageOffer
    val count get()=when(this) {is Files->paths.size;is Pixels->1}
}

/** Flavor checks do not read clipboard text, file bytes or remote URLs. */
internal fun supportsImageOffer(transfer:Transferable):Boolean = runCatching {
    transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || transfer.isDataFlavorSupported(DataFlavor.imageFlavor)
}.getOrDefault(false)

/** Called only on an explicit paste/drop. Never deserialize arbitrary flavors or parse text/URLs. */
internal fun readImageOffer(transfer:Transferable):ImageOffer? {
    if(transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        val files=transfer.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
            ?:throw TextFailure("没有读到有效的图片文件列表。")
        if(files.isEmpty() || files.size>4)throw TextFailure("每次最多添加 4 张图片。")
        val paths=files.map {item ->
            val file=item as? File ?:throw TextFailure("仅支持本地 PNG/JPEG 图片文件。")
            val path=file.toPath()
            if(!path.isAbsolute || path.toString().startsWith("\\\\") || file.extension.lowercase() !in setOf("png","jpg","jpeg"))
                throw TextFailure("仅支持本地 PNG/JPEG 图片；链接、文件夹和网络路径不能作为图片添加。")
            path
        }
        return ImageOffer.Files(paths)
    }
    if(transfer.isDataFlavorSupported(DataFlavor.imageFlavor)) {
        val image=transfer.getTransferData(DataFlavor.imageFlavor) as? Image
            ?:throw TextFailure("没有读到可用的截图，请重新复制图片。")
        return ImageOffer.Pixels(image)
    }
    return null
}

/** Main wires this provider to the composer; it is never invoked during rendering or polling. */
internal fun readClipboardTransfer():Transferable? {
    try {
        return Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
    } catch(e:TextFailure) {throw e}
    catch(_:Exception) {throw TextFailure("剪贴板暂时无法读取，请稍后再试或用「＋ 图片」选择文件。")}
}
