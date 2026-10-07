package com.cleo.cleos.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BubblePresetTest {
    private fun pack(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun manifest(preset: BubblePreset = BubblePreset()) = Json.encodeToString(BubblePackageManifest("cleos-bubble", 1, preset)).toByteArray()
    @Test fun presetRestoresBothSidesForCurrentCompanionAndKeepsOtherSettings() {
        val settings = AppSettings(userName = "Cleo", wallpaper = "wallpaper.png", myBubbleTheme = "peach", taBubbleThemes = mapOf("5" to "blue", "6" to "clear"),
            bubbleDecoration = BubbleDecoration(faceEmoji = "🥹"), bubblePaddingX = 18)
        val saved = BubblePreset.capture(settings, 5L, "我的方案")
        val restored = saved.apply(settings.copy(myBubbleTheme = "glass", bubblePaddingX = 6), 7L)
        assertEquals("peach", restored.myBubbleTheme)
        assertEquals("blue", restored.taBubbleThemes["7"])
        assertEquals("clear", restored.taBubbleThemes["6"])
        assertEquals("wallpaper.png", restored.wallpaper)
        assertEquals("Cleo", restored.userName)
        assertEquals(18, restored.bubblePaddingX)
        assertEquals("🥹", restored.bubbleDecoration.faceEmoji)
    }
    @Test fun packageWithoutImagesRoundTrips() {
        val preset = BubblePreset(name = "Emoji", decoration = BubbleDecoration(faceEmoji = "👩🏽‍🚀"))
        val result = BubblePackageReader.read(ByteArrayInputStream(pack(listOf("theme.json" to manifest(preset)))))
        assertEquals(preset, result.first)
    }
    @Test fun rejectsTraversalMissingImagesAndUnsupportedVersion() {
        val cases = listOf(
            listOf("theme.json" to manifest(), "images/../outside.png" to byteArrayOf(1)),
            listOf("theme.json" to manifest(BubblePreset(decoration = BubbleDecoration(faceImage = "bubble-x.png")))),
            listOf("theme.json" to Json.encodeToString(BubblePackageManifest("cleos-bubble", 99, BubblePreset())).toByteArray()))
        for (entries in cases) {
            try { BubblePackageReader.read(ByteArrayInputStream(pack(entries))); fail("Invalid package was accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun rejectsOversizedMetadataBeforeParsing() {
        try { BubblePackageReader.read(ByteArrayInputStream(pack(listOf("theme.json" to ByteArray(256 * 1024 + 1))))); fail("Oversized package was accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
