package com.cleo.cleos.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicit user lookup only. Separate client: no model key, cookies, persona, text or redirect. */
class WorldBookCatalog {
    data class Book(val key: String, val title: String, val author: String) {
        init { require(key.matches(Regex("OL[0-9]+W")) && title.isNotBlank() && title.length <= 240 && author.length <= 240 &&
            (title + author).none { it.isISOControl() }) }
        val url: String get() = "https://openlibrary.org/works/$key"
    }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS).build()

    suspend fun search(title: String, author: String): List<Book> {
        require(title.isNotBlank() && title.length <= 120 && author.length <= 120)
        val url = "https://openlibrary.org/search.json".toHttpUrl().newBuilder()
            .addQueryParameter("title", title).apply { if (author.isNotBlank()) addQueryParameter("author", author) }
            .addQueryParameter("fields", "key,title,author_name").addQueryParameter("limit", "5").build()
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Cleos-Bookroom/1.0").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val books = response.use {
                            check(it.isSuccessful) { "书目查询暂时不可用" }
                            val source = requireNotNull(it.body).source()
                            source.request(MAX_RESPONSE + 1L)
                            check(source.buffer.size <= MAX_RESPONSE) { "书目返回过大" }
                            parse(source.readUtf8())
                        }
                        if (continuation.isActive) continuation.resume(books)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }

    companion object {
        private const val MAX_RESPONSE = 131_072L
        internal fun parse(raw: String): List<Book> {
            require(raw.length <= MAX_RESPONSE)
            val docs = Json.parseToJsonElement(raw).jsonObject["docs"] as? JsonArray ?: error("缺少书目列表")
            require(docs.size <= 5)
            fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return docs.mapNotNull { doc ->
                val obj = doc as? JsonObject ?: return@mapNotNull null
                val key = obj.text("key")?.removePrefix("/works/") ?: return@mapNotNull null
                val title = obj.text("title")?.trim() ?: return@mapNotNull null
                val names = (obj["author_name"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() }
                val author = names?.joinToString("、").orEmpty()
                runCatching { Book(key, title, author) }.getOrNull()
            }.distinctBy { it.key }
        }
    }
}
