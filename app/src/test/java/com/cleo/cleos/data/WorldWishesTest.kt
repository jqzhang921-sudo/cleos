package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorldWishesTest {
    private val wish = WorldWishEntity(1, 10, "風之書", "合成作者", "想看看風如何被描寫。", WorldWishes.identity("風之書"), "run-one", 1)
    private val article = WorldArticleEntity(2, 10, "風之書", "合成來源", "風", WorldReading.fingerprint("風"), 1)
    private fun validate(items: List<WorldWishEntity>) = WorldWishes.validateBackup(items, listOf(article), setOf(10, 20))

    @Test fun unicodeCaseAndTitlePunctuationDeduplicateWithoutGuessingTranslations() {
        assertEquals(WorldWishes.identity(" Ｔｈｅ　Ｗｉｎｄ "), WorldWishes.identity("The wind"))
        assertEquals(WorldWishes.identity("《風之書》"), wish.identity)
        assertTrue(WorldWishes.duplicate("《風之書》", listOf(wish.copy(status = "ignored", handledAt = 2)), emptyList()))
        assertTrue(WorldWishes.duplicate("風之書", emptyList(), listOf(article)))
        assertFalse(WorldWishes.duplicate("The Wind", listOf(wish), listOf(article)))
    }
    @Test fun rollingCooldownCapsAndClockRollbackDoNotCreateRepeatedRequests() {
        assertFalse(WorldWishes.mayAsk(listOf(wish), 0))
        assertFalse(WorldWishes.mayAsk(listOf(wish), WorldWishes.COOLDOWN))
        assertTrue(WorldWishes.mayAsk(listOf(wish), WorldWishes.COOLDOWN + 1))
        assertFalse(WorldWishes.mayAsk((1..10).map { wish.copy(id = it.toLong(), status = "later") }, Long.MAX_VALUE))
        assertFalse(WorldWishes.mayAsk((1..100).map { wish.copy(id = it.toLong(), status = "ignored") }, Long.MAX_VALUE))
        assertTrue(WorldWishes.mayAsk(emptyList(), 1))
    }
    @Test fun requestsAreBoundedPublicFields() {
        assertTrue(WorldWishes.valid("未知作者的書", "", "想讀一讀"))
        for (bad in listOf(wish.copy(title = "!!!"), wish.copy(title = "x".repeat(121)), wish.copy(author = "x".repeat(121)),
            wish.copy(reason = " "), wish.copy(reason = "x".repeat(601)), wish.copy(reason = "bad\u0000")))
            assertFalse(WorldWishes.valid(bad.title, bad.author, bad.reason))
    }
    @Test fun backupRoundTripKeepsIgnoredDedupAndVerifiedLinkedBook() {
        val linked = wish.copy(status = "added", handledAt = 2, articleId = article.id, catalogKey = "OL123W", catalogTitle = "The Wind", catalogAuthor = "Synthetic", verifiedAt = 2)
        val restored = Json.decodeFromString<WorldWishEntity>(Json.encodeToString(linked))
        assertEquals(linked, restored); validate(listOf(restored))
        validate(listOf(wish.copy(status = "ignored", handledAt = 2)))
        validate(listOf(linked.copy(articleId = null))) // The user may remove the linked source later.
        assertTrue(BackupFile.VERSION >= 24)
    }
    @Test fun malformedOwnershipLinksStatusesCatalogAndDuplicatesAreRejected() {
        val bad = listOf(wish.copy(companionId = 99), wish.copy(identity = "wrong"), wish.copy(status = "sent"),
            wish.copy(articleId = 2), wish.copy(status = "added"), wish.copy(handledAt = 2),
            wish.copy(status = "added", handledAt = 2, articleId = 999),
            wish.copy(companionId = 20, status = "added", handledAt = 2, articleId = 2),
            wish.copy(catalogKey = "OL123W"), wish.copy(verifiedAt = 2),
            wish.copy(verifiedAt = 2, catalogKey = "../../private", catalogTitle = "風"))
        for (w in bad) try { validate(listOf(w)); fail("Malformed wish accepted: $w") } catch (_: IllegalArgumentException) { }
        for (items in listOf(listOf(wish, wish), listOf(wish, wish.copy(id = 2, runId = "other")), listOf(wish, wish.copy(id = 2, title = "海", identity = "海"))))
            try { validate(items); fail("Duplicate accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun legacyBackupHasNoWishesAndNewBackupPreservesThem() {
        val settings = BackupSettings(apiBaseUrl = "https://example.invalid", apiModel = "synthetic", aiName = "TA", userName = "Human", persona = "", historySize = 40,
            wallpaper = null, glassMode = "Auto", wallpaperDark = null, wallpaperHue = null, wallpaperChroma = null)
        val old = BackupFile(BackupFile.FORMAT, 17, 1, settings, emptyList(), emptyList(), emptyList(), emptyList())
        val codec = Json { encodeDefaults = false }
        val raw = codec.encodeToString(old)
        assertFalse(raw.contains("worldWishes")); assertTrue(codec.decodeFromString<BackupFile>(raw).worldWishes.isEmpty())
        assertEquals(listOf(wish), codec.decodeFromString<BackupFile>(codec.encodeToString(old.copy(version = 18, worldWishes = listOf(wish)))).worldWishes)
    }
    @Test fun catalogUsesActualWorkKeysAndRejectsNonBookPathsAndOversizedFields() {
        val books = WorldBookCatalog.parse("""{"docs":[{"key":"/works/OL123W","title":"The Wind","author_name":["A","B"]},{"key":"OL456W","title":"海"}]}""")
        assertEquals(2, books.size); assertEquals("A、B", books[0].author)
        assertEquals("https://openlibrary.org/works/OL123W", books[0].url)
        assertTrue(WorldBookCatalog.parse("""{"docs":[{"key":"https://evil.invalid","title":"bad"},{"key":"/books/OL1M","title":"bad"}]}""").isEmpty())
        for (bad in listOf("{}", "{\"docs\":[null,null,null,null,null,null]}", "x".repeat(131_073)))
            try { WorldBookCatalog.parse(bad); fail() } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }
}
