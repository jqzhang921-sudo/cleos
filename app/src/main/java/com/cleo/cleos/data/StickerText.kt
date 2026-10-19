package com.cleo.cleos.data

import com.cleo.cleos.data.db.StickerEntity
import kotlinx.serialization.json.Json

/**
 * The sticker collection, looked up by the names messages use: as written, then among the names
 * stickers had before they were renamed, then ignoring spaces, punctuation and case (a model
 * copying a name drops a comma or adds a space), then as the one sticker whose name has it in it.
 */
class StickerBook(val all: List<StickerEntity>) {
    private val byName = HashMap<String, StickerEntity>()
    private val byKey = HashMap<String, StickerEntity>()

    init {
        // Names first: a sticker's own name wins over another's old one.
        for (s in all) byName.putIfAbsent(s.name, s)
        for (s in all) for (a in aliases(s)) byName.putIfAbsent(a, s)
        for ((name, s) in byName) key(name).takeIf { it.isNotEmpty() }?.let { byKey.putIfAbsent(it, s) }
    }

    fun find(name: String): StickerEntity? {
        val n = name.trim()
        if (n.isEmpty()) return null
        byName[n]?.let { return it }
        val k = key(n)
        if (k.isEmpty()) return null
        byKey[k]?.let { return it }
        // "兔子" for 兔子晕倒, when nothing else has it: a single character would find too much.
        if (k.length < 2) return null
        return all.filter { key(it.name).contains(k) }.singleOrNull()
    }

    /** Whether [name] already means another sticker than [except]: its name or one it had, the same but for spaces and marks. */
    fun taken(name: String, except: Long? = null): Boolean {
        val n = name.trim()
        val k = key(n)
        return all.any { s ->
            s.id != except && (listOf(s.name) + aliases(s)).any { it == n || (k.isNotEmpty() && key(it) == k) }
        }
    }

    companion object {
        val EMPTY = StickerBook(emptyList())

        fun aliases(s: StickerEntity): List<String> =
            runCatching { Json.decodeFromString<List<String>>(s.aliases) }.getOrDefault(emptyList())

        fun encodeAliases(list: List<String>): String = Json.encodeToString(list)

        private val NOT_WORDS = Regex("""[\s\p{P}\p{S}]+""")

        fun key(s: String): String = s.replace(NOT_WORDS, "").lowercase()
    }
}

/**
 * How a sticker goes in a message: [[sticker:兔子晕倒]]. Both sides write it so (the TA as its
 * prompt says, the person's drawer when a sticker is tapped) and the chat draws the picture in its
 * place. Words, not a picture: a sticker costs the model what its name costs, and one that can't
 * look at pictures still knows what was sent.
 */
object StickerText {
    /** The token, and the spellings a model drifts to: 表情包 or 表情 for sticker, a full-width colon, spaces. */
    private val TOKEN = Regex("""\[\[\s*(?:sticker|表情包|表情)\s*[:：]\s*([^\[\]\n]{1,40}?)\s*]]""", RegexOption.IGNORE_CASE)

    /**
     * What a model sometimes writes instead, having seen stickers told in words (a quote, a line
     * about a reaction): [表情包：兔子晕倒], 【表情包：兔子晕倒】. Taken only on a line of its own and
     * inside a sentence it is the model saying something about a
     * sticker, and turning that into the picture put one in the middle of what it said.
     */
    private val LOOSE = Regex("""^[ \t]*[\[【]\s*表情包?\s*[:：]\s*([^\[\]【】\n]{1,40}?)\s*[\]】][ \t]*$""", RegexOption.MULTILINE)

    private val SHORT_MODEL_TOKEN = Regex("""^[ \t]*\[\[([^\[\]\n:：]{1,40})]][ \t]*$""", RegexOption.MULTILINE)

    /** Only assistant output: repair a standalone short marker; user wiki links and [动作] stay text. */
    fun normalizeModelTokens(text: String, book: StickerBook): String = SHORT_MODEL_TOKEN.replace(text) {
        val name = it.groupValues[1].trim()
        token(book.find(name)?.name ?: name)
    }

    /** How many of the collection the TA is told about: past this the list costs more than it gives. */
    const val MAX_LISTED = 100

    /** How much of a description goes in that list. */
    private const val DESCRIPTION_LISTED = 30

    fun token(name: String) = "[[sticker:$name]]"

    /** A sticker in words: in a notification, a quote, a search result, or where the picture is gone. */
    fun shown(name: String) = "[表情包：$name]"

    sealed interface Piece {
        data class Words(val text: String) : Piece

        data class Sticker(val sticker: StickerEntity) : Piece

        data class Unavailable(val name: String) : Piece
    }

    private class Hit(val range: IntRange, val name: String, val sticker: StickerEntity?)

    private fun hits(text: String, book: StickerBook): List<Hit> {
        val found = TOKEN.findAll(text).map { Hit(it.range, it.groupValues[1].trim(), book.find(it.groupValues[1])) } +
            LOOSE.findAll(text).map { Hit(it.range, it.groupValues[1].trim(), book.find(it.groupValues[1])) }
        val out = ArrayList<Hit>()
        // Where two overlap ([[表情包:x]] holds a [表情包:x]), the one starting first.
        for (h in found.sortedBy { it.range.first }) {
            if (out.isNotEmpty() && h.range.first <= out.last().range.last) continue
            out += h
        }
        return out
    }

    /**
     * [text] as the chat draws it: words and stickers, in order. A name the collection doesn't
     * have (any more) is explicitly unavailable, never a text pretending a picture was sent.
     */
    fun split(text: String, book: StickerBook): List<Piece> {
        if ('[' !in text && '【' !in text) return if (text.isEmpty()) emptyList() else listOf(Piece.Words(text))
        val hits = hits(text, book)
        if (hits.isEmpty()) return listOf(Piece.Words(text))
        val out = ArrayList<Piece>()
        val words = StringBuilder()
        fun flush() {
            val w = words.toString().trim()
            if (w.isNotEmpty()) out += Piece.Words(w)
            words.setLength(0)
        }
        var at = 0
        for (h in hits) {
            words.appendRange(text, at, h.range.first)
            flush()
            out += h.sticker?.let { Piece.Sticker(it) } ?: Piece.Unavailable(h.name)
            at = h.range.last + 1
        }
        words.appendRange(text, at, text.length)
        flush()
        return out
    }

    /** [text] where only words can be shown: each sticker as [表情包：名字]. */
    fun plain(text: String): String = if ('[' !in text) text else TOKEN.replace(text) { shown(it.groupValues[1].trim()) }

    /** The name, when [text] is one sticker and nothing else. */
    fun only(text: String): String? = TOKEN.matchEntire(text.trim())?.groupValues?.get(1)?.trim()

    /** [text] with every sticker taken out, written either way: what is read aloud, where no picture can go. */
    fun without(text: String): String =
        if ('[' !in text && '【' !in text) text else LOOSE.replace(TOKEN.replace(text, ""), "")

    /**
     * A reply still coming in, without a token that has only begun ("[[stic"): it would show as
     * words for a moment and then turn into a picture.
     */
    fun finishedPart(text: String): String {
        val open = text.lastIndexOf("[[")
        if (open >= 0 && text.indexOf("]]", open) < 0) return text.substring(0, open)
        return if (text.endsWith("[") || text.endsWith("【")) text.dropLast(1) else text
    }

    /**
     * The person's message for a TA that doesn't send stickers (its switch is off): each one told
     * in words rather than as the token, which it would take as something to answer in kind.
     */
    fun described(text: String, book: StickerBook): String = forHistory(text, book, sendTokens = false)

    /** Historical unknown/deleted names must not teach the model that those pictures exist. */
    fun forHistory(text: String, book: StickerBook, sendTokens: Boolean): String {
        val hits = hits(text, book)
        if (hits.isEmpty()) return text
        return buildString {
            var at = 0
            for (h in hits) {
                append(text, at, h.range.first)
                val s = h.sticker
                append(when {
                    s == null -> "（表情包不可用：${h.name}，本次没有可用的对应图片）"
                    sendTokens -> token(s.name)
                    s.description.isBlank() -> "（发了一张表情包：${s.name}）"
                    else -> "（发了一张表情包：${s.name}，${s.description.trim()}）"
                })
                at = h.range.last + 1
            }
            append(text, at, text.length)
        }
    }

    /** The collection as the TA's prompt lists it, one per line: the name, and what is in the picture. */
    fun menu(all: List<StickerEntity>): String = all.take(MAX_LISTED).joinToString("\n") { s ->
        val about = s.description.trim().replace('\n', ' ').take(DESCRIPTION_LISTED)
        if (about.isEmpty()) s.name else "${s.name}：$about"
    }
}
