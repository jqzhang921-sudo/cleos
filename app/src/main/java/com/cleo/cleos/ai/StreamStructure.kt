package com.cleo.cleos.ai

import kotlinx.serialization.json.*

/** Counts the received shape before parsing. Never retains values, text, names or arguments. */
internal class StreamStructure {
    private var chunks = 0
    private var invalid = 0
    private var toolChunks = 0
    private var namedChunks = 0
    private var nonFirst = 0
    private var otherShapes = 0
    private var legacy = 0
    private var contentChars = 0L
    private var reasoningChars = 0L
    private val roots = sortedSetOf<String>()
    private val deltas = sortedSetOf<String>()
    private val messages = sortedSetOf<String>()
    private val finishes = sortedSetOf<String>()
    private val rootFields = setOf("choices", "id", "object", "created", "model", "usage", "error", "type", "content", "delta", "tool_calls")
    private val payloadFields = setOf("role", "content", "tool_calls", "reasoning_content", "reasoning", "function_call", "refusal")
    private val finishValues = setOf("stop", "tool_calls", "length", "content_filter", "function_call")

    fun invalidChunk() { chunks++; invalid++ }

    fun record(root: JsonObject) {
        chunks++
        roots += root.keys.filter { it in rootFields }
        (root["choices"] as? JsonArray)?.forEachIndexed { index, item ->
            val choice = item as? JsonObject ?: return@forEachIndexed
            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let {
                finishes += if (it in finishValues) it else "other"
            }
            for ((field, keys) in listOf("delta" to deltas, "message" to messages)) {
                val payload = choice[field] as? JsonObject ?: continue
                keys += payload.keys.filter { it in payloadFields }
                val calls = payload["tool_calls"]
                if (calls != null && calls != JsonNull && calls !is JsonArray) otherShapes++
                val list = calls as? JsonArray
                toolChunks += list?.size ?: 0
                if (index > 0) nonFirst += list?.size ?: 0
                namedChunks += list?.count { piece ->
                    ((piece as? JsonObject)?.get("function") as? JsonObject)?.get("name")
                        .let { it as? JsonPrimitive }?.contentOrNull?.isNotEmpty() == true
                } ?: 0
                if (payload["function_call"]?.let { it != JsonNull } == true) legacy++
                contentChars += (payload["content"] as? JsonPrimitive)?.contentOrNull?.length ?: 0
                reasoningChars += (payload["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.length
                    ?: (payload["reasoning"] as? JsonPrimitive)?.contentOrNull?.length ?: 0
            }
        }
    }

    fun diagnostic(pendingCalls: Int) = "chunks=$chunks invalidChunks=$invalid " +
        "rootKeys=${roots.joinToString(",")} deltaKeys=${deltas.joinToString(",")} messageKeys=${messages.joinToString(",")} " +
        "toolChunks=$toolChunks namedToolChunks=$namedChunks nonFirstToolChunks=$nonFirst otherToolShapes=$otherShapes " +
        "legacyCalls=$legacy pendingCalls=$pendingCalls finish=${finishes.joinToString(",")} contentChars=$contentChars reasoningChars=$reasoningChars"
}
