package com.cleo.cleos.data

import com.cleo.cleos.ai.WorldReplyException
import kotlinx.serialization.json.*
import java.text.Normalizer
import java.util.Locale

/** A single public lookup term, never prose, a URL to execute, or a note to publish. */
internal object WorldTopicChoice {
    const val COOLDOWN_MS = 24 * 60 * 60 * 1000L
    fun identity(query: String): String = Normalizer.normalize(query, Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun parse(raw: String): String? {
        try {
            require(raw.length <= 8_000)
            val args = Json.parseToJsonElement(raw).jsonObject
            require(args.keys == setOf("query"))
            if (args["query"] == JsonNull) return null
            val value = args["query"] as? JsonPrimitive
            require(value != null && value.isString)
            return WorldTopicSearch.query(value.content)
        } catch (_: IllegalArgumentException) {
            throw WorldReplyException("choice_request", "模型没有返回有效的查阅主题，本次未开始搜索")
        }
    }
    fun repeated(query: String, recent: List<String>): Boolean = recent.any { identity(it) == identity(query) }
}
