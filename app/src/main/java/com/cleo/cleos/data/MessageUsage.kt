package com.cleo.cleos.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The tokens one request cost, as the service reported them in `usage`. Services disagree on
 * where the cached and thinking counts go, so [parse] looks in each place they are known to use.
 * Null fields: the service didn't say (not the same as zero).
 */
@Serializable
data class UsageStep(
    val prompt: Long = 0,
    val completion: Long = 0,
    /** Of [prompt], how much was read from the provider's cache (billed cheaper). */
    val cached: Long? = null,
    /** Of [completion], how much was thinking. */
    val reasoning: Long? = null,
)

/**
 * What one reply cost, over every request it took (a reply that uses tools asks several times).
 * Shown under the TA's last bubble of that reply; it never goes back to the model.
 */
@Serializable
data class MessageUsage(
    val steps: List<UsageStep> = emptyList(),
    val model: String = "",
) {
    val prompt: Long get() = steps.sumOf { it.prompt }
    val completion: Long get() = steps.sumOf { it.completion }
    val total: Long get() = prompt + completion
    val cached: Long? get() = steps.mapNotNull { it.cached }.takeIf { it.isNotEmpty() }?.sum()
    val reasoning: Long? get() = steps.mapNotNull { it.reasoning }.takeIf { it.isNotEmpty() }?.sum()

    /** Share of the prompt read from cache, 0..1; null when the service gave no cache figure. */
    val cacheRate: Float? get() = cached?.let { if (prompt > 0) it.toFloat() / prompt else 0f }

    operator fun plus(step: UsageStep): MessageUsage = copy(steps = steps + step)
}

object MessageUsages {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(usage: MessageUsage): String = json.encodeToString(usage)

    fun decode(raw: String?): MessageUsage? =
        if (raw.isNullOrBlank()) null else runCatching { json.decodeFromString<MessageUsage>(raw) }.getOrNull()

    /**
     * A `usage` object as the services send it. OpenAI / DeepSeek / 智谱 / Kimi / SiliconFlow /
     * OpenRouter all use prompt_tokens + completion_tokens; the cache figure lives in
     * prompt_tokens_details.cached_tokens (OpenAI, 智谱, OpenRouter), prompt_cache_hit_tokens
     * (DeepSeek) or a top-level cached_tokens (Kimi). Anthropic-shaped relays say input/output.
     * Null when it holds no counts at all.
     */
    fun parse(usage: JsonElement?): UsageStep? {
        val u = usage as? JsonObject ?: return null
        fun num(o: JsonObject?, key: String): Long? = (o?.get(key) as? JsonPrimitive)?.longOrNull
        val promptDetails = u["prompt_tokens_details"] as? JsonObject
        val completionDetails = u["completion_tokens_details"] as? JsonObject
        val prompt = num(u, "prompt_tokens") ?: num(u, "input_tokens")
        val completion = num(u, "completion_tokens") ?: num(u, "output_tokens")
        if (prompt == null && completion == null) return null
        // Anthropic counts cache reads beside input, not inside it.
        val anthropicCache = num(u, "cache_read_input_tokens")
        val cached = num(promptDetails, "cached_tokens")
            ?: num(u, "prompt_cache_hit_tokens")
            ?: num(u, "cached_tokens")
            ?: anthropicCache
        val reasoning = num(completionDetails, "reasoning_tokens") ?: num(u, "reasoning_tokens")
        val promptTotal = (prompt ?: 0) + if (num(u, "prompt_tokens") == null) (anthropicCache ?: 0) else 0
        return UsageStep(promptTotal, completion ?: 0, cached, reasoning)
    }

    /** 1234 → 1,234; 12345 → 12.3k; for the one-line summary under a bubble. */
    fun short(n: Long): String = when {
        n < 10_000 -> "%,d".format(n)
        n < 1_000_000 -> "%.1fk".format(n / 1000.0)
        else -> "%.2fM".format(n / 1_000_000.0)
    }
}
