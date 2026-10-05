package com.cleo.cleos.data

import com.cleo.cleos.data.db.FavoriteEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FavoritesTest {
    private fun message(id: Long, role: String = "user", text: String = "消息 $id", at: Long = id) =
        MessageEntity(id = id, conversationId = 4, role = role, content = text, createdAt = at)

    @Test fun selectionKeepsChronologyNotTapOrderAndMarksOnlyRealGaps() {
        val history = listOf(message(5), message(2, "tool"), message(1), message(4), message(3, "assistant"))
        val parts = FavoriteContent.select(history, linkedSetOf(5, 3, 1), "我", "云")
        assertEquals(listOf(1L, 3L, 5L), parts.map { it.messageId })
        assertEquals(listOf(false, false, true), parts.map { it.gapBefore })
        assertEquals(listOf("我", "云", "我"), parts.map { it.name })
    }

    @Test fun equalTimestampsKeepMessageIdOrder() {
        val parts = FavoriteContent.select(listOf(message(8, at = 1), message(7, at = 1)), setOf(8, 7), "我", "TA")
        assertEquals(listOf(7L, 8L), parts.map { it.messageId })
        assertFalse(parts.last().gapBefore)
    }

    @Test fun toolResultsSecretCardsAndThinkingAreNotCollected() {
        for (role in listOf("tool", "note", "request", "call", "pat")) assertFalse(FavoriteContent.eligible(message(1, role)))
        assertFalse(FavoriteContent.eligible(message(1, "assistant", "").copy(thought = "private")))
        assertFalse(FavoriteContent.eligible(message(1).copy(note = "给 TA 看")))
        assertTrue(FavoriteContent.eligible(message(1, "assistant").copy(error = "停止了")))
    }

    @Test fun voiceWithoutTranscriptionCanBeKeptAndRoundTripsWithItsDuration() {
        val voice = message(1, text = "").copy(audio = MessageAudios.encode(MessageAudio("voice_saved.wav", 8321)))
        assertTrue(FavoriteContent.eligible(voice))
        val parts = FavoriteContent.select(listOf(voice), setOf(1), "我", "TA")
        assertEquals(parts, FavoriteContent.decode(FavoriteContent.encode(parts)))
        assertEquals(8321L, parts.single().audio?.ms)
        assertEquals(listOf("voice_saved.wav"), FavoriteContent.files(parts))
    }

    @Test fun imagesWithoutTextAreIncluded() {
        val pic = message(1, text = "").copy(images = MessageImages.encode(listOf(MessageImage("favorite_pic.jpg", 400, 800))))
        assertTrue(FavoriteContent.eligible(pic))
        val parts = FavoriteContent.select(listOf(pic), setOf(1), "我", "TA")
        assertEquals(800, parts.single().images.single().height)
        assertEquals(listOf("favorite_pic.jpg"), FavoriteContent.files(parts))
    }

    @Test fun snapshotsContainOnlySavedContentNotToolArgumentsOrReasoning() {
        val original = message(1).copy(reasoning = "secret thought", toolCalls = "secret arguments", reactions = "heart")
        val parts = FavoriteContent.select(listOf(original), setOf(1), "我", "TA")
        val encoded = FavoriteContent.encode(parts)
        assertFalse(encoded.contains("secret"))
        assertFalse(encoded.contains("heart"))
        assertEquals("消息 1", FavoriteContent.decode(encoded).single().text)
    }

    @Test fun searchFindsNotesAndOriginalWordsWithoutChangingOriginal() {
        val parts = listOf(FavoritePart(1, 1, "assistant", "云", "Remember today"))
        val entry = FavoriteEntity(companionId = 1, conversationId = 4, sourceKey = "1:1", parts = FavoriteContent.encode(parts), createdAt = 2, note = "考完试那天")
        assertTrue(FavoriteContent.matches(entry, "remember"))
        assertTrue(FavoriteContent.matches(entry, " 考完试 "))
        assertFalse(FavoriteContent.matches(entry, "不存在"))
        assertTrue(FavoriteContent.matches(entry, ""))
        assertEquals(parts, FavoriteContent.decode(entry.copy(note = "").parts))
    }

    private fun oldBackup() = """{
      "format":"cleos-backup","version":1,"exportedAt":1,
      "settings":{"apiBaseUrl":"","apiModel":"","aiName":"","userName":"","persona":"","historySize":40,
        "wallpaper":null,"glassMode":"Auto","wallpaperDark":null,"wallpaperHue":null,"wallpaperChroma":null},
      "conversations":[],"messages":[],"diary":[],"todos":[]
    }"""

    @Test fun oldBackupsRestoreWithoutFavorites() {
        assertTrue(Json.decodeFromString<BackupFile>(oldBackup()).favorites.isEmpty())
    }

    @Test fun backupPreservesDetachedVoiceSnapshotAndNote() {
        val parts = listOf(FavoritePart(42, 123, "user", "我", "原对话已经删了", MessageAudio("voice_favorite_a.wav", 3000)))
        val entry = FavoriteEntity(id = 5, companionId = 1, conversationId = 99, sourceKey = "42:123",
            parts = FavoriteContent.encode(parts), createdAt = 456, note = "留下声音")
        val data = Json.decodeFromString<BackupFile>(oldBackup()).copy(favorites = listOf(entry))
        val restored = Json.decodeFromString<BackupFile>(Json.encodeToString(data))
        assertTrue(restored.messages.isEmpty())
        assertEquals(entry, restored.favorites.single())
        assertEquals(listOf("voice_favorite_a.wav"), FavoriteContent.files(FavoriteContent.decode(restored.favorites.single().parts)))
    }
}
