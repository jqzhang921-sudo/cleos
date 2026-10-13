package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.CompanionEntity
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FeedPhotoPlansTest {
    private val candidate = PhotoCandidate("wp:123", PhotoCredit("White cat", "Artist", "https://wordpress.org/photos/photo/123/",
        "CC0 1.0", "https://creativecommons.org/publicdomain/zero/1.0/"), PhotoLibrary.WordPress)
    private val photo = DownloadedPhoto(candidate, byteArrayOf(1, 2), "image/jpeg", WordPressPhotos.FALLBACK_NOTE)
    private var searches = 0
    private var downloads = 0
    private var picks = 0
    private var available = true
    private val source = object : PhotoSource {
        override suspend fun search(query: String): List<PhotoCandidate> { searches++; assertEquals("cat", query); return if (available) listOf(candidate) else emptyList() }
        override suspend fun download(candidate: PhotoCandidate): DownloadedPhoto { downloads++; return photo }
    }
    private val planner = FeedPhotoPlanner(source)
    private suspend fun plan(query: String?, allowed: suspend () -> Boolean = { true }, pick: String? = candidate.id) =
        planner.prepare("想晒晒太阳", query, allowed) { picks++; FeedPhotoChoice("猫咪也爱阳光", pick) }

    @Test fun textOnlyDraftDoesNotSearchChooseOrDownload() = runBlocking {
        assertEquals(FeedPhotoPlan("想晒晒太阳", null), plan(null))
        assertEquals(0, searches); assertEquals(0, picks); assertEquals(0, downloads)
    }
    @Test fun modelMayRequestOnePhotoOrChooseTextOnlyAfterSearch() = runBlocking {
        val pictured = plan("cat")
        assertEquals(photo, pictured.photo)
        assertEquals("猫咪也爱阳光", pictured.content)
        val plain = plan("cat", pick = null)
        assertNull(plain.photo)
        assertEquals(2, searches); assertEquals(2, picks); assertEquals(1, downloads)
    }
    @Test fun emptyGalleryOrInventedIdsNeverDownloadOrProduceAPostPlan() = runBlocking {
        available = false
        try { plan("cat"); fail() } catch (_: ToolFailure) { }
        assertEquals(0, picks)
        available = true
        try { plan("cat", pick = "https://localhost/private"); fail() } catch (_: ToolFailure) { }
        assertEquals(0, downloads)
    }
    @Test fun invalidKeywordsAreRejectedBeforeNetwork() = runBlocking {
        for (query in listOf("", "x".repeat(121), "cat\nprivate")) {
            try { plan(query); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, searches)
    }
    @Test fun changedSettingsAtEveryPhasePreventFurtherWork() = runBlocking {
        for (invalidateAt in 1..4) {
            var checks = 0
            try { plan("cat", allowed = { ++checks != invalidateAt }); fail() } catch (_: FeedPhotoChanged) { }
        }
        assertEquals(3, searches); assertEquals(2, picks); assertEquals(1, downloads)
    }
    @Test fun cancellingDuringSearchStopsBeforeChoosingOrDownloading() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val waiting = FeedPhotoPlanner(object : PhotoSource {
            override suspend fun search(query: String): List<PhotoCandidate> { entered.complete(Unit); awaitCancellation() }
            override suspend fun download(candidate: PhotoCandidate): DownloadedPhoto = error("must not download")
        })
        val job = launch { waiting.prepare("正文", "cat", { true }) { error("must not choose") } }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
    @Test fun choiceMustKeepRealIdAndPostLengthLimits() {
        assertEquals(candidate.id, FeedPhotoRules.choice("""{"content":" 猫咪 ","photoId":"wp:123"}""", listOf(candidate), 80).photoId)
        assertNull(FeedPhotoRules.choice("""{"content":"文字也很好","photoId":null}""", listOf(candidate), 80).photoId)
        for (raw in listOf("""{"content":"","photoId":"wp:123"}""", """{"content":"${"好".repeat(81)}"}""",
            """{"content":"你好","photoId":"wp:456"}""", """{"content":"你好","photoId":"https://example.com"}""")) {
            assertTrue(runCatching { FeedPhotoRules.choice(raw, listOf(candidate), 80) }.isFailure)
        }
    }
    @Test fun optInIsPerTaAndGalleryChangesInvalidateCurrentSelection() {
        val before = AppSettings(feedPhotoTas = setOf(7), photoLibrary = PhotoLibrary.WordPress)
        assertTrue(FeedPhotoRules.enabled(before, 7)); assertFalse(FeedPhotoRules.enabled(before, 8))
        assertFalse(FeedPhotoRules.enabled(AppSettings(), 7))
        assertFalse(FeedPhotoRules.sameSettings(before, before.copy(feedPhotoTas = emptySet()), 7))
        assertFalse(FeedPhotoRules.sameSettings(before, before.copy(photoLibrary = PhotoLibrary.Openverse), 7))
        assertTrue(FeedPhotoRules.sameSettings(before, before.copy(feedPhotoTas = setOf(7, 8)), 7))
    }
    @Test fun onlyPostingCanRequestPhotosAndQuietReplyQuotaAreRespected() {
        val raw = """{"action":"post","content":"猫咪","photoQuery":" cat "}"""
        assertEquals("cat", FeedVisitRules.decode(raw, emptyList(), emptyList(), true, true).photoQuery)
        assertTrue(runCatching { FeedVisitRules.decode(raw, emptyList(), emptyList(), true, false) }.isFailure)
        assertTrue(runCatching { FeedVisitRules.decode(raw, emptyList(), emptyList(), false, true) }.isFailure)
        assertTrue(runCatching { FeedVisitRules.decode("""{"action":"skip","photoQuery":"cat"}""", emptyList(), emptyList(), true, true) }.isFailure)
        assertTrue(runCatching { FeedVisitRules.decode("""{"action":"reply","postId":1,"photoQuery":"cat"}""", emptyList(), emptyList(), true, true) }.isFailure)
        val ta = CompanionEntity(apiBaseUrl = "a", apiModel = "b", createdAt = 1)
        assertTrue(FeedVisitRules.system(ta, "", true, true).contains("纯文字也很好"))
        assertTrue(FeedVisitRules.system(ta, "", false, true).contains("photoQuery 必须为 null"))
    }
    @Test fun manualBrowseHonoursPhotoPermissionAndCanRemainSilent() {
        assertNull(FeedAiRules.drafting("""{"content":"","sourceIndex":null}""", emptyList(), true))
        assertTrue(runCatching { FeedAiRules.drafting("""{"content":"","photoQuery":"cat"}""", emptyList(), true) }.isFailure)
        assertTrue(runCatching { FeedAiRules.drafting("""{"content":"猫咪","photoQuery":"cat"}""", emptyList(), false) }.isFailure)
        assertEquals("cat", FeedAiRules.drafting("""{"content":"猫咪","photoQuery":"cat"}""", emptyList(), true)!!.photoQuery)
        assertNull(FeedAiRules.drafting("""{"content":"日常","sourceIndex":99}""", emptyList(), false)!!.sourceIndex)
    }
    @Test fun committedPostOwnsImageAndAttributionSurvivesStorage() = runBlocking {
        var deleted = false
        var kept: List<MessageImage> = emptyList()
        val result = FeedPhotoCommit.run(photo, { _, _ -> MessageImage("photo.jpg", 300, 225) }, { deleted = true }) {
            kept = it; FeedPhotoCommit.Result(123L, true)
        }
        assertEquals(123L, result); assertFalse(deleted)
        val restored = MessageImages.decode(MessageImages.encode(kept)).single()
        assertEquals("WordPress 照片库", restored.source)
        assertEquals(WordPressPhotos.FALLBACK_NOTE, restored.credit!!.previewNote)
    }
    @Test fun staleDuplicateOrFailedTransactionDiscardsNewImage() = runBlocking {
        val removed = mutableListOf<String>()
        val result = FeedPhotoCommit.run(photo, { _, _ -> MessageImage("new.jpg", 1, 2) }, { removed += it }) {
            FeedPhotoCommit.Result("设置改变", false)
        }
        assertEquals("设置改变", result); assertEquals(listOf("new.jpg"), removed)
        try {
            FeedPhotoCommit.run<Unit>(photo, { _, _ -> MessageImage("failed.jpg", 1, 2) }, { removed += it }) { error("database failure") }
            fail()
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("new.jpg", "failed.jpg"), removed)
    }
    @Test fun cancelledSaveFinishesThenCleansUpWithoutPublishing() = runBlocking {
        val saved = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val removed = mutableListOf<String>()
        var wrote = false
        val job = launch {
            FeedPhotoCommit.run(photo, { _, _ -> saved.complete(Unit); release.await(); MessageImage("cancelled.jpg", 1, 2) }, { removed += it }) {
                wrote = true; FeedPhotoCommit.Result(Unit, true)
            }
        }
        saved.await(); job.cancel(); release.complete(Unit); job.join()
        assertFalse(wrote); assertEquals(listOf("cancelled.jpg"), removed)
    }
    @Test fun cancellationAtCommittedWriteNeverDeletesPublishedImage() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var deleted = false
        var posted = false
        val job = launch {
            FeedPhotoCommit.run(photo, { _, _ -> MessageImage("committed.jpg", 1, 2) }, { deleted = true }) {
                entered.complete(Unit); release.await(); posted = true; FeedPhotoCommit.Result(Unit, true)
            }
        }
        entered.await(); job.cancel(); release.complete(Unit); job.join()
        assertTrue(posted); assertFalse(deleted)
    }
    @Test fun failedImageDecodeNeverCallsPostWriter() = runBlocking {
        var wrote = false
        try {
            FeedPhotoCommit.run<Unit>(photo, { _, _ -> error("bad image") }, { error("nothing stored") }) {
                wrote = true; FeedPhotoCommit.Result(Unit, true)
            }
            fail()
        } catch (_: IllegalStateException) { }
        assertFalse(wrote)
    }
    @Test fun oldPreferencesAndBackupsDoNotEnableNewPhotoBehaviour() {
        assertTrue(decodeFeedPhotoTas(null).isEmpty())
        assertTrue(decodeFeedPhotoTas("bad json").isEmpty())
        assertEquals(setOf(7L, 8L), decodeFeedPhotoTas("[7,8,7,0,-1]"))
        val old = """{"apiBaseUrl":"a","apiModel":"b","aiName":"TA","userName":"U","persona":"","historySize":40,"wallpaper":null,"glassMode":"Auto","wallpaperDark":null,"wallpaperHue":null,"wallpaperChroma":null}"""
        val backup = Json.decodeFromString<BackupSettings>(old)
        assertTrue(backup.feedPhotoTas.isEmpty())
        val enabled = backup.copy(feedPhotoTas = listOf(7, 8))
        assertEquals(enabled, Json.decodeFromString<BackupSettings>(Json.encodeToString(enabled)))
    }
}
