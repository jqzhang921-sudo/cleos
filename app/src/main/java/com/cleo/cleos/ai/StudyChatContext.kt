package com.cleo.cleos.ai

import com.cleo.cleos.data.db.MessageEntity
import kotlinx.serialization.json.*

/** Historical shelf facts are available on relevant turns, not a perpetual suggestion to discuss books. */
internal object StudyChatContext {
    private val reading = Regex("书架|读书|看书|这本书|那本书|读过|读到|读完|阅读|读物|想读|哪本书|\\bbook\\b|\\breading\\b|\\bread\\b", RegexOption.IGNORE_CASE)
    fun select(raw: String, history: List<MessageEntity>, now: Long, background: Boolean): String {
        if (raw.isBlank() || history.any { it.conversationId < 0 }) return ""
        val data = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return ""
        val books = (data["books"] as? JsonArray).orEmpty()
        val wishes = (data["wishes"] as? JsonArray).orEmpty()
        if (background) {
            val after = maxOf(history.maxOfOrNull { it.createdAt } ?: 0, now - 24 * 60 * 60 * 1000L)
            val fresh = books.filter { row ->
                val book = row as? JsonObject
                val readAt = (book?.get("last_read_at") as? JsonPrimitive)?.longOrNull ?: 0
                val read = (book?.get("read_characters") as? JsonPrimitive)?.intOrNull ?: 0
                read > 0 && readAt > after && readAt <= now
            }
            if (fresh.isEmpty()) return ""
            // A past wish is not a new event. Once a conversation resumes, this activity is no longer new.
            return JsonObject(data + ("books" to JsonArray(fresh)) + ("wishes" to JsonArray(emptyList()))).toString()
        }
        fun relevant(text: String): Boolean = reading.containsMatchIn(text) || (books + wishes).any { row ->
            val title = ((row as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull.orEmpty()
            title.length >= 2 && text.contains(title, ignoreCase = true)
        }
        val users = history.filter { it.role == "user" }
        val last = users.lastOrNull() ?: return ""
        val followup = last.content.length <= 20 && users.getOrNull(users.lastIndex - 1)?.let {
            last.createdAt - it.createdAt in 0..(10 * 60 * 1000L) && relevant(it.content)
        } == true && Regex("哪|多少|怎么样|呢|进度|how|which", RegexOption.IGNORE_CASE).containsMatchIn(last.content)
        return raw.takeIf { relevant(last.content) || followup }.orEmpty()
    }
}
