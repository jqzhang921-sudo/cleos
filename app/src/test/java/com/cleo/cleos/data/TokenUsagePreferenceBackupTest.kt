package com.cleo.cleos.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class TokenUsagePreferenceBackupTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val backup = BackupSettings(apiBaseUrl = "https://example.invalid", apiModel = "fixture", aiName = "TA",
        userName = "User", persona = "", historySize = 20, wallpaper = null, glassMode = "Auto",
        wallpaperDark = null, wallpaperHue = null, wallpaperChroma = null)

    @Test fun olderBackupsAndExistingInstallationsStartWithUsageHidden() {
        val old = JsonObject(json.parseToJsonElement(json.encodeToString(backup)).jsonObject - "showTokenUsage")
        assertFalse(json.decodeFromString<BackupSettings>(old.toString()).showTokenUsage)
        assertFalse(AppSettings().showTokenUsage)
    }

    @Test fun bothChoicesSurviveBackupWithoutChangingThinkingOrVoicePreferences() {
        for (visible in listOf(false, true)) {
            val original = backup.copy(showTokenUsage = visible, showThought = false, expandVoiceText = true)
            val restored = json.decodeFromString<BackupSettings>(json.encodeToString(original))
            assertEquals(visible, restored.showTokenUsage)
            assertFalse(restored.showThought)
            assertTrue(restored.expandVoiceText)
            assertEquals(original.apiBaseUrl, restored.apiBaseUrl)
        }
    }
}
