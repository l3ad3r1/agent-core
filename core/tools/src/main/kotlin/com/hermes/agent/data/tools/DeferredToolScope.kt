package com.hermes.agent.data.tools

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The set of tools the current turn is allowed to reach *through the bridge*
 * (`tool_search` / `tool_describe` / `tool_call`).
 *
 * Why this exists: the bridge used to read straight from the [com.hermes.agent.domain.tool.ToolRegistry] —
 * `ToolSearchTool` listed `registry.all()` and `ToolCallTool` executed
 * `registry.byName(...)`, then invoked `targetTool.execute()` directly rather
 * than going back through `ToolCallExecutor`. Role grants are applied when the
 * advertised tool list is built (`agent.availableTools(registry)`), so nothing
 * downstream re-checked them: any deferred tool was reachable from any role.
 *
 * That was survivable only while MCP tools were the sole deferrable kind. As
 * soon as built-ins are tagged `deferrable` it becomes a privilege escalation —
 * RESEARCH is deliberately denied the `files` capability, but could have found
 * `read_file` with `tool_search` and run it with `tool_call`.
 *
 * The orchestrator publishes the deferred set for each step here. That set comes
 * from `ToolSearchEngine.evaluate(agent.availableTools(registry))`, so it is
 * already grant-filtered for the role that is running — the bridge simply has to
 * be told to respect it instead of consulting the global registry.
 *
 * Fails closed: an empty scope means the bridge can reach nothing, which is the
 * correct behaviour when progressive disclosure is inactive (the bridge tools are
 * not advertised then, so any call naming them is a hallucination).
 *
 * Scopes travel with the coroutine executing the turn. This avoids one turn
 * replacing another turn's grants while it is waiting for approval or a tool.
 * [publish] remains as a compatibility bridge for app integrations that have
 * not moved to [withScope] yet; those callers retain the legacy single-turn
 * behaviour and should be migrated.
 */
@Singleton
class DeferredToolScope @Inject constructor() {

    private val legacyAllowed = AtomicReference<Set<String>>(emptySet())
    private val requestAllowed = ThreadLocal<Set<String>?>()

    /** Publish the deferred, already grant-filtered tool names for this step. */
    fun publish(names: Set<String>) {
        legacyAllowed.set(names)
    }

    /** Drop the scope once a step is done, so a later turn cannot inherit it. */
    fun clear() {
        legacyAllowed.set(emptySet())
    }

    /** Execute [block] with grants isolated to this coroutine and its children. */
    suspend fun <T> withScope(names: Set<String>, block: suspend () -> T): T =
        withContext(requestAllowed.asContextElement(names.toSet())) { block() }

    fun isAllowed(toolName: String): Boolean =
        (requestAllowed.get() ?: legacyAllowed.get()).contains(toolName)

    fun allowedNames(): Set<String> = requestAllowed.get() ?: legacyAllowed.get()
}
