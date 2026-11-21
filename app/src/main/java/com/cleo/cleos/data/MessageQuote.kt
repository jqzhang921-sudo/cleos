package com.cleo.cleos.data

import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.db.WorldAlbumLookEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The message another one answers: its id, to find it in the chat, whose it was, and its
 * words as they were. The words are kept, not looked up: the one quoted can be deleted, or
 * be long out of what the model is sent, and the quote should still say what it said.
 */
@Serializable
data class MessageQuote(val id: Long, val role: String, val text: String, val fromAlbum: Boolean = false)

object MessageQuotes {
    private val json = Json { ignoreUnknownKeys = true }

    /** How much of a quoted message is kept: more than the chat shows, enough for the model. */
    const val MAX_TEXT = 200

    fun encode(quote: MessageQuote): String = json.encodeToString(quote)

    fun decode(raw: String?): MessageQuote? =
        if (raw.isNullOrBlank()) null else runCatching { json.decodeFromString<MessageQuote>(raw) }.getOrNull()

    /** The snapshot has no chat message id or attachment. It can be read after the photo is removed. */
    internal fun fromAlbum(look: WorldAlbumLookEntity): MessageQuote? {
        val text = listOf(look.content.trim(), look.question.trim()).filter(String::isNotEmpty)
            .distinct().joinToString("\n\n")
        return text.takeIf(String::isNotEmpty)?.let { MessageQuote(0, "assistant", it, fromAlbum = true) }
    }

    /** A quote of [m], or null when there is nothing in it to quote (a line, a card). */
    fun of(m: MessageEntity): MessageQuote? {
        if ((m.role != "user" && m.role != "assistant") || m.note != null) return null
        // A sticker is quoted by its name: the picture isn't kept with the quote.
        val words = StickerText.plain(m.content).trim()
        val pictures = MessageImages.decode(m.images).size
        val text = when {
            words.isNotEmpty() -> words
            m.audio != null -> "[语音]"
            pictures > 0 -> "[图片]"
            else -> return null
        }
        return MessageQuote(m.id, m.role, text.take(MAX_TEXT))
    }

    /**
     * Which of the person's recent messages ([recent], newest first) the model meant by
     * [words]: the newest that has them in it, or that is all of them. Punctuation and spaces
     * don't count: the model copies words, not always the commas around them.
     */
    fun find(recent: List<MessageEntity>, words: String): MessageEntity? {
        val want = plain(words)
        if (want.isEmpty()) return null
        return recent.firstOrNull { m ->
            val said = plain(m.content)
            said.isNotEmpty() && (said.contains(want) || (said.length >= 2 && want.contains(said)))
        }
    }

    private val NOT_WORDS = Regex("""[\s\p{P}\p{S}]+""")

    private fun plain(s: String) = s.replace(NOT_WORDS, "").lowercase()
}
