package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ToolDisclosureResult(
    val modelVisibleDescriptors: List<ToolDescriptor>,
    val isProgressiveDisclosureActive: Boolean,
    val deferredDescriptors: List<ToolDescriptor>,
)

/**
 * Progressive Tool Disclosure Engine (Tool Search).
 *
 * Evaluates the token footprint of deferrable tools against the active model's context window.
 * When deferrable tools exceed the threshold budget, they are hidden behind three bridge tools:
 * `tool_search`, `tool_describe`, and `tool_call`.
 * Core tools are NEVER deferred.
 */
object ToolSearchEngine {

    const val TOOL_SEARCH_NAME = "tool_search"
    const val TOOL_DESCRIBE_NAME = "tool_describe"
    const val TOOL_CALL_NAME = "tool_call"

    private const val CHARS_PER_TOKEN = 4.0
    private const val DEFAULT_THRESHOLD_PCT = 5.0

    val BRIDGE_TOOL_NAMES = setOf(TOOL_SEARCH_NAME, TOOL_DESCRIBE_NAME, TOOL_CALL_NAME)

    val searchToolDescriptor = ToolDescriptor(
        name = TOOL_SEARCH_NAME,
        description = "Search available deferred tools by keyword or task description. Returns matching tool names and brief summaries.",
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "Keywords describing the capability or task (e.g. 'github issues', 'notion search', 'send message')",
                required = true
            ),
            ToolParameter(
                name = "limit",
                type = ToolParameterType.INTEGER,
                description = "Maximum number of tool matches to return (default: 5, max: 20)",
                required = false
            )
        ),
        category = "system",
        capabilities = setOf("system", "tool_search"),
    )

    val describeToolDescriptor = ToolDescriptor(
        name = TOOL_DESCRIBE_NAME,
        description = "Inspect the full parameter schema and documentation for a specific deferred tool.",
        parameters = listOf(
            ToolParameter(
                name = "tool_name",
                type = ToolParameterType.STRING,
                description = "Exact name of the tool to describe (e.g. 'mcp__github__create_issue')",
                required = true
            )
        ),
        category = "system",
        capabilities = setOf("system", "tool_search"),
    )

    val callToolDescriptor = ToolDescriptor(
        name = TOOL_CALL_NAME,
        description = "Execute a deferred tool by name with arguments. Pass the tool arguments inside the arguments object.",
        parameters = listOf(
            ToolParameter(
                name = "tool_name",
                type = ToolParameterType.STRING,
                description = "Exact name of the deferred tool to execute",
                required = true
            ),
            ToolParameter(
                name = "arguments",
                type = ToolParameterType.OBJECT,
                description = "JSON object containing named arguments for the target tool",
                required = false
            )
        ),
        category = "system",
        requiresConfirmation = true,
        capabilities = setOf("system", "tool_search"),
    )

    /**
     * Compute progressive disclosure for the given tool descriptors.
     *
     * @param allDescriptors Full catalog of tools available in the app.
     * @param contextWindowTokens Active model's context window token limit (e.g. 128000).
     * @param thresholdPct Percentage of context window allocated for deferrable tools (default 5.0%).
     */
    fun evaluate(
        allDescriptors: List<ToolDescriptor>,
        contextWindowTokens: Int = 128000,
        thresholdPct: Double = DEFAULT_THRESHOLD_PCT,
    ): ToolDisclosureResult {
        val (coreTools, deferrableTools) = allDescriptors.partition { isCoreTool(it) }

        if (deferrableTools.isEmpty()) {
            return ToolDisclosureResult(
                modelVisibleDescriptors = allDescriptors,
                isProgressiveDisclosureActive = false,
                deferredDescriptors = emptyList(),
            )
        }

        // Estimate token cost of deferrable tool descriptors
        val estimatedChars = deferrableTools.sumOf { descriptorCharSize(it) }
        val estimatedTokens = estimatedChars / CHARS_PER_TOKEN
        val tokenBudget = (contextWindowTokens * (thresholdPct / 100.0)).coerceAtLeast(500.0)

        return if (estimatedTokens <= tokenBudget) {
            // Tier 0: Direct passthrough
            ToolDisclosureResult(
                modelVisibleDescriptors = allDescriptors,
                isProgressiveDisclosureActive = false,
                deferredDescriptors = emptyList(),
            )
        } else {
            // Tier 1/2: Progressive disclosure active — replace deferrable tools with bridge tools
            val bridgeTools = listOf(searchToolDescriptor, describeToolDescriptor, callToolDescriptor)
            val modelVisible = coreTools + bridgeTools

            ToolDisclosureResult(
                modelVisibleDescriptors = modelVisible,
                isProgressiveDisclosureActive = true,
                deferredDescriptors = deferrableTools,
            )
        }
    }

    fun isCoreTool(descriptor: ToolDescriptor): Boolean {
        // A tool is core if it does NOT have "deferrable" or "mcp" capability and is not an mcp__ prefixed tool
        val isMcp = descriptor.name.startsWith("mcp__") || descriptor.category == "mcp" || descriptor.capabilities.contains("mcp")
        val isDeferrable = descriptor.capabilities.contains("deferrable")
        return !isMcp && !isDeferrable
    }

    private fun descriptorCharSize(descriptor: ToolDescriptor): Int {
        var size = descriptor.name.length + descriptor.description.length + 30
        for (param in descriptor.parameters) {
            size += param.name.length + param.description.length + param.type.name.length + 20
            param.enumValues?.forEach { size += it.length + 2 }
        }
        return size
    }
}
