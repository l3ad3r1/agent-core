package com.hermes.agent.data.llm

import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
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
}
