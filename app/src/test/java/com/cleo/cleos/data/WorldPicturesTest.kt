package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WorldPicturesTest {
    private val origin = "https://zh.wikipedia.org/wiki/Sea"
    private val fileUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Sea.jpg/960px-Sea.jpg"
    private val sourceUrl = "https://commons.wikimedia.org/wiki/File:Sea.jpg"
    private val license = "https://creativecommons.org/licenses/by-sa/4.0/"
    private fun page(vararg values: Pair<String, JsonElement>) = buildJsonObject { putJsonObject("query") { putJsonArray("pages") { add(buildJsonObject { values.forEach { put(it.first, it.second) } }) } } }.toString().toByteArray()
    private fun named() = page("ns" to JsonPrimitive(0), "fullurl" to JsonPrimitive(origin), "pageimage" to JsonPrimitive("Sea.jpg"))
    private fun info(author: String = "<a href='https://bad.invalid'>A photographer</a>", short: String = "CC BY-SA 4.0", url: String = license,
        image: String = fileUrl, source: String = sourceUrl, restriction: String = "", mime: String = "image/jpeg", name: String = "File:Sea.jpg") = page(
        "ns" to JsonPrimitive(6), "title" to JsonPrimitive(name), "imagerepository" to JsonPrimitive("local"), "imageinfo" to buildJsonArray { add(buildJsonObject {
            put("thumburl", image); put("thumbmime", mime); put("descriptionurl", source)
            putJsonObject("extmetadata") { mapOf("Artist" to author, "LicenseShortName" to short, "LicenseUrl" to url,
                "ImageDescription" to "<p>A sea view.</p><script>not a caption</script>", "Credit" to "Own work", "Restrictions" to restriction).forEach { (k,v) -> putJsonObject(k) { put("value", v) } } }
        }) })
    private fun bad(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }

    @Test fun revokedPermissionStopsBeforeEachMetadataOrPixelRequest() = runBlocking {
        for (boundary in 1..3) {
            var requests = 0; var checks = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body((if (requests == 1) named() else info()).toResponseBody("application/json".toMediaType())).build()
            }.build()
            try {
                try { WorldPictureSource(client).fetch(origin) { if (++checks == boundary) error("permission revoked") }; fail("Expected revocation") }
                catch (e: IllegalStateException) { assertEquals("permission revoked", e.message) }
                assertEquals(boundary - 1, requests)
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    @Test fun apiRequestsAreBoundToOneActualArticleAndOneFreeFile() {
        val page = WorldPictureSource.pageUrl(origin)
        assertEquals("Sea", page.queryParameter("titles")); assertEquals("free", page.queryParameter("pilicense"))
        assertEquals("name", page.queryParameter("piprop")); assertNull(page.queryParameter("redirects"))
        val info = WorldPictureSource.infoUrl("Sea.jpg")
        assertEquals("commons.wikimedia.org", info.host); assertEquals("File:Sea.jpg", info.queryParameter("titles"))
        assertEquals("960", info.queryParameter("iiurlwidth"))
        listOf("https://localhost/wiki/Sea", "http://zh.wikipedia.org/wiki/Sea", "https://zh.wikipedia.org/wiki/A%7CB").forEach { bad { WorldPictureSource.pageUrl(it) } }
        bad { WorldPictureSource.infoUrl("Sea.jpg|File:Other.jpg") }
        assertEquals("Sea.jpg", WorldPictureSource.pageImage(named(), origin))
        bad { WorldPictureSource.pageImage(named(), origin + "2") }
    }
    @Test fun metadataKeepsCreatorCreditAndLicenseButNeverMarkupOrUploaderAsAuthor() {
        val c = WorldPictureSource.imageInfo(info(), "Sea.jpg")!!
        assertEquals("A photographer", c.author); assertEquals("Own work", c.credit); assertEquals("A sea view.", c.description)
        assertEquals(license, c.licenseUrl); assertEquals(sourceUrl, c.sourceUrl); assertEquals(fileUrl, c.imageUrl)
        assertNull(WorldPictureSource.imageInfo(info(author = ""), "Sea.jpg"))
        assertNull(WorldPictureSource.imageInfo(info(restriction = "trademarked"), "Sea.jpg"))
    }
    @Test fun unrecognizedLicensesForeignPathsAndNonRasterImagesAreNotDownloaded() {
        for ((n,u) in listOf("Fair use" to "", "CC BY-SA 4.0" to "https://evil.invalid/", "Public domain" to "", "CC BY-SA 4.0" to "http://creativecommons.org/licenses/by-sa/4.0/"))
            assertNull(WorldPictureSource.imageInfo(info(short = n, url = u), "Sea.jpg"))
        assertNull(WorldPictureSource.imageInfo(info(mime = "image/svg+xml"), "Sea.jpg"))
        assertNull(WorldPictureSource.imageInfo(info(image = "https://upload.wikimedia.org/wikipedia/en/a/Sea.jpg"), "Sea.jpg"))
        assertNull(WorldPictureSource.imageInfo(info(source = "https://commons.wikimedia.org/wiki/File:Other.jpg"), "Sea.jpg"))
        assertNull(WorldPictureSource.imageInfo(info(name = "File:Other.jpg"), "Sea.jpg"))
        for (url in listOf("http://upload.wikimedia.org/wikipedia/commons/a.jpg", "$fileUrl?token=x", "https://user:pw@upload.wikimedia.org/wikipedia/commons/a.jpg", "https://127.0.0.1/wikipedia/commons/a.jpg")) assertFalse(WorldPictureSource.validImage(url))
    }
    @Test fun actualCommonsThumbnailHostAndTrackingTagsAreCanonicalizedWithoutRelaxingOtherQueries() {
        val thumb = fileUrl.replace("upload.wikimedia.org", "thumb.wikimedia.org")
        val tagged = "$thumb?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=thumbnail"
        assertEquals(thumb, WorldPictureSource.canonicalImage(tagged))
        assertEquals(thumb, WorldPictureSource.imageInfo(info(image = tagged), "Sea.jpg")!!.imageUrl)
        assertFalse(WorldPictureSource.validImage(tagged)); assertTrue(WorldPictureSource.validImage(thumb))
        for (raw in listOf("$thumb?url=https://evil.invalid/", "$tagged&token=x", "$thumb?utm_content=a&utm_content=b",
            thumb.replace("thumb.wikimedia.org", "thumb.wikimedia.org.evil.invalid"), "$thumb#fragment")) assertNull(WorldPictureSource.canonicalImage(raw))
    }
    @Test fun noPictureIsDifferentFromBrokenMetadataOrOversizedJson() {
        assertNull(WorldPictureSource.pageImage(page("ns" to JsonPrimitive(0), "fullurl" to JsonPrimitive(origin)), origin))
        assertNull(WorldPictureSource.pageImage(page("missing" to JsonPrimitive(true)), origin))
        for (bytes in listOf("{}".toByteArray(), "{\"error\":{}}".toByteArray(), ("[".repeat(50)+"]".repeat(50)).toByteArray(), ByteArray(131073))) bad { WorldPictureSource.pageImage(bytes, origin) }
    }
    @Test fun networkRequestsHaveNoCredentialsAndUseAtMostThreePublicRequests() = runBlocking {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request(); requests += r
            val bytes = when(requests.size) { 1 -> named(); 2 -> info(); else -> byteArrayOf(1,2,3) }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(bytes.toResponseBody((if (requests.size < 3) "application/json" else "image/jpeg").toMediaType())).build()
        }.build()
        try {
            assertArrayEquals(byteArrayOf(1,2,3), WorldPictureSource(http).fetch(origin)!!.bytes)
            assertEquals(listOf("zh.wikipedia.org", "commons.wikimedia.org", "upload.wikimedia.org"), requests.map { it.url.host })
            requests.forEach { assertNull(it.header("Authorization")); assertNull(it.header("Cookie")); assertNull(it.header("Referer")) }
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }
    @Test fun badStatusMimeOversizeAndNoLicenseDoNotFallBackToArbitraryDownloads() = runBlocking {
        for (mode in listOf("redirect", "large", "mime", "license", "no-image")) {
            var count = 0
            val http = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                count++
                val bytes = when { mode == "no-image" -> page("ns" to JsonPrimitive(0), "fullurl" to JsonPrimitive(origin))
                    count == 1 -> named(); count == 2 -> info(short = if (mode == "license") "Fair use" else "CC BY-SA 4.0")
                    mode == "large" -> ByteArray(2_097_153); else -> byteArrayOf(1) }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if(mode == "redirect")302 else 200).message("fixture")
                    .header("Location", "https://bad.invalid/").body(bytes.toResponseBody((if(count < 3)"application/json" else if(mode == "mime")"image/png" else "image/jpeg").toMediaType())).build()
            }.build()
            try {
                if(mode in listOf("license","no-image")) assertNull(WorldPictureSource(http).fetch(origin))
                else try { WorldPictureSource(http).fetch(origin); fail(mode) } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
                assertEquals(if(mode == "redirect" || mode == "no-image")1 else if(mode == "license")2 else 3, count)
            } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
        }
    }
    private val article = WorldArticleEntity(1, 5, "Sea", "维基百科", "合成摘要", WorldReading.fingerprint("合成摘要"), 1, space = "outside", originUrl = origin, searchQuery = "Sea")
    private val picture = WorldPictureEntity(1, 5, article.fingerprint, "world-00000000-0000-0000-0000-000000000001.jpg", 960, 640, "Sea.jpg", "A photographer", "Own work", "Sea", "CC BY-SA 4.0", license, sourceUrl, fileUrl, 2, "0".repeat(64))
    @Test fun backupChecksArticleOwnerVersionFilesAndLicenseBeforeRestoring() {
        WorldPictureData.validate(listOf(article), listOf(picture)); assertEquals(0, article.readOffset)
        listOf(picture.copy(companionId = 6), picture.copy(fingerprint = "wrong"), picture.copy(file = "../other.jpg"), picture.copy(width = 961),
            picture.copy(license = "Fair use"), picture.copy(author = "bad\nauthor"), picture.copy(fetchedAt = 0)).forEach { p -> bad { WorldPictureData.validate(listOf(article), listOf(p)) } }
        bad { WorldPictureData.validate(listOf(article.copy(space = "study")), listOf(picture)) }
        bad { WorldPictureData.validate(listOf(article), listOf(picture,picture)) }
        assertEquals(picture, Json.decodeFromString<WorldPictureEntity>(Json.encodeToString(picture)))
        assertTrue(BackupSummary(1,0,0,0,0,0,1,worldPictures=1).toString().contains("1 张窗外配图"))
    }
    @Test fun epubBookmarkPreviewIsCompactButKeepsRealPositionAndSource() {
        val original = "〔EPUB 阅读章节 1〕\n\n海边\n\n第一段。\n\n〔EPUB 阅读章节 2〕\n\n第二段。"
        assertEquals("海边 第一段。", WorldShelfData.preview(original, 0))
        assertEquals("第一段。", WorldShelfData.preview(original, original.indexOf("第一段")))
        assertTrue(original.startsWith("〔EPUB 阅读章节 1〕"))
    }
}
