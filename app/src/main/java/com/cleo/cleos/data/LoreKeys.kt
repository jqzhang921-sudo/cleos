package com.cleo.cleos.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The words that bring one 设定 entry up, as the entry keeps them. The person writes them in
 * one line, the way they would say them out loud ("星历、旧历、历法"), and the model reads
 * them as a list.
 */
object LoreKeys {
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(String.serializer())

    /** Everything a line can be separated by, in Chinese or with a keyboard. */
    private val SPLIT = Regex("[、,，;；/|]+")

    fun decode(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(list, raw) }.getOrDefault(emptyList())

    fun encode(keys: List<String>): String = json.encodeToString(list, keys.filter { it.isNotBlank() })

    /** One line into the words in it, each short and without repeats: what the page saves. */
    fun parse(line: String): List<String> = line.split(SPLIT)
        .map { it.trim().take(KEY_MAX) }
        .filter { it.isNotEmpty() }
        .distinct()

    /** The line the page shows for them again. */
    fun line(keys: List<String>): String = keys.joinToString("、")

    /**
     * How long one word may be. A word is what someone types into a search box, so a whole
     * sentence would never match; the long ones are the model's, and those it writes itself.
     */
    const val KEY_MAX = 40
}
