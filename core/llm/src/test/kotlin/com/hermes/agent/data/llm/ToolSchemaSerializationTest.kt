package com.hermes.agent.data.llm

import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the OpenAI `tools` schema we send to cloud providers.
 *
 * Regression: `ToolParameter.required` used to default to true, so an
 * action-style tool that declared only `action` as required still emitted
 * every one of its arguments in `"required"`. Groq validates tool calls
 * against that array and rejected a perfectly good single-action call with
 * HTTP 400 ("parameters for tool todo did not match schema: missing
 * properties: 'id', 'priority', ..."), which surfaced to the user as a chat
 * turn that silently produced nothing.
 */
class ToolSchemaSerializationTest {

    private fun requiredArrayOf(json: String): List<String> {
        val marker = "\"required\":["
        val start = json.indexOf(marker) + marker.length
        val end = json.indexOf(']', start)
        val body = json.substring(start, end)
        if (body.isBlank()) return emptyList()
        return body.split(',').map { it.trim().trim('"') }
    }

    @Test
    fun `action-style tool marks only the discriminator required`() {
        val descriptor = ToolDescriptor(
            name = "todo",
            description = "Manage todos.",
            parameters = listOf(
                ToolParameter("action", ToolParameterType.STRING, "The action.", required = true),
                ToolParameter("id", ToolParameterType.STRING, "Task ID."),
                ToolParameter("title", ToolParameterType.STRING, "Task title."),
                ToolParameter("tags", ToolParameterType.ARRAY, "Tags."),
                ToolParameter("limit", ToolParameterType.INTEGER, "Max results."),
            ),
        )

        assertEquals(listOf("action"), requiredArrayOf(descriptor.toJsonOpenAiString()))
    }

    @Test
    fun `omitting the required flag leaves a parameter optional`() {
        val descriptor = ToolDescriptor(
            name = "sample",
            description = "Sample.",
            parameters = listOf(ToolParameter("maybe", ToolParameterType.STRING, "Optional.")),
        )

        assertEquals(emptyList<String>(), requiredArrayOf(descriptor.toJsonOpenAiString()))
    }

    @Test
    fun `single-purpose tool keeps its primary parameter required`() {
        val descriptor = ToolDescriptor(
            name = "web_search",
            description = "Search the web.",
            parameters = listOf(
                ToolParameter("query", ToolParameterType.STRING, "Query.", required = true),
                ToolParameter("limit", ToolParameterType.INTEGER, "Max results.", required = false),
            ),
        )

        assertEquals(listOf("query"), requiredArrayOf(descriptor.toJsonOpenAiString()))
    }

    @Test
    fun `every declared parameter still appears in properties`() {
        val descriptor = ToolDescriptor(
            name = "todo",
            description = "Manage todos.",
            parameters = listOf(
                ToolParameter("action", ToolParameterType.STRING, "The action.", required = true),
                ToolParameter("title", ToolParameterType.STRING, "Task title."),
            ),
        )

        val json = descriptor.toJsonOpenAiString()
        assertTrue(json.contains("\"action\":{"))
        assertTrue(json.contains("\"title\":{"))
    }

    /**
     * Regression: the `tools` array was assembled by hand-concatenating JSON and
     * escaped only `"`. Any tool whose description carried a newline, tab, or
     * backslash therefore emitted a raw control character inside a JSON string
     * literal, producing a body the provider could not parse at all. Hugging
     * Face's router rejected the entire request with HTTP 400 "Bad control
     * character in string literal in JSON at position N".
     *
     * That kills the request before any model runs, so it presents to the user
     * as every chat turn failing with no reply — indistinguishable from "the
     * cloud LLM is down", which is exactly how it was first reported.
     */
    @Test
    fun `tool schema stays valid JSON when text carries control characters`() {
        val descriptor = ToolDescriptor(
            name = "shell",
            description = "Run a command.\nUsage:\n\tshell(cmd)\nWindows path: C:\tmp",
            parameters = listOf(
                ToolParameter(
                    name = "cmd",
                    type = ToolParameterType.STRING,
                    description = "Command.\nExample:\tls -la \"quoted\"",
                    required = true,
                ),
            ),
        )

        val encoded = descriptor.toJsonOpenAiString()

        // Must parse at all: this is the exact failure the provider reported.
        val fn = Json.parseToJsonElement(encoded).jsonObject.getValue("function").jsonObject
        assertEquals(descriptor.description, fn.getValue("description").jsonPrimitive.content)

        val cmd = fn.getValue("parameters").jsonObject
            .getValue("properties").jsonObject
            .getValue("cmd").jsonObject
        assertEquals(descriptor.parameters[0].description, cmd.getValue("description").jsonPrimitive.content)

        // No raw control character may survive into the serialized form.
        assertTrue("raw control char leaked into tool JSON", encoded.none { it.code < 0x20 })
    }

    @Test
    fun `tool name and enum values with quotes stay valid JSON`() {
        val descriptor = ToolDescriptor(
            name = "quote_tool",
            description = "Handles \"quoted\" text",
            parameters = listOf(
                ToolParameter(
                    name = "mode",
                    type = ToolParameterType.STRING,
                    description = "Mode",
                    required = true,
                    enumValues = listOf("plain", "with\"quote", "with\backslash"),
                ),
            ),
        )

        val enum = Json.parseToJsonElement(descriptor.toJsonOpenAiString()).jsonObject
            .getValue("function").jsonObject
            .getValue("parameters").jsonObject
            .getValue("properties").jsonObject
            .getValue("mode").jsonObject
            .getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(descriptor.parameters[0].enumValues, enum)
    }
}
