package com.cleo.cleos.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A relay's limited schema dialect, not a statement about the model's tool capability. */
internal object ToolSchemaCompatibility {
    const val DEPENDENCIES = "dependentRequired"

    fun rejectedKeyword(body: String): String? {
        val message = runCatching {
            val error = (Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
            (error?.get("message") as? JsonPrimitive)?.contentOrNull
        }.getOrNull() ?: return null
        // Match a rejected *schema* field. A user/tool argument with the same name is data.
        val refused = Regex("Unknown name [\"']dependentRequired[\"'] at [\"'][^\"'\\r\\n]*tools\\[\\d+][^\"'\\r\\n]*\\.parameters[^\"'\\r\\n]*[\"']:\\s*Cannot find field", RegexOption.IGNORE_CASE)
        return DEPENDENCIES.takeIf { refused.containsMatchIn(message) }
    }

    /** A known schema refusal's zero-based tool index, without retaining provider prose. */
    fun rejectedToolIndex(body: String): Int? {
        if (rejectedKeyword(body) == null) return null
        val error = (Json.parseToJsonElement(body) as JsonObject)["error"] as JsonObject
        val message = (error["message"] as JsonPrimitive).contentOrNull.orEmpty()
        return (Regex("function_declarations\\[(\\d+)]").find(message)
            ?: Regex("tools\\[(\\d+)]").find(message))?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Only schema positions are traversed; properties, defaults and examples remain intact. */
    fun adapt(tools: List<ToolSpec>): List<ToolSpec> = tools.map { tool ->
        tool.copy(parameters = adaptSchema(tool.parameters))
    }

    private val maps = setOf("properties", "patternProperties", "\$defs", "definitions", "dependentSchemas")
    private val nodes = setOf("items", "additionalProperties", "unevaluatedProperties", "unevaluatedItems",
        "propertyNames", "contains", "not", "if", "then", "else")
    private val arrays = setOf("anyOf", "oneOf", "allOf", "prefixItems")

    private fun adaptSchema(schema: JsonObject): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        for ((key, value) in schema) {
            if (key == DEPENDENCIES) continue
            out[key] = when {
                key in maps && value is JsonObject -> JsonObject(value.mapValues { (_, child) -> adaptNode(child) })
                key in nodes -> if (value is JsonArray) JsonArray(value.map(::adaptNode)) else adaptNode(value)
                key in arrays && value is JsonArray -> JsonArray(value.map(::adaptNode))
                else -> value
            }
        }
        val dependencies = schema[DEPENDENCIES] as? JsonObject
        val notes = dependencies.orEmpty().mapNotNull { (trigger, fields) ->
            val required = (fields as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (required.isEmpty()) null else "When '$trigger' is provided, also provide: ${required.joinToString(", ")} ."
        }
        if (notes.isNotEmpty()) {
            val description = (schema["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            out["description"] = JsonPrimitive((listOf(description).filter { it.isNotBlank() } + notes).joinToString("\n"))
        }
        return JsonObject(out)
    }

    private fun adaptNode(value: JsonElement): JsonElement = if (value is JsonObject) adaptSchema(value) else value

    /** Preserve direct input dependencies before calling MCP; the server validates the full schema. */
    fun missingDependencies(schema: JsonObject, args: JsonObject): List<String> {
        val dependencies = schema[DEPENDENCIES] as? JsonObject ?: return emptyList()
        return dependencies.flatMap { (trigger, fields) ->
            if (trigger !in args) emptyList() else (fields as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it !in args }
        }.distinct()
    }
}
