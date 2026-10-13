package com.cleo.cleos.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PhotoLibraryBackupTest {
    private val json = Json { encodeDefaults = true }
    private val original = BackupSettings(apiBaseUrl = "https://example.com", apiModel = "test", aiName = "TA",
        userName = "用户", persona = "", historySize = 20, wallpaper = null, glassMode = "Auto",
        wallpaperDark = null, wallpaperHue = null, wallpaperChroma = null)

    @Test fun oldInstallAndUnknownKeysRetainOpenverse() {
        assertEquals(PhotoLibrary.Openverse, AppSettings().photoLibrary)
        assertEquals(PhotoLibrary.Openverse, PhotoLibrary.decode(null))
        assertEquals(PhotoLibrary.Openverse, PhotoLibrary.decode("unknown"))
    }
    @Test fun backupRoundTripPreservesSelectionAndOldBackupsStillDecode() {
        val backup = original.copy(photoLibrary = PhotoLibrary.WordPress.name)
        val restored = json.decodeFromString<BackupSettings>(json.encodeToString(backup))
        assertEquals(PhotoLibrary.WordPress, PhotoLibrary.decode(restored.photoLibrary))
        val old = json.encodeToString(original).replace("\"photoLibrary\":null,", "")
        assertNull(json.decodeFromString<BackupSettings>(old).photoLibrary)
    }
}
