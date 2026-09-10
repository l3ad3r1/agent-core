package com.hermes.agent.domain.tool

/**
 * Read/write access to the catalog of tools available to the orchestrator.
 *
 * Phase 2 ships a fixed set of first-party tools registered at app
 * startup. Phase 3 will allow dynamic registration of third-party plugins
 * through the gRPC sandbox.
 */
interface ToolRegistry {

    /** All registered tools, ordered by category then name. */
    fun all(): List<Tool>

    /** All registered tool descriptors, suitable for sending to an LLM. */
    fun descriptors(): List<ToolDescriptor> = all().map { it.descriptor }

    /** Look up a tool by its descriptor name, or null if not registered. */
    fun byName(name: String): Tool?

    /** Register a tool. Idempotent — re-registering replaces. */
    fun register(tool: Tool)

    /**
     * Register [tool] only while its name is unused.
     *
     * Dynamic tool providers must use this instead of [register]: replacing an
     * already registered tool changes the identity and confirmation policy the
     * model sees. Implementations that cannot provide an atomic operation still
     * fail closed rather than replacing the existing tool.
     */
    fun registerIfAbsent(tool: Tool): Boolean {
        if (byName(tool.descriptor.name) != null) return false
        register(tool)
        return byName(tool.descriptor.name) === tool
    }

    /** Deregister a tool by name. */
    fun unregister(name: String)

    /** Remove [tool] only if it is still the entry registered under [name]. */
    fun unregisterIfSame(name: String, tool: Tool): Boolean {
        if (byName(name) !== tool) return false
        unregister(name)
        return true
    }
}
