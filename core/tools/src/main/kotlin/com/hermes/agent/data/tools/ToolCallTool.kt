package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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
        // Models often send `arguments` as a JSON string. Parse it; running the
        // tool with {} instead would ignore what the user just approved.
        val toolArgs: Map<String, JsonElement> = when (nestedArgsElem) {
            is JsonObject -> nestedArgsElem.toMap()
            null, JsonNull -> emptyMap()
            else -> {
                val text = (nestedArgsElem as? JsonPrimitive)?.takeIf { it.isString }?.content
                val parsed = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                (parsed as? JsonObject)?.toMap()
                    ?: return ToolResult.error("tool_call `arguments` must be a JSON object")
            }
        }

        return targetTool.execute(toolArgs)
    }
}
