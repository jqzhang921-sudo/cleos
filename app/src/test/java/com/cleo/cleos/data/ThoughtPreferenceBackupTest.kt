package com.cleo.cleos.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ThoughtPreferenceBackupTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val backup = BackupSettings(apiBaseUrl = "https://example.com", apiModel = "test", aiName = "TA",
        userName = "用户", persona = "", historySize = 20, wallpaper = null, glassMode = "Auto",
        wallpaperDark = null, wallpaperHue = null, wallpaperChroma = null)

    @Test fun oldBackupKeepsThinkingCardsVisible() {
        val old = JsonObject(json.parseToJsonElement(json.encodeToString(backup)).jsonObject - "showThought")
        assertTrue(json.decodeFromString<BackupSettings>(old.toString()).showThought)
        assertTrue(AppSettings().showThought)
    }

    @Test fun hiddenAndVisiblePreferenceRoundTripWithoutChangingOtherDisplaySettings() {
        for (visible in listOf(false, true)) {
            val original = backup.copy(showThought = visible, messageSpacing = 3, expandVoiceText = true)
            val restored = json.decodeFromString<BackupSettings>(json.encodeToString(original))
            assertEquals(visible, restored.showThought)
            assertEquals(3, restored.messageSpacing)
            assertTrue(restored.expandVoiceText)
            assertEquals(original.apiBaseUrl, restored.apiBaseUrl)
        }
    }
}
