package com.cleo.cleos.data

import com.cleo.cleos.ai.FeedNewsSources
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FeedSourcesBackupTest {
    private val json = Json { encodeDefaults = true }
    private val original = BackupSettings(apiBaseUrl = "https://example.com", apiModel = "test", aiName = "TA", userName = "用户",
        persona = "喜欢猫", historySize = 20, wallpaper = null, glassMode = "Auto", wallpaperDark = null,
        wallpaperHue = null, wallpaperChroma = null, feedRssUrl = "https://example.com/rss", feedInterests = "家常菜")

    @Test fun oldBackupsWithoutCategoryPreferenceKeepTheirCustomFeed() {
        val oldJson = json.encodeToString(original).replace("\"feedNewsSources\":\"\",", "")
        val decoded = json.decodeFromString<BackupSettings>(oldJson)
        assertEquals("", decoded.feedNewsSources)
        assertEquals(setOf("custom"), FeedNewsSources.selected(decoded.feedNewsSources, decoded.feedRssUrl))
        assertEquals(original.feedInterests, decoded.feedInterests)
    }

    @Test fun selectedCategoriesAndAnUnusedCustomAddressSurviveRoundTrip() {
        val saved = original.copy(feedNewsSources = "cats,food")
        val decoded = json.decodeFromString<BackupSettings>(json.encodeToString(saved))
        assertEquals(saved, decoded)
        assertEquals(setOf("cats", "food"), FeedNewsSources.selected(decoded.feedNewsSources, decoded.feedRssUrl))
    }
}
