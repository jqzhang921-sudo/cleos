package com.cleo.cleos.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A feature the service explicitly rejected, not a guess made from an HTTP status. */
enum class RequestFeature { Tools, Images, Thinking }

internal object ChatCompatibility {
    private fun error(body: String): JsonObject? = runCatching {
        (Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
    }.getOrNull()

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun rejectedFeature(body: String): RequestFeature? {
        val e = error(body) ?: return null
        val message = e.text("message").lowercase()
        // A malformed history/schema is not proof of an unsupported capability.
        if (listOf("reasoning_content", "schema", "properties", "arguments", "tool_call_id").any { it in message }) return null
        val param = e.text("param").trim('\'', '"').lowercase()
        val unknown = "(?:unsupported|unknown|unrecognized|unrecognised) (?:parameter|request argument|field)(?: supplied)?"
        if (Regex(unknown).containsMatchIn(message) || e.text("code") == "unsupported_parameter") {
            val field = param.ifEmpty { Regex("$unknown\\s*:?\\s*['\"]?([a-z_]+)").find(message)?.groupValues?.get(1).orEmpty() }
            if (field in setOf("thinking", "thinking.type", "reasoning_effort")) return RequestFeature.Thinking
            if (field == "tools") return RequestFeature.Tools
        }
        // Tool support restricted in a particular thinking mode is not a global tool refusal.
        if ("thinking" !in message && "reasoning" !in message && "image" !in message && listOf(
                "does not support tools", "doesn't support tools", "does not support tool calling",
                "does not support tool calls", "does not support function calling",
                "tools are not supported", "tool calling is not supported", "tool calls are not supported",
                "no endpoints found that support tool use", "不支持工具调用", "不支持函数调用",
            ).any { it in message }) return RequestFeature.Tools
        if (listOf("does not support image", "image input is not supported", "images are not supported", "不支持图片", "不支持图像").any { it in message }) return RequestFeature.Images
        if (listOf("thinking is not supported", "does not support thinking", "不支持深度思考").any { it in message }) return RequestFeature.Thinking
        return null
    }

    fun fallback(failure: ChatException, tools: Boolean, images: Boolean, thinking: Boolean): RequestFeature? {
        if (failure.status !in setOf(400, 404, 422)) return null
        return failure.rejectedFeature?.takeIf {
            when (it) {
                RequestFeature.Tools -> tools
                RequestFeature.Images -> images
                RequestFeature.Thinking -> thinking
            }
        }
    }

    /** No provider prose is logged: it can echo a secret, a tool argument or a person's words. */
    fun diagnostic(body: JsonObject, status: Int? = null, errorBody: String? = null): String {
        fun safe(value: String): String = value.takeIf { it.length <= 100 && it.matches(Regex("[A-Za-z0-9_./\\[\\]-]+")) } ?: "[redacted]"
        val tools = body["tools"] as? JsonArray
        val names = tools.orEmpty().mapNotNull { ((it as? JsonObject)?.get("function") as? JsonObject)?.text("name") }
        val assistants = (body["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.text("role") == "assistant" }
        val absent = assistants.mapIndexedNotNull { i, m -> i.takeIf { "reasoning_content" !in m } }
        val thinking = (body["thinking"] as? JsonObject)?.text("type").orEmpty().ifEmpty { "omitted" }
        val e = errorBody?.let(::error)
        val message = e?.text("message").orEmpty().lowercase()
        val category = when {
            "reasoning_content" in message && listOf("missing", "required", "must be provided").any { it in message } -> "missing_reasoning_content"
            "reasoning_content" in message -> "reasoning_content_error"
            "schema" in message || "properties" in message -> "tool_schema_error"
            "tool_call_id" in message -> "tool_history_error"
            errorBody != null && rejectedFeature(errorBody) != null -> "unsupported_${rejectedFeature(errorBody)!!.name.lowercase()}"
            status == null -> "request"
            else -> "other_http_error"
        }
        return "model=${safe(body.text("model"))} thinking=$thinking toolsPresent=${tools != null} tools=${tools?.size ?: 0} " +
            "send_message=${"send_message" in names} mcp=${names.count { it.startsWith("mcp_") }} " +
            "names=${names.take(40).joinToString(",") { safe(it) }} assistants=${assistants.size} " +
            "reasoningAbsent=${absent.take(40).joinToString(",")} status=${status ?: "pending"} category=$category " +
            "type=${errorLabel(e?.text("type").orEmpty())} code=${errorLabel(e?.text("code").orEmpty())} " +
            "param=${e?.text("param")?.takeIf { it.matches(Regex("(?:messages|tools|thinking|reasoning_effort|model|max_tokens)(?:\\[\\d+\\]|\\.[a-z_]+)*")) } ?: "redacted_or_absent"}"
    }

    private fun errorLabel(value: String): String = when (value) {
        "" -> "none"
        "invalid_request_error", "invalid_request", "invalid_parameter", "unsupported_parameter", "unknown_parameter",
        "authentication_error", "permission_error", "rate_limit_error", "server_error", "api_error",
        "model_not_found", "context_length_exceeded", "insufficient_quota", "invalid_api_key" -> value
        else -> "redacted"
    }
}
