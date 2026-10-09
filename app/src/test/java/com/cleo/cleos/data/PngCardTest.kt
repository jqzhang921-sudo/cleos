package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A 角色卡 can arrive as a picture: the card's JSON sits in the PNG's own text, base64 under the
 * keyword `chara` (the newer `ccv3` carries the same thing). A picture without one in it, or a
 * file that is not a picture, reads as nothing rather than as a broken card.
 */
class PngCardTest {
    @Test
    fun aCardTuckedIntoAPictureIsRead() {
        val json = """{"name":"林夏","personality":"话少","greeting_message":"嗨"}"""
        val bytes = png("chara" to json)
        assertEquals(json, PngCard.json(bytes))
        // And what comes out of the picture reads as a card like any other file's.
        val card = ForeignFile.read(bytes, "image/png").cards.single()
        assertEquals("林夏", card.name)
        assertEquals("嗨", card.greeting)
    }

    @Test
    fun theNewerKeywordCarriesTheSameThing() {
        val json = """{"name":"林夏","personality":"话少"}"""
        assertEquals(json, PngCard.json(png("ccv3" to json)))
    }

    @Test
    fun aCardTooLongForOneLineIsReadTheSame() {
        // base64 of a real card's worth of text wraps over several lines; the reader takes it back
        // the way the writers break it, whitespace and all.
        val json = """{"name":"林夏","description":"${"安静的人".repeat(200)}"}"""
        assertEquals(json, PngCard.json(png("chara" to json, wrap = true)))
    }

    @Test
    fun aCardBesideAnotherPicturesTextIsStillFound() {
        // A real picture has other chunks around the one the card is in, in no set order.
        val json = """{"name":"洺洺","characterSetting":"说话短"}"""
        val bytes = picture(
            chunk("IHDR", ByteArray(13)),
            chunk("tEXt", "Software\u0000made somewhere".toByteArray(Charsets.ISO_8859_1)),
            chunk("tEXt", "chara\u0000${Base64.getEncoder().encodeToString(json.toByteArray())}".toByteArray(Charsets.ISO_8859_1)),
            chunk("IDAT", ByteArray(64)),
        )
        assertEquals(json, PngCard.json(bytes))
        assertEquals("洺洺", ForeignFile.read(bytes).cards.single().name)
    }

    @Test
    fun aPictureWithNoCardInItReadsAsNothing() {
        assertNull(PngCard.json(png("Comment" to "made with something")))
        // An empty keyword is not one either.
        assertNull(PngCard.json(png("" to "whatever")))
    }

    @Test
    fun aFileThatIsNotAPictureReadsAsNothing() {
        assertNull(PngCard.json(ByteArray(0)))
        assertNull(PngCard.json("林夏：话少".toByteArray()))
        // The signature on its own is not a picture yet.
        assertNull(PngCard.json(header()))
    }

    @Test
    fun aPictureThatStopsMidChunkReadsAsNothing() {
        val whole = png("chara" to """{"name":"林夏"}""")
        assertNull(PngCard.json(whole.copyOfRange(0, whole.size - 20)))
    }

    @Test
    fun aChunkClaimingMoreThanTheFileHoldsReadsAsNothing() {
        // A length that runs past the end of the file must be refused rather than sliced out of it.
        val bytes = picture(chunk("tEXt", "chara\u0000AAAA".toByteArray(Charsets.ISO_8859_1)))
        val huge = bytes.copyOf()
        // 0x7FFFFFFF: read as an Int, at + 12 + length would wrap round to before the file.
        huge[8] = 0x7F.toByte()
        huge[9] = 0xFF.toByte()
        huge[10] = 0xFF.toByte()
        huge[11] = 0xFF.toByte()
        assertNull(PngCard.json(huge))
    }

    private fun header() = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** A picture holding one text chunk, which is all of the format this reader looks at. */
    private fun png(text: Pair<String, String>, wrap: Boolean = false): ByteArray {
        val (keyword, value) = text
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray())
        return picture(chunk("tEXt", "$keyword\u0000${if (wrap) broken(encoded) else encoded}".toByteArray(Charsets.ISO_8859_1)))
    }

    private fun picture(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header())
        for (c in chunks) out.write(c)
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    /** One chunk: its length, its name, its bytes, and the checksum this reader never looks at. */
    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.write(ByteArray(4))
        return out.toByteArray()
    }

    /** base64 the way a real file carries it: lines of 64, each ending in a newline. */
    private fun broken(s: String): String = s.chunked(64).joinToString("\n", postfix = "\n")
}
