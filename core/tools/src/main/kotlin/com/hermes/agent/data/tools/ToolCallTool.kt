package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolCallTool @Inject constructor(
    private val toolRegistry: ToolRegistry,
) : Tool {

    override val descriptor: ToolDescriptor = ToolSearchEngine.callToolDescriptor

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val toolName = arguments["tool_name"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (toolName.isBlank()) {
            return ToolResult.error("Parameter 'tool_name' is required to execute a deferred tool")
        }

        val targetTool = toolRegistry.byName(toolName)
            ?: return ToolResult.error("Deferred tool '$toolName' is not registered or unavailable")

        val nestedArgsElem = arguments["arguments"]
        val toolArgs: Map<String, JsonElement> = when (nestedArgsElem) {
            is JsonObject -> nestedArgsElem.toMap()
            null -> emptyMap()
            else -> {
                try {
                    nestedArgsElem.jsonObject.toMap()
                } catch (e: Exception) {
                    emptyMap()
                }
            }
        }

        return targetTool.execute(toolArgs)
    }
}
