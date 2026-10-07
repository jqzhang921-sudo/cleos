package com.cleo.cleos.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An emoji the person stuck on one of the TA's messages from its long-press menu, and when. The
 * TA hears of it in the person's first message after that (Prompt): it happened between the two,
 * and a reaction on its own doesn't ask for an answer.
 */
@Serializable
data class MessageReaction(val emoji: String, val at: Long)

object MessageReactions {
    private val json = Json { ignoreUnknownKeys = true }

    /** What the long-press menu offers: faces phones have drawn for years, so none shows as a box. */
    val OFFERED = listOf("❤️", "😘", "😂", "🥺", "😭", "👍", "🤗")

    val ALL = (OFFERED + listOf("😼", "😻", "😹", "🐱", "🐰", "🐶", "👀", "🙈", "🙉", "🙊",
        "😊", "🥰", "😍", "😎", "🤔", "😮", "😱", "🤯", "😴", "🤤", "🥲", "😅", "🤣", "🙃",
        "😏", "😤", "😡", "🤡", "👻", "💀", "👎", "👏", "🙌", "🙏", "👌", "✌️", "🤝", "💪",
        "💋", "💔", "💕", "💖", "💯", "🔥", "✨", "🎉", "🎂", "🌹", "🌸", "🍀", "🌙", "☀️",
        "🌈", "⭐", "☕", "🍵", "🍓", "🍰", "🫶", "🫂", "🫡", "🫠")).distinct()

    fun encode(list: List<MessageReaction>): String? = if (list.isEmpty()) null else json.encodeToString(list)

    fun decode(raw: String?): List<MessageReaction> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString<List<MessageReaction>>(raw) }.getOrDefault(emptyList())

    /** [list] with [emoji] taken off when it is on, else put on at [at]: each one once. */
    fun toggle(list: List<MessageReaction>, emoji: String, at: Long): List<MessageReaction> =
        if (list.any { it.emoji == emoji }) list.filterNot { it.emoji == emoji } else list + MessageReaction(emoji, at)
}
