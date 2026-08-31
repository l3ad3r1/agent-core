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
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ToolCallTool @Inject constructor(
    private val toolRegistryProvider: Provider<ToolRegistry>,
    private val deferredScope: DeferredToolScope,
) : Tool {

    constructor(
        toolRegistry: ToolRegistry,
        deferredScope: DeferredToolScope = DeferredToolScope(),
    ) : this(Provider { toolRegistry }, deferredScope)

    override val descriptor: ToolDescriptor = ToolSearchEngine.callToolDescriptor

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val toolName = arguments["tool_name"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (toolName.isBlank()) {
            return ToolResult.error("Parameter 'tool_name' is required to execute a deferred tool")
        }

        // Role grants are applied when the advertised tool list is built, and this
        // path never went back through it: without this check any role could run
        // any deferred tool by naming it. The scope holds only what the running
        // agent was actually granted. See DeferredToolScope.
        if (!deferredScope.isAllowed(toolName)) {
            return ToolResult.error(
                "Tool '$toolName' is not available to this agent. Use tool_search to see what is."
            )
        }

        val targetTool = toolRegistryProvider.get().byName(toolName)
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
