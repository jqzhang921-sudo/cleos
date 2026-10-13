package com.cleo.cleos.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A picture sent in the chat. [file] is a name inside ImageStore. */
@Serializable
data class MessageImage(val file: String, val width: Int, val height: Int, val source: String? = null, val credit: PhotoCredit? = null)

/** Kept with the downloaded file through history, backup and restore. */
@Serializable
data class PhotoCredit(val title: String, val creator: String, val pageUrl: String, val license: String, val licenseUrl: String,
    val previewNote: String? = null)

object MessageImages {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(images: List<MessageImage>): String? = if (images.isEmpty()) null else json.encodeToString(images)

    fun decode(raw: String?): List<MessageImage> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString<List<MessageImage>>(raw) }.getOrDefault(emptyList())
}
