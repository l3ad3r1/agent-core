package com.hermes.agent.data.plugin.script

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement

/**
 * Host capabilities a sandboxed module can reach, one method per gated API.
 *
 * Implemented in the app layer, which owns Room, the HTTP client, and settings.
 * Keeping it an interface is what lets [ScriptPluginEngine] stay free of Android
 * dependencies and unit-testable without a device.
 *
 * Every method is called from inside the Rhino sandbox on a background
 * dispatcher. Implementations must be thread-safe and must not block for long:
 * a slow host call spends the calling module's instruction budget wall-clock
 * time and stalls the agent turn that invoked it.
 */
interface ScriptPluginHost {

    fun log(pluginId: String, message: String)

    /**
     * Reads a host collection ("notes", "todos", "bookmarks"), returning JSON.
     * Gated on [ScriptPluginPermissions.DATA_READ].
     */
    fun readData(pluginId: String, collection: String, query: String): String

    /**
     * Writes to a host collection, returning a JSON result.
     * Gated on [ScriptPluginPermissions.DATA_WRITE].
     */
    fun writeData(pluginId: String, collection: String, payload: String): String

    /**
     * Performs an HTTPS GET through the host's client.
     * Gated on [ScriptPluginPermissions.NETWORK].
     */
    fun httpGet(pluginId: String, url: String): String
}

/**
 * Adapts one tool declared by a module into the host [Tool] contract, so the
 * agent and the LLM see it exactly like a built-in.
 *
 * The descriptor comes from the manifest rather than from the running script:
 * Hermes builds the model's tool list before any module code executes, and a
 * schema that could change at runtime would let a module misrepresent itself
 * after the user approved it.
 *
 * Failures are returned as [ToolResult.error] rather than thrown, because a
 * failed tool call is a step the agent loop already knows how to recover from,
 * whereas an escaping exception would abort the whole turn over a third-party
 * module's bug.
 */
class ScriptPluginTool(
    override val descriptor: ToolDescriptor,
    private val pluginId: String,
    private val engine: ScriptPluginEngine,
) : Tool {

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        return engine.execute(pluginId, descriptor.name, arguments).fold(
            onSuccess = { output ->
                ToolResult.ok(output, executionMs = System.currentTimeMillis() - start)
            },
            onFailure = { throwable ->
                ToolResult.error(
                    message = "Module '$pluginId' could not run '${descriptor.name}': ${throwable.message}",
                    executionMs = System.currentTimeMillis() - start,
                )
            },
        )
    }
}
