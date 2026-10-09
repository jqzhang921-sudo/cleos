package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignImageTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test
    fun ordinaryImagesAreRejectedBeforeTextParsingEvenWithoutImageMime() {
        val headers = listOf(
            bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            bytes(0xff, 0xd8, 0xff, 0xe0),
            "GIF87a".toByteArray(), "GIF89a".toByteArray(),
            "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray(),
            bytes(0x49, 0x49, 0x2a, 0), bytes(0x4d, 0x4d, 0, 0x2a),
            bytes(0x49, 0x49, 0x2b, 0), bytes(0x4d, 0x4d, 0, 0x2b),
            "BM".toByteArray() + ByteArray(12),
        )
        headers.forEach { image ->
            val error = assertThrows(ImportException::class.java) { ForeignFile.read(image, "application/octet-stream") }
            assertTrue(error.message.orEmpty().contains("普通图片"))
            assertTrue(error.message.orEmpty().contains("提取"))
        }
    }

    @Test
    fun heifAvifAndCompatibleImageBrandsAreDetected() {
        listOf("heic", "mif1", "avif").forEach { brand ->
            val image = bytes(0, 0, 0, 24) + "ftyp$brand".toByteArray() + ByteArray(4) + "isom".toByteArray() + ByteArray(4)
            assertThrows(ImportException::class.java) { ForeignFile.read(image) }
        }
        val compatible = bytes(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(4) + "avif".toByteArray() + ByteArray(4)
        assertThrows(ImportException::class.java) { ForeignFile.read(compatible) }
    }

    @Test
    fun imageMimeCatchesOtherImageFormatsAndSvgWithoutTryingToImportTheirContent() {
        listOf("image/x-icon", "image/svg+xml", " IMAGE/JPEG ; charset=utf-8").forEach { mime ->
            assertThrows(ImportException::class.java) { ForeignFile.read("只有图像格式才会这样标记".toByteArray(), mime) }
        }
    }

    @Test
    fun ordinaryTextBeginningWithBmpLettersIsStillText() {
        assertEquals("BMP 是一种图片格式", ForeignFile.read("说明：BMP 是一种图片格式".toByteArray(), "text/plain").memories.single().summary)
        assertTrue(ForeignFile.read("BM 是名字的缩写".toByteArray()).memories.isNotEmpty())
    }
}
