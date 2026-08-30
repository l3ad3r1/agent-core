package com.hermes.agent.data.mcp

import com.hermes.agent.domain.mcp.McpToolDefinition
import com.hermes.agent.domain.tool.ToolParameterType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolTest {

    @Test
    fun `mcp tool generates descriptor with parameters and confirmation required`() {
        val definition = McpToolDefinition(
            serverId = "server-123",
            toolName = "create_repo",
            qualifiedName = "mcp__github__create_repo",
            description = "Creates a new GitHub repository",
            inputSchemaJson = """
                {
                    "type": "object",
                    "properties": {
                        "repo_name": {
                            "type": "string",
                            "description": "Name of the new repo"
                        },
                        "is_private": {
                            "type": "boolean",
                            "description": "Whether repo is private"
                        }
                    },
                    "required": ["repo_name"]
                }
            """.trimIndent()
        )

        val tool = McpTool(definition) { null }
        val desc = tool.descriptor

        assertEquals("mcp__github__create_repo", desc.name)
        assertEquals("Creates a new GitHub repository", desc.description)
        assertTrue(desc.requiresConfirmation)
        assertEquals(2, desc.parameters.size)

        val p1 = desc.parameters.find { it.name == "repo_name" }
        assertEquals(ToolParameterType.STRING, p1?.type)
        assertTrue(p1?.required == true)

        val p2 = desc.parameters.find { it.name == "is_private" }
        assertEquals(ToolParameterType.BOOLEAN, p2?.type)
        assertTrue(p2?.required == false)
    }

    @Test
    fun `mcp tool execution fails gracefully when client is unavailable`() = runTest {
        val definition = McpToolDefinition(
            serverId = "server-123",
            toolName = "ping",
            qualifiedName = "mcp__demo__ping",
            description = "Ping tool",
            inputSchemaJson = "{}"
        )

        val tool = McpTool(definition) { null }
        val result = tool.execute(emptyMap())

        org.junit.Assert.assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("not connected") == true)
    }
}
