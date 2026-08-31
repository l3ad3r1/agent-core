package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.ToolDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers what [ToolSearchEngine.evaluate] returns once it is actually called on
 * a real registry snapshot — bridge tools included, as the orchestrator sees it.
 *
 * Before the orchestrator was wired to this, evaluate() ran only in tests: the
 * bridge tools were advertised on every turn with nothing to find, and a large
 * MCP catalogue was sent to the model in full.
 */
class ToolSearchDisclosureTest {

    private fun core(name: String) = ToolDescriptor(
        name = name,
        description = "A core tool that does $name.",
        parameters = emptyList(),
        category = "information",
        capabilities = setOf(name),
    )

    private fun mcp(name: String, descriptionPadding: Int = 40) = ToolDescriptor(
        name = "mcp__server__$name",
        description = "Remote tool $name. " + "x".repeat(descriptionPadding),
        parameters = emptyList(),
        category = "mcp",
        capabilities = setOf("mcp", "deferrable"),
    )

    private val bridges = listOf(
        ToolSearchEngine.searchToolDescriptor,
        ToolSearchEngine.describeToolDescriptor,
        ToolSearchEngine.callToolDescriptor,
    )

    @Test
    fun `with no MCP tools the bridge tools are not offered to the model`() {
        val registrySnapshot = listOf(core("web_search"), core("memory")) + bridges

        val result = ToolSearchEngine.evaluate(registrySnapshot, contextWindowTokens = 32_768)

        assertFalse(result.isProgressiveDisclosureActive)
        val names = result.modelVisibleDescriptors.map { it.name }
        assertEquals(listOf("web_search", "memory"), names)
        assertFalse("nothing to search, so no bridge", names.contains("tool_search"))
        assertFalse(names.contains("tool_call"))
    }

    @Test
    fun `a small MCP catalogue stays inline rather than costing a round trip`() {
        val registrySnapshot = listOf(core("web_search")) + listOf(mcp("a"), mcp("b")) + bridges

        val result = ToolSearchEngine.evaluate(registrySnapshot, contextWindowTokens = 32_768)

        assertFalse(result.isProgressiveDisclosureActive)
        val names = result.modelVisibleDescriptors.map { it.name }
        assertTrue(names.contains("mcp__server__a"))
        assertFalse(names.contains("tool_search"))
    }

    @Test
    fun `a large MCP catalogue hides behind the bridge`() {
        val many = (1..60).map { mcp("tool$it", descriptionPadding = 400) }
        val registrySnapshot = listOf(core("web_search"), core("memory")) + many + bridges

        val result = ToolSearchEngine.evaluate(registrySnapshot, contextWindowTokens = 32_768)

        assertTrue(result.isProgressiveDisclosureActive)
        val names = result.modelVisibleDescriptors.map { it.name }
        assertTrue(names.containsAll(listOf("tool_search", "tool_describe", "tool_call")))
        assertFalse("the catalogue must not be sent in full", names.any { it.startsWith("mcp__") })
        assertEquals(60, result.deferredDescriptors.size)
    }

    @Test
    fun `core tools are never deferred no matter how large the catalogue`() {
        val many = (1..60).map { mcp("tool$it", descriptionPadding = 400) }
        val coreNames = listOf("web_search", "memory", "shell", "write_file")
        val registrySnapshot = coreNames.map { core(it) } + many + bridges

        val result = ToolSearchEngine.evaluate(registrySnapshot, contextWindowTokens = 32_768)

        val names = result.modelVisibleDescriptors.map { it.name }
        for (n in coreNames) {
            assertTrue("core tool '$n' was deferred", names.contains(n))
        }
    }

    @Test
    fun `the bridge tools are never listed twice`() {
        val many = (1..60).map { mcp("tool$it", descriptionPadding = 400) }
        val registrySnapshot = listOf(core("web_search")) + many + bridges

        val result = ToolSearchEngine.evaluate(registrySnapshot, contextWindowTokens = 32_768)

        val names = result.modelVisibleDescriptors.map { it.name }
        assertEquals(1, names.count { it == "tool_search" })
        assertEquals(1, names.count { it == "tool_call" })
    }

    @Test
    fun `a smaller context defers sooner`() {
        val some = (1..12).map { mcp("tool$it", descriptionPadding = 300) }
        val snapshot = listOf(core("web_search")) + some + bridges

        val roomy = ToolSearchEngine.evaluate(snapshot, contextWindowTokens = 200_000)
        val cramped = ToolSearchEngine.evaluate(snapshot, contextWindowTokens = 4_096)

        assertFalse(roomy.isProgressiveDisclosureActive)
        assertTrue(cramped.isProgressiveDisclosureActive)
    }

    @Test
    fun `isBridgeTool recognises exactly the three bridges`() {
        bridges.forEach { assertTrue(it.name, ToolSearchEngine.isBridgeTool(it)) }
        assertFalse(ToolSearchEngine.isBridgeTool(core("web_search")))
        assertFalse(ToolSearchEngine.isBridgeTool(mcp("a")))
    }
}
