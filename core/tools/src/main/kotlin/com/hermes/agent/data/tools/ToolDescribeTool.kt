package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

@Singleton
class ToolDescribeTool @Inject constructor(
    private val toolRegistryProvider: Provider<ToolRegistry>,
) : Tool {

    constructor(toolRegistry: ToolRegistry) : this(Provider { toolRegistry })

    override val descriptor: ToolDescriptor = ToolSearchEngine.describeToolDescriptor

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val toolName = arguments["tool_name"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (toolName.isBlank()) {
            return ToolResult.error("Parameter 'tool_name' cannot be blank")
        }

        var output = ""
        val duration = measureTimeMillis {
            val tool = toolRegistryProvider.get().byName(toolName)
            if (tool == null) {
                output = "Tool '$toolName' was not found in the tool registry."
            } else {
                val desc = tool.descriptor
                val schemaObj = buildJsonObject {
                    put("name", desc.name)
                    put("description", desc.description)
                    put("category", desc.category)
                    put("requiresConfirmation", desc.requiresConfirmation)
                    putJsonArray("parameters") {
                        for (param in desc.parameters) {
                            addJsonObject {
                                put("name", param.name)
                                put("type", param.type.jsonSchemaType)
                                put("description", param.description)
                                put("required", param.required)
                                if (param.enumValues != null) {
                                    putJsonArray("enumValues") {
                                        for (enumVal in param.enumValues) {
                                            add(enumVal)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                output = schemaObj.toString()
            }
        }

        return ToolResult.ok(output, executionMs = duration)
    }
}
