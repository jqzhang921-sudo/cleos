package com.cleo.cleos.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One related Commons picture, requested explicitly. Never carries model auth, cookies or redirects. */
class WorldPictureSource(private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false)
    .followSslRedirects(false).callTimeout(25, TimeUnit.SECONDS).build()) {
    data class Credit(val title: String, val author: String, val credit: String, val description: String,
        val license: String, val licenseUrl: String, val sourceUrl: String, val imageUrl: String, val mime: String)
    data class Picture(val credit: Credit, val bytes: ByteArray)

    suspend fun fetch(origin: String, allowed: suspend () -> Unit = {}): Picture? {
        allowed()
        val name = pageImage(get(pageUrl(origin), META_BYTES, "application/json").first, origin) ?: return null
        allowed()
        val credit = imageInfo(get(infoUrl(name), META_BYTES, "application/json").first, name) ?: return null
        allowed()
        val (bytes, mime) = get(credit.imageUrl.toHttpUrl(), IMAGE_BYTES, "image/jpeg, image/png, image/webp")
        require(mime == credit.mime) { "图片格式与来源不一致" }
        return Picture(credit, bytes)
    }

    private suspend fun get(url: HttpUrl, limit: Long, accept: String): Pair<ByteArray, String> {
        val call = client.newCall(Request.Builder().url(url).header("Accept", accept)
            .header("User-Agent", "Cleos-World/1.0 (https://github.com/jqzhang921-sudo/cleos)").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            check(it.isSuccessful) { "配图来源暂时不可用" }
                            val body = requireNotNull(it.body)
                            val mime = body.contentType()?.let { type -> "${type.type}/${type.subtype}" }.orEmpty()
                            require(if (accept == "application/json") mime == accept else mime in MIMES) { "配图来源格式不受支持" }
                            val stream = body.source(); stream.request(limit + 1)
                            require(stream.buffer.size <= limit) { "配图返回过大" }
                            stream.readByteArray() to mime
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }

    companion object {
        const val META_BYTES = 131_072L
        const val IMAGE_BYTES = 2_097_152L
        val MIMES = setOf("image/jpeg", "image/png", "image/webp")
        private val licenses = mapOf(
            "CC BY-SA 4.0" to "https://creativecommons.org/licenses/by-sa/4.0/",
            "CC BY-SA 3.0" to "https://creativecommons.org/licenses/by-sa/3.0/",
            "CC BY 4.0" to "https://creativecommons.org/licenses/by/4.0/",
            "CC BY 3.0" to "https://creativecommons.org/licenses/by/3.0/",
            "CC0" to "https://creativecommons.org/publicdomain/zero/1.0/",
        )
        fun validLicense(name: String, url: String) = licenses[name] == url
        private fun secure(raw: String, host: String): HttpUrl? {
            if (raw.length !in 1..2000 || raw != raw.trim() || raw.any { it.isISOControl() }) return null
            val u = raw.toHttpUrlOrNull() ?: return null
            return u.takeIf { it.isHttps && it.host == host && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null }
        }
        /** The API adds UTM tags to thumbnails. Remove only known tags, never arbitrary query parameters. */
        internal fun canonicalImage(raw: String): String? {
            if (raw.length !in 1..2000 || raw != raw.trim() || raw.any { it.isISOControl() }) return null
            val url = raw.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.port != 443 || url.host !in setOf("upload.wikimedia.org", "thumb.wikimedia.org") ||
                url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || !url.encodedPath.startsWith("/wikipedia/commons/")) return null
            if (url.queryParameterNames.any { it !in setOf("utm_source", "utm_campaign", "utm_content") } ||
                url.queryParameterNames.any { key -> url.queryParameterValues(key).size != 1 || url.queryParameter(key).orEmpty().length !in 1..100 }) return null
            return url.newBuilder().query(null).build().toString()
        }
        fun validImage(raw: String) = canonicalImage(raw) == raw
        fun validSource(raw: String) = secure(raw, "commons.wikimedia.org")?.let { it.pathSegments.firstOrNull() == "wiki" && it.pathSegments.drop(1).joinToString("/").startsWith("File:") } == true
        internal fun pageUrl(origin: String): HttpUrl {
            require(WorldTopicSearch.validUrl(origin))
            val title = origin.toHttpUrl().pathSegments.drop(1).joinToString("/")
            require(title.isNotBlank() && '|' !in title && title.none { it.isISOControl() })
            return "https://zh.wikipedia.org/w/api.php".toHttpUrl().newBuilder().addQueryParameter("action", "query")
                .addQueryParameter("titles", title).addQueryParameter("prop", "pageimages|info").addQueryParameter("inprop", "url")
                .addQueryParameter("piprop", "name").addQueryParameter("pilicense", "free")
                .addQueryParameter("format", "json").addQueryParameter("formatversion", "2").build()
        }
        internal fun infoUrl(name: String): HttpUrl {
            require(name.length in 1..240 && '|' !in name && name.none { it.isISOControl() })
            return "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder().addQueryParameter("action", "query")
                .addQueryParameter("titles", "File:$name").addQueryParameter("prop", "imageinfo")
                .addQueryParameter("iiprop", "url|extmetadata|mime|thumbmime|size").addQueryParameter("iiurlwidth", "960")
                .addQueryParameter("iiextmetadatafilter", "Artist|Credit|ImageDescription|LicenseShortName|LicenseUrl|Restrictions")
                .addQueryParameter("format", "json").addQueryParameter("formatversion", "2").build()
        }
        private fun page(bytes: ByteArray): JsonObject? {
            require(bytes.size <= META_BYTES)
            val raw = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            var depth = 0; var quoted = false; var escaped = false
            raw.forEach { ch -> if (quoted) { if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false }
                else when (ch) { '"' -> quoted = true; '{', '[' -> require(++depth <= 24); '}', ']' -> require(--depth >= 0) } }
            val root = Json.parseToJsonElement(raw).jsonObject
            require("error" !in root && "warnings" !in root)
            val rows = root["query"]?.jsonObject?.get("pages")?.jsonArray ?: error("配图来源没有完整返回")
            require(rows.size == 1)
            return rows.single().jsonObject.takeUnless { "missing" in it || "invalid" in it }
        }
        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        internal fun pageImage(bytes: ByteArray, origin: String): String? {
            val row = page(bytes) ?: return null
            require(row["ns"]?.jsonPrimitive?.intOrNull == 0 && row.text("fullurl") == origin)
            return row.text("pageimage").takeIf { it.isNotBlank() }?.also { infoUrl(it) }
        }
        internal fun imageInfo(bytes: ByteArray, name: String): Credit? {
            val row = page(bytes) ?: return null
            if (row.text("title").replace('_', ' ') != "File:$name".replace('_', ' ') || row["ns"]?.jsonPrimitive?.intOrNull != 6 || row.text("imagerepository") != "local") return null
            val info = (row["imageinfo"] as? JsonArray)?.singleOrNull()?.jsonObject ?: return null
            val meta = info["extmetadata"]?.jsonObject ?: return null
            fun field(key: String) = WorldOutside.plain(meta[key]?.jsonObject?.text("value").orEmpty()).replace(Regex("\\s+"), " ").trim()
            val license = field("LicenseShortName"); val licenseUrl = field("LicenseUrl").let { if (it.endsWith('/')) it else "$it/" }
            val author = field("Artist"); val credit = field("Credit"); val description = field("ImageDescription")
            if (!validLicense(license, licenseUrl) || author.isBlank() || author.length > 1000 || credit.length > 1000 || description.length > 2000 || field("Restrictions").isNotBlank()) return null
            val url = canonicalImage(info.text("thumburl").ifBlank { info.text("url") }) ?: return null
            val mime = info.text("thumbmime").ifBlank { info.text("mime") }
            val source = info.text("descriptionurl")
            if (!validImage(url) || !validSource(source) || source.toHttpUrl().pathSegments.drop(1).joinToString("/").replace('_', ' ') != "File:$name".replace('_', ' ') || mime !in MIMES) return null
            // A thumbnail is only a request; the actual decoder validates its real size and MIME too.
            return Credit(name, author, credit, description, license, licenseUrl, source, url, mime)
        }
    }
}
