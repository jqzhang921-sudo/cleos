package com.cleo.cleos.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class BubbleSpacingCompatibilityTest {
    private val json = Json { encodeDefaults = true }
    private val backup = BackupSettings(apiBaseUrl = "https://example.com", apiModel = "test", aiName = "TA",
        userName = "用户", persona = "", historySize = 20, wallpaper = null, glassMode = "Auto",
        wallpaperDark = null, wallpaperHue = null, wallpaperChroma = null)

    @Test fun oldBackupsAndPresetsKeepOriginalSpacing() {
        val old = JsonObject(json.parseToJsonElement(json.encodeToString(backup)).jsonObject - "messageSpacing")
        assertEquals(6, json.decodeFromString<BackupSettings>(old.toString()).messageSpacing)
        assertEquals(6, json.decodeFromString<BubblePreset>("{}").messageSpacing)
        assertEquals(6, AppSettings().messageSpacing)
    }

    @Test fun backupKeepsCompactAndWideSpacing() {
        for (spacing in listOf(0, 3, 24)) {
            val restored = json.decodeFromString<BackupSettings>(json.encodeToString(backup.copy(messageSpacing = spacing)))
            assertEquals(spacing, restored.messageSpacing)
        }
    }

    @Test fun presetRestoresSpacingWithoutChangingBubbleSizeOrOtherTa() {
        val settings = AppSettings(messageSpacing = 0, bubblePaddingX = 8, bubblePaddingY = 4,
            taBubbleThemes = mapOf("2" to "peach"), wallpaper = "my-wallpaper.png")
        val saved = BubblePreset.capture(settings, 1, "小气泡")
        val portable = json.decodeFromString<BubblePreset>(json.encodeToString(saved))
        val result = portable.apply(settings.copy(messageSpacing = 24, bubblePaddingY = 16), 1)
        assertEquals(0, result.messageSpacing)
        assertEquals(8, result.bubblePaddingX)
        assertEquals(4, result.bubblePaddingY)
        assertEquals("peach", result.taBubbleThemes["2"])
        assertEquals(settings.wallpaper, result.wallpaper)
        assertEquals(0, saved.copy(messageSpacing = -100).apply(settings, 1).messageSpacing)
        assertEquals(24, saved.copy(messageSpacing = 100).apply(settings, 1).messageSpacing)
    }
}
