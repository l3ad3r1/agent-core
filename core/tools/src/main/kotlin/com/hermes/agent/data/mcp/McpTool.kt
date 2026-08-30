package com.hermes.agent.data.mcp

import com.hermes.agent.domain.mcp.McpToolDefinition
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlin.system.measureTimeMillis

/**
 * Dynamic [Tool] implementation backed by an external MCP server.
 * All MCP tools declare `requiresConfirmation = true` to pass through ToolConfirmationService / ToolExecutionPolicy.
 */
class McpTool(
    val definition: McpToolDefinition,
    private val clientProvider: suspend () -> McpClient?,
) : Tool {

    override val descriptor: ToolDescriptor by lazy {
        val client = McpClient(
            com.hermes.agent.domain.mcp.McpServerConfig(
                id = definition.serverId,
                name = "",
                url = "",
            )
        )
        val params = client.parseParameters(definition.inputSchemaJson)

        ToolDescriptor(
            name = definition.qualifiedName,
            description = definition.description,
            parameters = params,
            category = "mcp",
            requiresConfirmation = true,
            capabilities = setOf("mcp", "deferrable"),
        )
    }

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        var output = ""
        var errorMsg: String? = null

        val duration = measureTimeMillis {
            val client = clientProvider()
            if (client == null) {
                errorMsg = "MCP server is not connected or disabled for tool '${definition.qualifiedName}'"
            } else {
                val callResult = client.callTool(definition.toolName, arguments)
                callResult.fold(
                    onSuccess = { output = it },
                    onFailure = { errorMsg = it.message ?: "MCP tool call failed" }
                )
            }
        }

        return if (errorMsg == null) {
            ToolResult.ok(output, executionMs = duration)
        } else {
            ToolResult.error(errorMsg ?: "MCP tool execution failed", executionMs = duration)
        }
    }
}
