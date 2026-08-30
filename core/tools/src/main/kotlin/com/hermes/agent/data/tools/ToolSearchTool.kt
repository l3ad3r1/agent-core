package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

@Singleton
class ToolSearchTool @Inject constructor(
    private val toolRegistry: ToolRegistry,
) : Tool {

    override val descriptor: ToolDescriptor = ToolSearchEngine.searchToolDescriptor

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val query = arguments["query"]?.jsonPrimitive?.content ?: ""
        val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 20)

        if (query.isBlank()) {
            return ToolResult.error("Parameter 'query' cannot be blank")
        }

        var output = ""
        val duration = measureTimeMillis {
            val queryTerms = query.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length > 1 }

            val allTools = toolRegistry.all()
            val deferrableTools = allTools.filter { !ToolSearchEngine.isCoreTool(it.descriptor) }

            val matches = deferrableTools.mapNotNull { tool ->
                val desc = tool.descriptor
                val textToMatch = "${desc.name} ${desc.description} ${desc.capabilities.joinToString(" ")} ${desc.parameters.joinToString(" ") { "${it.name} ${it.description}" }}".lowercase()

                var score = 0
                for (term in queryTerms) {
                    if (desc.name.lowercase().contains(term)) score += 5
                    if (desc.description.lowercase().contains(term)) score += 2
                    if (textToMatch.contains(term)) score += 1
                }

                if (score > 0 || queryTerms.isEmpty()) {
                    Pair(desc, score)
                } else {
                    null
                }
            }
                .sortedByDescending { it.second }
                .take(limit)

            val jsonArray = buildJsonArray {
                for ((desc, _) in matches) {
                    addJsonObject {
                        put("name", desc.name)
                        put("description", desc.description)
                        putJsonArray("parameters") {
                            for (param in desc.parameters) {
                                addJsonObject {
                                    put("name", param.name)
                                    put("type", param.type.jsonSchemaType)
                                    put("required", param.required)
                                    put("description", param.description)
                                }
                            }
                        }
                    }
                }
            }

            output = if (matches.isEmpty()) {
                "No deferred tools matched query '$query'. Available deferred tool count: ${deferrableTools.size}."
            } else {
                jsonArray.toString()
            }
        }

        return ToolResult.ok(output, executionMs = duration)
    }
}
