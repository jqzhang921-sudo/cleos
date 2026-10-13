package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WordPressPhotosTest {
    private val small = "https://pd.w.org/2026/10/123abc.12345-300x225.jpg"
    private val clear = "https://pd.w.org/2026/10/123abc.12345-768x576.jpg"
    private fun metadata(caption: String = "<p>A white cat &amp; a bowl.</p>", author: Boolean = true,
        clearUrl: String = clear, smallUrl: String = small, page: String = "https://wordpress.org/photos/photo/123abc/",
        mime: String = "image/jpeg"): String = buildJsonArray {
        add(buildJsonObject {
            put("id", 123); put("link", page)
            putJsonObject("title") { put("rendered", "Photo detail") }
            put("photo-thumbnail-url", smallUrl)
            putJsonObject("_embedded") {
                putJsonArray("author") { add(buildJsonObject {
                    if (author) put("name", "Photographer") else { put("code", "rest_user_invalid_id"); put("message", "private error detail") }
                }) }
                putJsonArray("wp:featuredmedia") { add(buildJsonObject {
                    put("media_type", "image"); put("mime_type", mime)
                    putJsonObject("caption") { put("rendered", caption) }
                    putJsonObject("media_details") { putJsonObject("sizes") {
                        putJsonObject("medium") { put("source_url", smallUrl) }
                        putJsonObject("medium_large") { put("source_url", clearUrl) }
                    } }
                }) }
            }
        })
    }.toString()
    private fun candidate() = WordPressPhotoText.parse(metadata()).single()
    private fun http(action: (Request) -> Pair<Int, ResponseBody>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (status, body) = action(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test").body(body).build()
    }.build()
    private fun String.jsonBody() = toResponseBody("application/json".toMediaType())
    private fun failure(action: suspend () -> Unit) = runBlocking {
        try { action(); fail("Expected failure") } catch (e: ToolFailure) { return@runBlocking e }
        error("unreachable")
    }

    @Test fun captionAuthorAndCc0AreKeptWithoutInventingMissingAuthors() {
        val photo = candidate()
        assertEquals("wp:123", photo.id)
        assertEquals(PhotoLibrary.WordPress, photo.library)
        assertEquals("A white cat & a bowl.", photo.credit.title)
        assertEquals("Photographer", photo.credit.creator)
        assertEquals("CC0 1.0", photo.credit.license)
        assertEquals(listOf(clear, small), photo.previews)
        val missing = WordPressPhotoText.parse(metadata(author = false)).single()
        assertEquals("作者信息暂缺", missing.credit.creator)
        assertFalse(missing.toString().contains("private error"))
    }
    @Test fun genericSearchRequiresWholeWordsAndAllQueryTerms() {
        assertTrue(WordPressPhotoText.parse(metadata(), "cat").isNotEmpty())
        assertTrue(WordPressPhotoText.parse(metadata("Delicate flowers and intricate leaves"), "cat").isEmpty())
        assertTrue(WordPressPhotoText.parse(metadata(), "black cat").isEmpty())
        assertTrue(WordPressPhotoText.parse(metadata("A BLACK cat on a beach"), "black cat").isNotEmpty())
        assertFalse(WordPressPhotoText.matches("cat", "..."))
    }
    @Test fun unsafeOriginalOversizedAndCredentialUrlsAreNeverDownloadCandidates() {
        val bad = listOf("https://example.com/2026/10/photo-300x225.jpg",
            "https://pd.w.org.evil.com/2026/10/photo-300x225.jpg", "http://pd.w.org/2026/10/photo-300x225.jpg",
            "https://secret@pd.w.org/2026/10/photo-300x225.jpg", "https://pd.w.org:444/2026/10/photo-300x225.jpg",
            "https://pd.w.org/2026/10/photo.jpg", "https://pd.w.org/2026/10/photo-5588x7450.jpg",
            "$small?token=secret", "$small#secret", "https://pd.w.org/2026/10/photo-300x225.svg",
            "https://pd.w.org/2026/10/%2fprivate-300x225.jpg")
        bad.forEach { assertNull(it, WordPressPhotoText.safePreview(it)) }
        assertTrue(WordPressPhotoText.parse(metadata(smallUrl = "https://localhost/private")).isEmpty())
        assertTrue(WordPressPhotoText.parse(metadata(page = "https://example.com/photos/photo/123/")).isEmpty())
        assertTrue(WordPressPhotoText.parse(metadata(mime = "image/svg+xml")).isEmpty())
        assertEquals(listOf(small), WordPressPhotoText.parse(metadata(clearUrl = "https://example.com/private")).single().previews)
    }
    @Test fun tagLookupAndPhotosUseFixedPublicHostsWithoutCredentials() = runBlocking {
        val requests = mutableListOf<Request>()
        val gallery = WordPressPhotos(http { request ->
            requests += request
            200 to (if (request.url.encodedPath.endsWith("photo-tags")) """[{"id":156,"slug":"cat"}]""" else metadata()).jsonBody()
        }, "test")
        assertEquals("wp:123", gallery.search("猫咪").single().id)
        assertEquals(2, requests.size)
        assertEquals("cat", requests.first().url.queryParameter("slug"))
        assertEquals("156", requests.last().url.queryParameter("photo-tags"))
        assertNull(requests.last().url.queryParameter("search"))
        assertTrue(requests.all { it.url.host == "wordpress.org" && it.header("Authorization") == null })
    }
    @Test fun unknownTagsFallBackToFilteredSearchNotUnrelatedFirstImages() = runBlocking {
        val requests = mutableListOf<Request>()
        val gallery = WordPressPhotos(http { request ->
            requests += request
            200 to (if (request.url.encodedPath.endsWith("photo-tags")) "[]" else metadata("Delicate flowers")).jsonBody()
        }, "test")
        assertTrue(gallery.search("cat").isEmpty())
        assertEquals("cat", requests.last().url.queryParameter("search"))
        assertNull(WordPressPhotoText.tag("""[{"id":156,"slug":"delicate"}]""", "cat"))
    }
    @Test fun clearPreviewIsTriedFirstAndNetworkFailureFallsBackWithVisibleNote() = runBlocking {
        val requests = mutableListOf<Request>()
        val events = mutableListOf<String>()
        val gallery = WordPressPhotos(http { request ->
            requests += request
            if (request.url.toString() == clear) throw InterruptedIOException("private request details")
            200 to byteArrayOf(1, 2).toResponseBody("image/jpeg".toMediaType())
        }, "test", events::add)
        val result = gallery.download(candidate())
        assertEquals(listOf(clear, small), requests.map { it.url.toString() })
        assertEquals(WordPressPhotos.FALLBACK_NOTE, result.previewNote)
        assertTrue(events.any { it.contains("photo_preview_fallback") })
        assertFalse(events.any { it.contains("private") || it.contains("https://") })
        val delivered = PhotoDelivery.deliver(result, 1) { _, _ -> MessageImage("photo.jpg", 300, 225) }
        val saved = MessageImages.decode(MessageImages.encode(delivered.images)).single()
        assertEquals(PhotoLibrary.WordPress.title, saved.source)
        assertEquals(result.previewNote, saved.credit!!.previewNote)
        assertTrue(delivered.receipt.contains(WordPressPhotos.FALLBACK_NOTE))
    }
    @Test fun successfulClearPreviewDoesNotFetchSmallOrShowFallbackNote() = runBlocking {
        var calls = 0
        val result = WordPressPhotos(http { request ->
            calls++; assertEquals(clear, request.url.toString())
            200 to byteArrayOf(1).toResponseBody("image/webp".toMediaType())
        }, "test").download(candidate())
        assertEquals(1, calls)
        assertNull(result.previewNote)
    }
    @Test fun smallOnlyResultIsLabelledAndInvalidCandidateCannotReachNetwork() = runBlocking {
        var calls = 0
        val gallery = WordPressPhotos(http { _ ->
            calls++; 200 to byteArrayOf(1).toResponseBody("image/jpeg".toMediaType())
        }, "test")
        assertEquals(WordPressPhotos.SMALL_NOTE, gallery.download(candidate().copy(previews = listOf(small))).previewNote)
        for (bad in listOf(candidate().copy(id = "https://localhost"), candidate().copy(library = PhotoLibrary.Openverse),
            candidate().copy(previews = listOf("https://localhost/private")))) {
            try { gallery.download(bad); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, calls)
    }
    @Test fun redirectsRateLimitsAndOversizedImagesDoNotReportSuccess() {
        for (status in listOf(302, 429, 503)) {
            val gallery = WordPressPhotos(http { _ -> status to "".jsonBody() }, "test")
            failure { gallery.search("cat") }
            failure { gallery.download(candidate()) }
        }
        val oversized = WordPressPhotos(http { _ ->
            200 to ByteArray(McpImages.MAX_BYTES + 1).toResponseBody("image/jpeg".toMediaType())
        }, "test")
        assertTrue(failure { oversized.download(candidate()) }.result.contains("过大"))
    }
    @Test fun changingSelectionAfterSearchStillDownloadsFromOriginalLibrary() = runBlocking {
        var selected = PhotoLibrary.WordPress
        var wpDownloads = 0
        val wp = object : PhotoSource {
            override suspend fun search(query: String) = listOf(candidate())
            override suspend fun download(candidate: PhotoCandidate): DownloadedPhoto {
                wpDownloads++; return DownloadedPhoto(candidate, byteArrayOf(1), "image/jpeg")
            }
        }
        val ov = object : PhotoSource {
            override suspend fun search(query: String): List<PhotoCandidate> = error("Wrong library")
            override suspend fun download(candidate: PhotoCandidate): DownloadedPhoto = error("Wrong library")
        }
        val libraries = PhotoLibraries(ov, wp) { selected }
        val book = PhotoBook(libraries)
        book.run("search_photos", buildJsonObject { put("query", "cat") }, 1, 2)
        selected = PhotoLibrary.Openverse
        assertEquals("wp:123", book.run("share_photo", buildJsonObject { put("id", "wp:123") }, 1, 2).photo!!.candidate.id)
        assertEquals(1, wpDownloads)
        try { book.run("share_photo", buildJsonObject { put("id", "wp:123") }, 1, 3); fail() } catch (_: ToolFailure) { }
        assertEquals(1, wpDownloads)
        assertSame(wp, libraries.forLibrary(PhotoLibrary.WordPress))
    }
    @Test fun cancellationStopsDownloadWithoutStartingSmallFallback() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        val gallery = WordPressPhotos(http { _ ->
            calls++; started.countDown()
            release.await(2, TimeUnit.SECONDS)
            200 to byteArrayOf(1).toResponseBody("image/jpeg".toMediaType())
        }, "test")
        val job = launch { gallery.download(candidate()); fail("Cancelled operation succeeded") }
        while (started.count > 0) delay(5)
        job.cancel(CancellationException("stop")); job.join()
        release.countDown()
        assertEquals(1, calls)
    }
}
