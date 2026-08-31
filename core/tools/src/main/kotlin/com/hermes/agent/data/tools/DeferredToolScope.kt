package com.hermes.agent.data.tools

import java.util.concurrent.atomic.AtomicReference
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
 * Single-user app, one orchestrated turn at a time, so a single holder is
 * adequate; the value is swapped atomically and read-only during a step. If
 * concurrent orchestrations are ever introduced this must become request-scoped.
 */
@Singleton
class DeferredToolScope @Inject constructor() {

    private val allowed = AtomicReference<Set<String>>(emptySet())

    /** Publish the deferred, already grant-filtered tool names for this step. */
    fun publish(names: Set<String>) {
        allowed.set(names)
    }

    /** Drop the scope once a step is done, so a later turn cannot inherit it. */
    fun clear() {
        allowed.set(emptySet())
    }

    fun isAllowed(toolName: String): Boolean = allowed.get().contains(toolName)

    fun allowedNames(): Set<String> = allowed.get()
}
