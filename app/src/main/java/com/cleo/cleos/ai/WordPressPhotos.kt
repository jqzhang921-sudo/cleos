package com.cleo.cleos.ai

import com.cleo.cleos.data.PhotoCredit
import com.cleo.cleos.data.PhotoLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Search uses the saved choice; a cached result keeps its original provider when sharing. */
class PhotoLibraries(private val openverse: PhotoSource, private val wordpress: PhotoSource,
    private val selected: suspend () -> PhotoLibrary) : PhotoSource {
    fun forLibrary(library: PhotoLibrary) = when (library) {
        PhotoLibrary.Openverse -> openverse
        PhotoLibrary.WordPress -> wordpress
    }
    override suspend fun search(query: String) = forLibrary(selected()).search(query)
    override suspend fun download(candidate: PhotoCandidate) = forLibrary(candidate.library).download(candidate)
}

/** Fixed public API and resized CDN images only. No key, redirects, original files or VPN changes. */
class WordPressPhotos(http: OkHttpClient, private val version: String,
    private val diagnostic: (String) -> Unit = {}) : PhotoSource {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    override suspend fun search(query: String): List<PhotoCandidate> = withinBudget {
        val words = WordPressPhotoText.query(query)
        val slug = words.lowercase(Locale.ROOT).replace(Regex("\\s+"), "-")
        val tags = api("photo-tags").addQueryParameter("slug", slug)
            .addQueryParameter("per_page", "1").addQueryParameter("_fields", "id,slug").build()
        val tag = WordPressPhotoText.tag(json(tags.toString()), slug)
        val photos = api("photos").addQueryParameter("per_page", "12")
            .addQueryParameter("_embed", "author,wp:featuredmedia")
            .addQueryParameter("_fields", "id,link,photo-thumbnail-url,_links,_embedded")
        if (tag != null) photos.addQueryParameter("photo-tags", tag.toString())
        else photos.addQueryParameter("search", words)
        WordPressPhotoText.parse(json(photos.build().toString()), if (tag == null) words else null)
    }

    override suspend fun download(candidate: PhotoCandidate): DownloadedPhoto = withinBudget {
        require(candidate.library == PhotoLibrary.WordPress && WordPressPhotoText.validId(candidate.id))
        val urls = candidate.previews.take(2)
        require(urls.isNotEmpty() && urls.all { WordPressPhotoText.safePreview(it) != null })
        try {
            // Leave time for the small preview instead of spending the entire budget on a slow CDN image.
            val first = client.newBuilder().callTimeout(if (urls.size > 1) 12 else 20, TimeUnit.SECONDS).build()
            image(first, candidate, urls.first(), if (WordPressPhotoText.small(urls.first())) SMALL_NOTE else null)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (urls.size < 2 || (e !is IOException && e !is ToolFailure)) throw e
            runCatching { diagnostic("event=photo_preview_fallback reason=${e.javaClass.simpleName}") }
            image(client, candidate, urls.last(), FALLBACK_NOTE)
        }
    }

    private fun api(path: String) = "https://wordpress.org/photos/wp-json/wp/v2/$path".toHttpUrl().newBuilder()
    private fun request(url: String, accept: String) = Request.Builder().url(url)
        .header("Accept", accept).header("User-Agent", "Cleos/$version").build()
    private suspend fun json(url: String) = client.fetch(request(url, "application/json")) {
        runCatching { diagnostic("event=photo_search_http status=${it.code}") }
        ready(it)
        bounded(it, 768 * 1024).toString(Charsets.UTF_8)
    }
    private suspend fun image(http: OkHttpClient, candidate: PhotoCandidate, url: String, note: String?) =
        http.fetch(request(url, "image/jpeg, image/png, image/webp")) {
            runCatching { diagnostic("event=photo_download_http status=${it.code}") }
            ready(it)
            val mime = it.body.contentType()?.let { type -> "${type.type}/${type.subtype}" }
            if (mime !in setOf("image/jpeg", "image/png", "image/webp"))
                throw ToolFailure("图库返回的不是可显示的图片，没有发出照片。", "图片格式暂不支持")
            DownloadedPhoto(candidate, bounded(it, McpImages.MAX_BYTES), requireNotNull(mime), note)
        }
    private suspend fun <T : Any> withinBudget(action: suspend () -> T): T =
        withTimeoutOrNull(20_000) { action() }
            ?: throw ToolFailure("等待图库响应超时，没有发出照片。可以稍后再试。", "图库连接超时")
    private fun ready(response: Response) {
        if (response.code == 429) throw ToolFailure("公开图库暂时限流，请稍后再试；没有发出照片。", "图库暂时限流")
        if (!response.isSuccessful) throw ToolFailure("公开图库暂时不可用，请稍后再试；没有发出照片。", "图库暂时不可用")
    }
    private fun bounded(response: Response, maximum: Int): ByteArray {
        if (response.body.contentLength() > maximum) throw ToolFailure("图库内容过大，没有发出照片。", "内容过大")
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        response.body.byteStream().use { input ->
            while (true) {
                val n = input.read(buffer, 0, minOf(buffer.size, maximum + 1 - result.size()))
                if (n < 0) break
                if (result.size() + n > maximum) throw ToolFailure("图库内容过大，没有发出照片。", "内容过大")
                result.write(buffer, 0, n)
            }
        }
        return result.toByteArray()
    }
    companion object {
        const val SMALL_NOTE = "这张照片是小尺寸预览，可点来源查看原图。"
        const val FALLBACK_NOTE = "清晰预览未能加载，已显示小尺寸预览，可点来源查看原图。"
    }
}

object WordPressPhotoText {
    private val idPattern = Regex("wp:[1-9][0-9]{0,9}")
    private val previewPath = Regex("/20[0-9]{2}/(?:0[1-9]|1[0-2])/[A-Za-z0-9][A-Za-z0-9._-]*-([1-9][0-9]{0,3})x([1-9][0-9]{0,3})\\.(?:jpg|jpeg|png|webp)")
    private val aliases = mapOf("猫" to "cat", "猫咪" to "cat", "狗" to "dog", "狗狗" to "dog",
        "海滩" to "beach", "沙滩" to "beach", "美食" to "food", "食物" to "food", "日落" to "sunset")
    fun validId(id: String) = idPattern.matches(id)
    fun query(value: String) = aliases[value.trim()] ?: value.trim()
    fun safePreview(raw: String): String? {
        if (raw.length > 500) return null
        val url = raw.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.host != "pd.w.org" || url.port != 443 || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        val match = previewPath.matchEntire(url.encodedPath) ?: return null
        if (match.groupValues.drop(1).any { it.toInt() > 1440 }) return null
        return url.toString()
    }
    fun small(url: String): Boolean = url.toHttpUrlOrNull()?.encodedPath?.let {
        previewPath.matchEntire(it)?.groupValues?.drop(1)?.all { dimension -> dimension.toInt() <= 360 }
    } == true
    fun tag(raw: String, slug: String): Long? = (Json.parseToJsonElement(raw) as? JsonArray)
        ?.mapNotNull { it as? JsonObject }?.firstOrNull { it.text("slug") == slug }
        ?.get("id")?.let { (it as? JsonPrimitive)?.longOrNull }?.takeIf { it in 1..9_999_999_999L }

    fun parse(raw: String, query: String? = null): List<PhotoCandidate> {
        val rows = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        return rows.take(12).mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            val id = (item["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            if (!validId("wp:$id")) return@mapNotNull null
            val page = item.text("link").toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == "wordpress.org" &&
                it.port == 443 && it.username.isEmpty() && it.password.isEmpty() && it.query == null &&
                it.fragment == null && Regex("/photos/photo/[A-Za-z0-9]+/").matches(it.encodedPath) }
                ?: return@mapNotNull null
            val embedded = item["_embedded"] as? JsonObject ?: return@mapNotNull null
            val media = (embedded["wp:featuredmedia"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: return@mapNotNull null
            if (media.text("media_type") != "image" || media.text("mime_type") !in setOf("image/jpeg", "image/png", "image/webp")) return@mapNotNull null
            val caption = clean((media["caption"] as? JsonObject)?.text("rendered").orEmpty(), 400)
                .ifBlank { clean(media.text("alt_text"), 400) }
            if (query != null && !matches(caption, query)) return@mapNotNull null
            val sizes = ((media["media_details"] as? JsonObject)?.get("sizes") as? JsonObject)
            fun size(name: String) = (sizes?.get(name) as? JsonObject)?.text("source_url")?.let(::safePreview)
            val small = size("medium")?.takeIf(::small)
                ?: safePreview(item.text("photo-thumbnail-url"))?.takeIf(::small)
                ?: return@mapNotNull null
            val clear = listOfNotNull(size("medium_large"), size("large"))
                .firstOrNull { !small(it) }
            val author = (embedded["author"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it["code"] == null }?.text("name").orEmpty()
            PhotoCandidate("wp:$id", PhotoCredit(caption.take(180).ifBlank { "图库照片" },
                clean(author, 100).ifBlank { "作者信息暂缺" }, page.toString(), "CC0 1.0",
                "https://creativecommons.org/publicdomain/zero/1.0/"), PhotoLibrary.WordPress,
                listOfNotNull(clear, small).distinct())
        }.distinctBy { it.id }.take(6)
    }
    /** Whole words prevent cat from matching delicate/intricate in the generic search endpoint. */
    internal fun matches(caption: String, query: String): Boolean {
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(query.lowercase(Locale.ROOT)).map { it.value }.toList()
        return tokens.isNotEmpty() && tokens.all { token ->
            if (token.any { it.code > 127 }) caption.contains(token, ignoreCase = true)
            else Regex("(?<![\\p{L}\\p{N}])${Regex.escape(token)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(caption)
        }
    }
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    private fun clean(value: String, maximum: Int) = value.replace(Regex("<[^>]*>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("[\\p{Cc}\\s]+"), " ").trim().take(maximum)
}
