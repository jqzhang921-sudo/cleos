package com.cleo.cleos.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MessageImageCopiesTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun deletingEitherMessageKeepsTheOtherImageAndMetadata() {
        val dir = folder.newFolder()
        val original = File(dir, "photo.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val picture = MessageImage(original.name, 400, 200)
        val first = MessageImageCopies.copy(dir, listOf(picture)).single()
        val second = MessageImageCopies.copy(dir, listOf(picture)).single()
        assertNotEquals(first.file, picture.file); assertNotEquals(first.file, second.file)
        assertEquals(400, first.width); assertEquals(200, first.height)
        File(dir, first.file).delete()
        assertArrayEquals(byteArrayOf(1, 2, 3), original.readBytes())
        original.delete()
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir, second.file).readBytes())
    }
    @Test fun failedCopyCleansNewFilesWithoutTouchingOriginals() {
        val dir = folder.newFolder()
        File(dir, "photo.jpg").writeText("original")
        try {
            MessageImageCopies.copy(dir, listOf(MessageImage("photo.jpg", 1, 1), MessageImage("missing.jpg", 1, 1)))
            fail("Missing image must fail")
        } catch (_: java.io.IOException) { }
        assertEquals(listOf("photo.jpg"), dir.listFiles()!!.map { it.name })
        try {
            MessageImageCopies.copy(dir, listOf(MessageImage("../outside.png", 1, 1)))
            fail("Traversal must fail")
        } catch (_: IllegalArgumentException) { }
    }
}
