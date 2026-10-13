package com.cleo.cleos.ai

import com.cleo.cleos.data.PhotoCredit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class PhotosDiagnosticsTest {
    private val candidate = PhotoCandidate("145e0b37-0577-416b-8d05-27c0b0bdde46",
        PhotoCredit("Cat", "Photographer", "https://example.org/cat", "CC0 1.0", "https://creativecommons.org/publicdomain/zero/1.0/"))
    private val image = DownloadedPhoto(candidate, byteArrayOf(1), "image/webp")
    private val args = buildJsonObject { put("query", "private-search") }
    private fun source(search: suspend () -> List<PhotoCandidate> = { listOf(candidate) },
        download: suspend () -> DownloadedPhoto = { image }) = object : PhotoSource {
        override suspend fun search(query: String) = search()
        override suspend fun download(candidate: PhotoCandidate) = download()
    }
    private suspend fun failed(block: suspend () -> Unit): ToolFailure {
        try { block(); fail("Expected failure") } catch (e: ToolFailure) { return e }
        error("unreachable")
    }

    @Test fun failureCategoriesDoNotDiscloseExceptionProseOrBlameModelCapabilities() {
        for ((error, note) in listOf(
            UnknownHostException("private-detail") to "图库地址解析失败",
            SSLException("private-detail") to "图库安全连接失败",
            SocketTimeoutException("private-detail") to "图库连接超时",
            ConnectException("private-detail") to "手机未连上图库",
            NoRouteToHostException("private-detail") to "手机未连上图库",
            IOException("private-detail") to "图库连接中断",
            SerializationException("private-detail") to "图库内容解析失败",
            IllegalArgumentException("private-detail") to "图库内容解析失败",
            IllegalStateException("private-detail") to "找图处理出错",
        )) {
            val failure = PhotoFailures.of(error)
            assertEquals(note, failure.note)
            assertTrue(failure.result.contains("没有发出"))
            assertFalse(failure.result.contains("private-detail"))
        }
    }

    @Test fun searchFailureKeepsExceptionTypeWithoutSearchText() = runBlocking {
        val records = mutableListOf<String>()
        val book = PhotoBook(source(search = { throw UnknownHostException("private-detail") }), diagnostic = records::add)
        assertEquals("图库地址解析失败", failed { book.run("search_photos", args, 1, 2) }.note)
        assertEquals(listOf("event=photo_search_failed failed=true reason=UnknownHostException"), records)
        assertFalse(records.toString().contains("private-"))
    }

    @Test fun downloadFailureIsDistinguishedFromSuccessfulSearch() = runBlocking {
        val records = mutableListOf<String>()
        val book = PhotoBook(source(download = { throw SocketTimeoutException("private-detail") }), diagnostic = records::add)
        book.run("search_photos", args, 1, 2)
        assertEquals("图库连接超时", failed { book.run("share_photo", buildJsonObject { put("id", candidate.id) }, 1, 2) }.note)
        assertEquals(listOf("event=photo_search_ok tools=1", "event=photo_share_failed failed=true reason=SocketTimeoutException"), records)
    }

    @Test fun brokenDiagnosticsDoNotPreventSearchOrSharing() = runBlocking {
        val book = PhotoBook(source(), diagnostic = { error("storage unavailable") })
        book.run("search_photos", args, 1, 2)
        assertEquals(image, book.run("share_photo", buildJsonObject { put("id", candidate.id) }, 1, 2).photo)
    }

    @Test fun httpFailureRetainsStatusWithoutQueryOrBody() = runBlocking {
        val records = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(429).message("limited").body("private-response".toResponseBody()).build()
        }.build()
        val gallery = OpenversePhotos(http, "test", records::add)
        assertEquals("图库暂时限流", failed { gallery.search("private-search") }.note)
        assertEquals(listOf("event=photo_search_http status=429"), records)
    }

    @Test fun invalidResponseIsReportedAsParsingFailureWithSuccessfulHttpStatus() = runBlocking {
        val records = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("ok").body("{bad json".toResponseBody()).build()
        }.build()
        val book = PhotoBook(OpenversePhotos(http, "test", records::add), diagnostic = records::add)
        assertEquals("图库内容解析失败", failed { book.run("search_photos", args, 1, 2) }.note)
        assertEquals("event=photo_search_http status=200", records.first())
        assertTrue(records.last().contains("photo_search_failed"))
        assertFalse(records.toString().contains("bad json"))
    }

    @Test fun testChecksSearchDownloadAndActualDecoderInOrder() = runBlocking {
        val calls = mutableListOf<String>()
        val gallery = object : PhotoSource {
            override suspend fun search(query: String): List<PhotoCandidate> { calls += query; return listOf(candidate) }
            override suspend fun download(candidate: PhotoCandidate): DownloadedPhoto { calls += "download"; return image }
        }
        val result = PhotoCheck.run(gallery) { assertEquals(image, it); calls += "decode"; 640 to 480 }
        assertEquals(listOf("cat", "download", "decode"), calls)
        assertTrue(result.contains("图库测试通过"))
        assertTrue(result.contains("640 × 480"))
        assertTrue(result.contains("没有发送聊天"))
    }

    @Test fun emptySearchDoesNotDownloadOrClaimDecodeSuccess() = runBlocking {
        val result = PhotoCheck.run(source(search = { emptyList() }, download = { error("must not download") })) {
            error("must not decode")
        }
        assertTrue(result.contains("连接成功"))
        assertFalse(result.contains("图库测试通过"))
    }

    @Test fun decodeFailureKeepsCauseForSafeDiagnosisAndDoesNotReportSuccess() = runBlocking {
        val decoderError = IllegalArgumentException("private-detail")
        val failure = failed { PhotoCheck.run(source()) { throw decoderError } }
        assertEquals("图库图片无法解码", failure.note)
        assertSame(decoderError, failure.cause)
        assertFalse(failure.result.contains("private-detail"))
    }

    @Test fun testIdentifiesWhetherSearchOrDownloadTimedOut() = runBlocking {
        val records = mutableListOf<String>()
        val search = failed { PhotoCheck.run(source(search = { throw SocketTimeoutException() }), progress = records::add) { 1 to 1 } }
        assertEquals("查询图库：图库连接超时", search.note)
        assertEquals(listOf("正在查询图库…"), records)
        records.clear()
        val download = failed { PhotoCheck.run(source(download = { throw SocketTimeoutException() }), progress = records::add) { 1 to 1 } }
        assertEquals("下载照片：图库连接超时", download.note)
        assertEquals(listOf("正在查询图库…", "正在下载照片…"), records)
        assertTrue(download.cause is SocketTimeoutException)
    }

    @Test fun cancellingTestIsNeverPresentedAsGalleryFailure() = runBlocking {
        for (duringDecode in listOf(false, true)) {
            val cancel = CancellationException("stop")
            try {
                PhotoCheck.run(source(search = { if (!duringDecode) throw cancel; listOf(candidate) })) { throw cancel }
                fail("Expected cancellation")
            } catch (e: CancellationException) { assertSame(cancel, e) }
        }
    }
}
