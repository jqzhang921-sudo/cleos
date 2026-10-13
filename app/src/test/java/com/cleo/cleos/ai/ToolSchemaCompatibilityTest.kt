package com.cleo.cleos.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ToolSchemaCompatibilityTest {
    @Test fun providerIndexIdentifiesTheRejectedToolBeyondTheNamePreviewLimit() {
        val tools = (0..48).map { ToolSpecs.addTodo.copy(name = "mcp_fixture_$it") }
        val request = requestBody("claude-fixture", listOf(ApiMessage("user", "private")), tools)
        val error = obj("""{"error":{"message":"Unknown name \"dependentRequired\" at '***.tools[0].function_declarations[48].parameters': Cannot find field."}}""").toString()
        assertEquals(48, ToolSchemaCompatibility.rejectedToolIndex(error))
        assertTrue(ChatCompatibility.diagnostic(request, 400, error).contains("rejectedTool=mcp_fixture_48"))
    }
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun onlyAnExplicitUnknownSchemaFieldIsRecognized() {
        val body = obj("""{"error":{"message":"Invalid JSON payload received. Unknown name \"dependentRequired\" at '***.tools[0].function_declarations[48].parameters': Cannot find field."}}""").toString()
        assertEquals("dependentRequired", ToolSchemaCompatibility.rejectedKeyword(body))
        assertNull(ChatCompatibility.rejectedFeature(body))
        assertTrue(ChatCompatibility.diagnostic(obj("""{"model":"Claude-opus-4-6","tools":[]} """), 400, body).contains("category=tool_schema_keyword_error"))
        for (message in listOf(
            "Unknown name \"dependentRequired\" at 'messages[0]': Cannot find field.",
            "Unknown name \"dependentRequired\" at 'tools[0].arguments': Cannot find field.",
            "Invalid arguments: dependentRequired is missing",
            "Unknown name \"properties\" at 'tools[0].parameters': Cannot find field.",
        )) {
            val error = JsonObject(mapOf("error" to JsonObject(mapOf("message" to JsonPrimitive(message))))).toString()
            assertNull(message, ToolSchemaCompatibility.rejectedKeyword(error))
        }
        assertNull(ToolSchemaCompatibility.rejectedKeyword("Unknown name dependentRequired"))
    }

    @Test fun dependenciesBecomeInstructionsWithoutRemovingAnyParametersOrTools() {
        val original = obj("""{"type":"object","description":"Existing help","properties":{"a":{"type":"string"},"b":{"type":"number"}},"required":["a"],"dependentRequired":{"a":["b"]}}""")
        val tool = ToolSpecs.addTodo.copy(parameters = original)
        val tools = listOf(tool, ToolSpecs.sendMessage)
        val adapted = ToolSchemaCompatibility.adapt(tools)
        assertEquals(tools.map { it.name }, adapted.map { it.name })
        val schema = adapted[0].parameters
        assertFalse("dependentRequired" in schema)
        for (key in listOf("type", "properties", "required")) assertEquals(original[key], schema[key])
        assertTrue(schema["description"].toString().contains("Existing help"))
        assertTrue(schema["description"].toString().contains("also provide: b"))
        assertEquals(ToolSpecs.sendMessage, adapted[1])
        assertTrue("dependentRequired" in original)
    }

    @Test fun recursiveConversionDoesNotStripPropertyNamesDefaultsOrExamples() {
        val schema = obj("""{"type":"object","properties":{
          "dependentRequired":{"type":"string"},
          "nested":{"type":"array","items":{"type":"object","properties":{"x":{"type":"string"}},"dependentRequired":{"x":["y"]}}}
        },"default":{"dependentRequired":{"is":"data"}},"examples":[{"dependentRequired":123}],
        "${'$'}defs":{"entry":{"dependentRequired":{"a":["b"]},"properties":{"a":{"type":"string"}}}},
        "allOf":[{"dependentRequired":{"a":["b"]}}]}""")
        val adapted = ToolSchemaCompatibility.adapt(listOf(ToolSpecs.addTodo.copy(parameters = schema))).single().parameters
        assertEquals(schema["default"], adapted["default"])
        assertEquals(schema["examples"], adapted["examples"])
        assertEquals(schema["properties"]!!.jsonObject["dependentRequired"], adapted["properties"]!!.jsonObject["dependentRequired"])
        val nested = adapted["properties"]!!.jsonObject["nested"]!!.jsonObject["items"]!!.jsonObject
        assertFalse("dependentRequired" in nested)
        assertTrue(nested["description"].toString().contains("also provide: y"))
        assertFalse(adapted["${'$'}defs"]!!.jsonObject["entry"]!!.jsonObject.containsKey("dependentRequired"))
        assertEquals(adapted, ToolSchemaCompatibility.adapt(listOf(ToolSpecs.addTodo.copy(parameters = adapted))).single().parameters)
    }

    @Test fun originalRootDependenciesAreCheckedBeforeAnMcpCall() {
        val schema = obj("""{"dependentRequired":{"a":["b","c"],"d":["c"]}}""")
        assertEquals(listOf("b", "c"), ToolSchemaCompatibility.missingDependencies(schema, obj("""{"a":"value","d":true}""")))
        assertTrue(ToolSchemaCompatibility.missingDependencies(schema, obj("""{"b":"value"}""")).isEmpty())
        assertTrue(ToolSchemaCompatibility.missingDependencies(schema, obj("""{"a":null,"b":null,"c":null}""")).isEmpty())
    }
}
