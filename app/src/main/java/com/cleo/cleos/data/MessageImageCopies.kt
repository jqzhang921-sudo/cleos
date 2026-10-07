package com.cleo.cleos.data

import java.io.File
import java.util.UUID

/** New messages own their files so deleting either message cannot remove the other's image. */
internal object MessageImageCopies {
    fun copyFile(dir: File, name: String): String {
        require(name.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.[A-Za-z0-9]+")))
        val source = File(dir, name)
        val target = File(dir, "message-${UUID.randomUUID()}.${source.extension}")
        try { source.copyTo(target); return target.name }
        catch (e: Exception) { target.delete(); throw e }
    }
    fun copy(dir: File, pictures: List<MessageImage>): List<MessageImage> {
        val made = mutableListOf<File>()
        try {
            return pictures.map { picture ->
                require(picture.file.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.(png|jpg|jpeg)")))
                val source = File(dir, picture.file)
                val target = File(dir, "message-${UUID.randomUUID()}.${source.extension}")
                made += target
                source.copyTo(target)
                picture.copy(file = target.name)
            }
        } catch (e: Exception) {
            made.forEach { it.delete() }
            throw e
        }
    }
}
