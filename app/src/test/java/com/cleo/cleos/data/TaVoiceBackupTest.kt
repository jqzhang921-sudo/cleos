package com.cleo.cleos.data

import com.cleo.cleos.ai.TaVoices
import com.cleo.cleos.data.db.CompanionEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TaVoiceBackupTest {
    private val settings = BackupSettings(apiBaseUrl = "https://chat.example.invalid", apiModel = "fixture", aiName = "TA", userName = "user",
        persona = "", historySize = 40, wallpaper = null, glassMode = "Auto", wallpaperDark = null, wallpaperHue = null, wallpaperChroma = null)
    private val speech = AppSettings(speechEngine = "api", speechBaseUrl = "https://voice.example.invalid/v1", speechModel = "tts", speechVoice = "shared")
    private fun ta(id: Long, voice: String?) = CompanionEntity(id = id, name = "same name", apiBaseUrl = "https://chat.example.invalid", apiModel = "fixture", createdAt = 0,
        voiceChoices = voice?.let { TaVoices.saved("{}", requireNotNull(TaVoices.scope(speech)), it) } ?: "{}")

    @Test fun backupRoundTripKeepsEachActualOwnerAndSharedFollowerSeparate() {
        val backup = BackupFile(BackupFile.FORMAT, BackupFile.VERSION, 0, settings, emptyList(), emptyList(), emptyList(), emptyList(),
            companions = listOf(ta(1, "alpha"), ta(2, "beta"), ta(3, null)))
        val read = Json.decodeFromString<BackupFile>(Json.encodeToString(backup))
        TaVoices.validateBackup(read.companions)
        assertEquals(listOf("alpha", "beta", null), read.companions.map { TaVoices.choice(it.voiceChoices, speech) })
        assertEquals(listOf(1L, 2L, 3L), read.companions.map { it.id })
        assertEquals(15, BackupFile.VERSION)
    }

    @Test fun oldVersionTenCompanionsWithoutNewFieldFollowTheExistingSharedVoice() {
        val raw = Json.encodeToString(ta(1, null)).replace(",\"voiceChoices\":\"{}\"", "")
        val restored = Json.decodeFromString<CompanionEntity>(raw)
        assertEquals("{}", restored.voiceChoices); TaVoices.validateBackup(listOf(restored))
        assertEquals("shared", TaVoices.effective(speech, restored).speechVoice)
    }

    @Test fun oneMalformedCompanionRejectsAllVoiceChoicesBeforeRestore() {
        try { TaVoices.validateBackup(listOf(ta(1, "alpha"), ta(2, "beta").copy(voiceChoices = "broken"))); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("未恢复")) }
    }
}
