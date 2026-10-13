package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.CompanionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class PhotosTest {
    private val id = "145e0b37-0577-416b-8d05-27c0b0bdde46"
    private val credit = PhotoCredit("Cat", "Photographer", "https://www.flickr.com/photos/example/1", "CC BY 2.0", "https://creativecommons.org/licenses/by/2.0/")
    private val candidate = PhotoCandidate(id, credit)
    private var now = 10_000L
    private var searched = 0
    private var downloaded = 0
    private val source = object : PhotoSource {
        override suspend fun search(query: String): List<PhotoCandidate> { searched++; return listOf(candidate) }
        override suspend fun download(candidate: PhotoCandidate): DownloadedPhoto {
            downloaded++; return DownloadedPhoto(candidate, byteArrayOf(1, 2), "image/webp")
        }
    }
    private val book = PhotoBook(source) { now }
    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun search(conversation: Long = 1, companion: Long = 2) = runBlocking {
        book.run("search_photos", args("""{"query":"cat"}"""), conversation, companion)
    }
    private fun share(conversation: Long = 1, companion: Long = 2, value: String = id) = runBlocking {
        book.run("share_photo", buildJsonObject { put("id", value) }, conversation, companion)
    }
    private fun failure(block: () -> Unit): ToolFailure {
        try { block(); fail("expected ToolFailure") } catch (e: ToolFailure) { return e }
        error("unreachable")
    }
    private fun metadata(change: Map<String, JsonElement> = emptyMap()): String {
        val item = buildJsonObject {
            put("id", id); put("title", "Cat"); put("creator", "Photographer")
            put("category", "photograph"); put("mature", false)
            put("license", "by"); put("license_version", "2.0")
            put("license_url", credit.licenseUrl); put("foreign_landing_url", credit.pageUrl)
        }
        return buildJsonObject { putJsonArray("results") { add(JsonObject(item + change)) } }.toString()
    }

    @Test fun metadataPreservesAuthorOriginalPageAndLicense() {
        assertEquals(candidate, OpenversePhotoText.parse(metadata()).single())
    }
    @Test fun unsafeOrUnlicensedResultsAreNotOffered() {
        for (change in listOf(
            mapOf("id" to JsonPrimitive("../private")),
            mapOf("category" to JsonPrimitive("illustration")),
            mapOf("mature" to JsonPrimitive(true)),
            mapOf("mature" to JsonNull),
            mapOf("license" to JsonPrimitive("by-nc")),
            mapOf("foreign_landing_url" to JsonPrimitive("javascript:alert(1)")),
            mapOf("foreign_landing_url" to JsonPrimitive("https://user:password@flickr.com/a")),
            mapOf("license_url" to JsonPrimitive("https://example.com/licenses/by/2.0/")),
            mapOf("license_url" to JsonPrimitive("https://creativecommons.org/licenses/by-sa/2.0/")),
        )) assertTrue(OpenversePhotoText.parse(metadata(change)).isEmpty())
    }
    @Test fun titlesAreBoundedAndResultsDeduplicated() {
        val row = Json.parseToJsonElement(metadata(mapOf("title" to JsonPrimitive("<b>Cat</b>\n" + "x".repeat(300))))).jsonObject["results"]!!.jsonArray.single()
        val result = OpenversePhotoText.parse(buildJsonObject { putJsonArray("results") { repeat(20) { add(row) } } }.toString())
        assertEquals(1, result.size)
        assertEquals(180, result.single().credit.title.length)
        assertFalse(result.single().credit.title.contains("<b>"))
    }
    @Test fun searchIsNotASendAndShareNeedsCurrentScopedResult() {
        val found = search()
        assertNull(found.photo)
        assertTrue(found.result.contains(id))
        assertTrue(found.result.contains("不代表你看过图片"))
        failure { share(conversation = 3) }
        failure { share(companion = 4) }
        assertEquals(0, downloaded)
        assertEquals(candidate, share().photo!!.candidate)
        assertEquals(1, downloaded)
    }
    @Test fun expiresWithoutSavingOrDownloading() {
        search(); now += 600_001
        assertTrue(failure { share() }.result.contains("过期"))
        assertEquals(0, downloaded)
    }
    @Test fun arbitraryUrlsAndInventedIdsCannotReachNetwork() {
        search()
        failure { share(value = "https://127.0.0.1/private") }
        failure { share(value = "00000000-0000-0000-0000-000000000000") }
        assertEquals(0, downloaded)
    }
    @Test fun queriesAreBoundedAndFastRepeatedSearchesDoNotHitNetwork() {
        for (query in listOf("", "x".repeat(121), "cat\nprivate")) failure {
            runBlocking { book.run("search_photos", buildJsonObject { put("query", query) }, 1, 2) }
        }
        assertEquals(0, searched)
        search(); failure { search() }; assertEquals(1, searched)
        now += 2000; search(); assertEquals(2, searched)
    }
    @Test fun cacheHasGlobalBoundAcrossConversations() {
        repeat(121) { search(conversation = it.toLong()); now += 2000 }
        failure { share(conversation = 0) }
        assertNotNull(share(conversation = 120).photo)
    }
    @Test fun networkFailuresDoNotClaimAPhotoWasSent() {
        val unavailable = PhotoBook(object : PhotoSource {
            override suspend fun search(query: String): List<PhotoCandidate> = error("server detail")
            override suspend fun download(candidate: PhotoCandidate): DownloadedPhoto = error("unused")
        })
        val error = failure { runBlocking { unavailable.run("search_photos", args("""{"query":"cat"}"""), 1, 2) } }
        assertTrue(error.result.contains("没有发出"))
        assertFalse(error.result.contains("server detail"))
    }
    @Test fun cancellationIsNotReportedAsNetworkFailure() {
        val cancelling = PhotoBook(object : PhotoSource {
            override suspend fun search(query: String): List<PhotoCandidate> = throw CancellationException("stop")
            override suspend fun download(candidate: PhotoCandidate): DownloadedPhoto = error("unused")
        })
        try { runBlocking { cancelling.run("search_photos", args("""{"query":"cat"}"""), 1, 2) }; fail() }
        catch (_: CancellationException) { }
    }

    private fun http(body: ResponseBody, code: Int = 200, requests: MutableList<Request> = mutableListOf()) =
        OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body).build()
        }.build()
    @Test fun requestsUseFixedGalleryHostFiltersAndProxyNotMetadataUrl() = runBlocking {
        val requests = mutableListOf<Request>()
        val gallery = OpenversePhotos(http(metadata().toResponseBody("application/json".toMediaType()), requests = requests), "test")
        assertEquals(candidate, gallery.search("cat & coast").single())
        val query = requests.single().url
        assertEquals("api.openverse.org", query.host)
        assertEquals("cat & coast", query.queryParameter("q"))
        assertEquals("false", query.queryParameter("mature"))
        assertEquals("photograph", query.queryParameter("category"))
        assertEquals("cc0,pdm,by,by-sa", query.queryParameter("license"))
        assertNull(requests.single().header("Authorization"))
        requests.clear()
        val images = OpenversePhotos(http(byteArrayOf(1, 2).toResponseBody("image/webp".toMediaType()), requests = requests), "test")
        val photo = images.download(candidate)
        assertEquals("/v1/images/$id/thumb/", requests.single().url.encodedPath)
        assertEquals("api.openverse.org", requests.single().url.host)
        assertArrayEquals(byteArrayOf(1, 2), photo.bytes)
    }
    @Test fun rateLimitRedirectAndUnexpectedImageFormatsAreRejected() {
        for (status in listOf(302, 429, 503)) {
            val api = OpenversePhotos(http("".toResponseBody(), status), "test")
            failure { runBlocking { api.search("cat") } }
        }
        val svg = OpenversePhotos(http("<svg/>".toResponseBody("image/svg+xml".toMediaType())), "test")
        failure { runBlocking { svg.download(candidate) } }
    }
    @Test fun chunkedOversizedImageIsRejectedEvenWithoutContentLength() {
        val body = object : ResponseBody() {
            override fun contentType() = "image/webp".toMediaType()
            override fun contentLength() = -1L
            override fun source() = Buffer().write(ByteArray(McpImages.MAX_BYTES + 1))
        }
        val api = OpenversePhotos(http(body), "test")
        assertTrue(failure { runBlocking { api.download(candidate) } }.result.contains("过大"))
    }
    @Test fun deliveryIsConfirmedOnlyAfterSaveAndKeepsCreditThroughStorage() = runBlocking {
        val photo = DownloadedPhoto(candidate, byteArrayOf(1), "image/webp")
        var saved = false
        val delivery = PhotoDelivery.deliver(photo, 1) { _, _ -> saved = true; MessageImage("photo.jpg", 100, 80) }
        assertTrue(saved)
        assertTrue(delivery.receipt.contains("已在聊天中展示"))
        val stored = MessageImages.decode(MessageImages.encode(delivery.images)).single()
        assertEquals(credit, stored.credit)
        assertEquals("Openverse", stored.source)
        assertNull(MessageImages.decode("""[{"file":"old.jpg","width":1,"height":2}]""").single().credit)
    }
    @Test fun failedStorageOrExhaustedReplyBudgetNeverConfirmsDelivery() = runBlocking {
        val photo = DownloadedPhoto(candidate, byteArrayOf(1), "image/webp")
        val broken = PhotoDelivery.deliver(photo, 1) { _, _ -> error("bad decoder") }
        assertTrue(broken.images.isEmpty())
        assertTrue(broken.receipt.contains("没有发出图片"))
        val capped = PhotoDelivery.deliver(photo, 0) { _, _ -> error("must not save") }
        assertTrue(capped.images.isEmpty())
        assertFalse(capped.receipt.contains("已在聊天中展示"))
    }
    @Test fun photoToolsAreOptInAndPromptDoesNotInventImageVision() {
        assertFalse(ToolGroup.Photos in AppSettings().tools)
        assertFalse(ToolSpecs.offered(AppSettings().tools).any { it.name == "share_photo" })
        assertEquals(listOf("search_photos", "share_photo"), ToolSpecs.offered(setOf(ToolGroup.Photos)).map { it.name })
        val ta = CompanionEntity(id = 1, name = "TA", persona = "", apiBaseUrl = "", apiModel = "", createdAt = 0)
        val enabled = Prompt.system(AppSettings(), ta, setOf(ToolGroup.Photos))
        assertTrue(enabled.contains("元数据") || enabled.contains("标题不代表你看过画面"))
        assertTrue(enabled.contains("保留作者"))
        assertFalse(Prompt.system(AppSettings(), ta).contains("search_photos"))
        assertFalse(ToolGroup.Photos in decodeTools("Feed:on,FeedActions:on"))
        assertTrue(ToolGroup.Photos in decodeTools(encodeTools(setOf(ToolGroup.Photos))))
    }
}
