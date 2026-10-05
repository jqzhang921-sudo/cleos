package com.cleo.cleos.ai

import com.cleo.cleos.data.LoreKeys
import com.cleo.cleos.data.db.LoreDao
import com.cleo.cleos.data.db.LoreEntity
import kotlinx.serialization.json.JsonObject

/**
 * What a TA can look up in their 设定: a world book brought over from another app, or written
 * here. It is not memory — the entries are not about the person and are not in front of the
 * model every message — so the TA reads one by asking for it, the way a person opens a book
 * rather than remembering it all.
 */
class LoreBook(private val dao: LoreDao) {
    suspend fun act(a: JsonObject, companionId: Long): ToolOutcome =
        when (ToolArgs.text(a, "action")?.trim()?.lowercase()) {
            "search" -> search(a, companionId)
            "open" -> open(a, companionId)
            else -> throw ToolFailure("action 要是 search 或 open。", "不知道要做什么")
        }

    private suspend fun search(a: JsonObject, companionId: Long): ToolOutcome {
        val query = ToolArgs.text(a, "query")?.trim().orEmpty()
        if (query.isEmpty()) throw ToolFailure("search 要给 query：要查的那个词。", "没说查什么")
        val all = dao.allFor(companionId).filter { it.enabled }
        if (all.isEmpty()) throw ToolFailure("对方还没有给你带设定过来。", "还没有设定")
        val words = LoreSearch.words(query)
        val hits = all
            .mapNotNull { entry -> LoreSearch.score(entry, words).takeIf { it > 0 }?.let { entry to it } }
            .sortedByDescending { it.second }
            .take(HITS)
            .map { it.first }
        if (hits.isEmpty()) {
            return ToolOutcome(
                "设定里没有和「$query」有关的条目。换个说法，或者只拿其中一个词再查一次；确实没有就别当有。",
                "设定里没有「$query」",
            )
        }
        val text = buildString {
            append("设定里和「").append(query).append("」有关的 ").append(hits.size).append(" 条：")
            for (e in hits) {
                val keys = LoreKeys.decode(e.keys)
                append("\n\n【#").append(e.id).append(' ').append(e.title).append('】')
                if (keys.isNotEmpty()) append("（").append(keys.joinToString("、")).append("）")
                append('\n').append(e.content.take(ENTRY_CHARS))
                if (e.content.length > ENTRY_CHARS) append("…（还没完，用 open $e.id 看整条）")
            }
            append("\n\n这是设定里的原文，照它说，别改动它；哪条要整条看就用 open。")
        }
        return ToolOutcome(text, "查了设定：$query")
    }

    private suspend fun open(a: JsonObject, companionId: Long): ToolOutcome {
        val id = ToolArgs.id(a["id"]) ?: throw ToolFailure("缺少 id：用结果里那个 #号。", "不知道是哪一条")
        val entry = dao.get(id)?.takeIf { it.companionId == companionId && it.enabled }
            ?: throw ToolFailure("没有 #$id 这一条。用 search 查出来的 #号。", "没找到这一条")
        val keys = LoreKeys.decode(entry.keys)
        val text = buildString {
            append("【#").append(entry.id).append(' ').append(entry.title).append('】')
            if (entry.book.isNotBlank()) append("（《").append(entry.book).append("》）")
            if (keys.isNotEmpty()) append("\n关键词：").append(keys.joinToString("、"))
            append('\n').append(entry.content.take(WHOLE_CHARS))
            if (entry.content.length > WHOLE_CHARS) append("\n…（这一条很长，后面的没有给完）")
        }
        return ToolOutcome(text, "看了设定「${entry.title}」")
    }

    private companion object {
        /** How many entries one search brings back at once, and how much of each. */
        const val HITS = 5
        const val ENTRY_CHARS = 700

        /** One entry opened by itself: as much as is worth putting in a turn. */
        const val WHOLE_CHARS = 4_000
    }
}

/**
 * Finding the entry a word belongs to. A world book is looked up by its keywords, so a word
 * that is one of them is what a search is for; the text itself is only a fallback.
 */
internal object LoreSearch {
    /** Common two-character runs of a sentence: never what someone is looking up. */
    private val COMMON = setOf(
        "什么", "怎么", "这个", "那个", "一个", "我们", "你们", "他们", "是不", "不是", "没有", "有没",
        "知道", "告诉", "一下", "相关", "关于", "哪些", "哪个", "时候", "的时", "意思", "为什", "么意",
    )
    private val SPLIT = Regex("[\\s、,，。.！!？?；;：:/|「」『』()（）]+")

    /**
     * The words to look for in [query]. A Chinese sentence has no spaces in it, so a run longer
     * than a word is also looked at in twos: 「星历是什么」 looks up 星历 as well.
     */
    fun words(query: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in query.lowercase().split(SPLIT)) {
            val word = raw.trim()
            if (word.isEmpty()) continue
            out += word
            if (word.length <= WORD_MAX) continue
            for (i in 0..word.length - 2) {
                val pair = word.substring(i, i + 2)
                if (pair !in COMMON) out += pair
            }
        }
        return out.take(MAX_WORDS)
    }

    /** How much [entry] has to do with those words: a keyword first, the text after it. */
    fun score(entry: LoreEntity, words: List<String>): Int {
        val keys = LoreKeys.decode(entry.keys).map { it.lowercase() }
        val title = entry.title.lowercase()
        val content = entry.content.lowercase()
        var score = 0
        for (w in words) {
            score += when {
                keys.any { it == w } -> 12
                keys.any { it.contains(w) || w.contains(it) } -> 7
                else -> 0
            }
            if (title.contains(w)) score += 5
            if (content.contains(w)) score += 2
        }
        return score
    }

    /** Past this a run is a sentence, not a word. */
    private const val WORD_MAX = 4
    private const val MAX_WORDS = 12
}

/**
 * What a TA is told about their 设定 before they have looked anything up: that it is there, and
 * what is in it. Only the entry titles and their keywords go into the prompt — the text stays
 * out until the TA asks, so a book of hundreds of entries costs a few lines a message.
 */
object LoreDigest {
    /** How much of the list of titles goes along; past this only the count does, and the TA searches. */
    const val INDEX_MAX = 1_500

    fun forChat(lore: List<LoreEntity>): String? {
        val shown = lore.filter { it.enabled }
        if (shown.isEmpty()) return null
        val head = "对方给你带了一份设定（世界书），那是你们所在的世界：里面的人、地方、称呼、规矩都以它为准。" +
            "用到的时候用 lore 工具查：给 search 一个词（人名、地名、称呼，或者别的设定里的说法），它会把原文给你。" +
            "查到就照原文说，别照着标题猜，也别编设定里没有的事；没说到的就当你不知道。"
        val list = buildString {
            for ((book, entries) in shown.groupBy { it.book }) {
                append('\n')
                if (book.isNotBlank()) append("《").append(book).append("》")
                for (e in entries) {
                    append("\n· ").append(e.title)
                    val keys = LoreKeys.decode(e.keys)
                    if (keys.isNotEmpty()) append("（").append(keys.joinToString("、")).append("）")
                }
            }
        }
        return if (list.length <= INDEX_MAX) "$head\n这份设定里有这些（只是名字，内容要查）：$list"
        else "$head\n（设定里条目不少，共 ${shown.size} 条，不一一列了；用到哪个词就查哪个。）"
    }
}
