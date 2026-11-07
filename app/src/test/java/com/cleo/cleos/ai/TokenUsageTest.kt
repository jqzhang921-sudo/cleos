package com.cleo.cleos.ai

import com.cleo.cleos.data.MessageUsage
import com.cleo.cleos.data.MessageUsages
import com.cleo.cleos.data.UsageStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageTest {
    private fun parse(raw: String) = MessageUsages.parse(Json.parseToJsonElement(raw))

    @Test
    fun openAiShapeWithCacheAndReasoning() {
        val u = parse("""{"prompt_tokens":1200,"completion_tokens":80,"total_tokens":1280,
            "prompt_tokens_details":{"cached_tokens":1024},"completion_tokens_details":{"reasoning_tokens":30}}""")
        assertEquals(UsageStep(1200, 80, 1024, 30), u)
    }

    @Test
    fun deepSeekCacheHitField() {
        val u = parse("""{"prompt_tokens":500,"completion_tokens":20,"prompt_cache_hit_tokens":384,"prompt_cache_miss_tokens":116}""")
        assertEquals(384L, u?.cached)
    }

    @Test
    fun noCountsIsNull() {
        assertNull(parse("""{}"""))
        assertNull(MessageUsages.parse(null))
    }

    @Test
    fun parserKeepsLastUsageFromEmptyChoicesChunk() {
        val p = StreamParser()
        p.feed("""{"choices":[{"delta":{"content":"hi"}}]}""")
        p.feed("""{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2}}""")
        assertEquals(UsageStep(10, 2), p.usage)
    }

    @Test
    fun usageAddsUpOverSteps() {
        val u = MessageUsage(model = "m") + UsageStep(100, 10, 50) + UsageStep(200, 20)
        assertEquals(300L, u.prompt)
        assertEquals(30L, u.completion)
        assertEquals(330L, u.total)
        assertEquals(50L, u.cached)
        assertNull(u.reasoning)
        assertEquals(u, MessageUsages.decode(MessageUsages.encode(u)))
    }

    @Test
    fun streamOptionsOnlyWhenAsked() {
        assertFalse("stream_options" in requestBody("m", emptyList(), emptyList()))
        assertTrue(requestBody("m", emptyList(), emptyList(), includeUsage = true)["stream_options"]!!
            .jsonObject.containsKey("include_usage"))
    }
}
