package com.hermes.agent.data.tools

import com.hermes.agent.data.tool.ToolRegistryImpl
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSearchEngineTest {

    private fun createTestDescriptor(name: String, isMcp: Boolean = false): ToolDescriptor {
        val category = if (isMcp) "mcp" else "general"
        val caps = if (isMcp) setOf("mcp", "deferrable") else setOf("general")
        return ToolDescriptor(
            name = name,
            description = "Description for $name with some explanatory text",
            parameters = listOf(
                ToolParameter(
                    name = "param1",
                    type = ToolParameterType.STRING,
                    description = "A string parameter description",
                    required = true,
                )
            ),
            category = category,
            capabilities = caps,
        )
    }

    @Test
    fun `core tools are never deferred`() {
        val core1 = createTestDescriptor("read_file", isMcp = false)
        val core2 = createTestDescriptor("write_file", isMcp = false)
        val core3 = createTestDescriptor("web_search", isMcp = false)

        assertTrue(ToolSearchEngine.isCoreTool(core1))
        assertTrue(ToolSearchEngine.isCoreTool(core2))
        assertTrue(ToolSearchEngine.isCoreTool(core3))
    }

    @Test
    fun `mcp and deferrable tools are identified as non-core`() {
        val mcp1 = createTestDescriptor("mcp__github__create_issue", isMcp = true)
        val mcp2 = createTestDescriptor("mcp__notion__search", isMcp = true)

        assertFalse(ToolSearchEngine.isCoreTool(mcp1))
        assertFalse(ToolSearchEngine.isCoreTool(mcp2))
    }

    @Test
    fun `tier 0 passthrough when no deferrable tools exist`() {
        val coreTools = listOf(
            createTestDescriptor("read_file"),
            createTestDescriptor("write_file"),
            createTestDescriptor("calculator")
        )

        val result = ToolSearchEngine.evaluate(coreTools, contextWindowTokens = 4096)
        assertFalse(result.isProgressiveDisclosureActive)
        assertEquals(3, result.modelVisibleDescriptors.size)
        assertEquals(0, result.deferredDescriptors.size)
    }

    @Test
    fun `progressive disclosure activates when deferrable tools exceed threshold budget`() {
        val coreTools = listOf(
            createTestDescriptor("read_file"),
            createTestDescriptor("write_file")
        )
        // Add 50 MCP tools
        val mcpTools = (1..50).map { i -> createTestDescriptor("mcp__server__tool_$i", isMcp = true) }
        val allTools = coreTools + mcpTools

        // Context window 2048 with 5% threshold = ~100 tokens budget (400 chars)
        // 50 MCP tools will be ~5000 chars
        val result = ToolSearchEngine.evaluate(allTools, contextWindowTokens = 2048, thresholdPct = 5.0)

        assertTrue(result.isProgressiveDisclosureActive)
        assertEquals(50, result.deferredDescriptors.size)

        // Model visible should have core tools (2) + bridge tools (3: tool_search, tool_describe, tool_call) = 5
        assertEquals(5, result.modelVisibleDescriptors.size)
        val visibleNames = result.modelVisibleDescriptors.map { it.name }.toSet()
        assertTrue(visibleNames.contains("read_file"))
        assertTrue(visibleNames.contains("write_file"))
        assertTrue(visibleNames.contains("tool_search"))
        assertTrue(visibleNames.contains("tool_describe"))
        assertTrue(visibleNames.contains("tool_call"))
    }

    @Test
    fun `bridge tools work end-to-end via ToolRegistry`() = runTest {
        val registry = ToolRegistryImpl()

        val dummyMcpTool = object : Tool {
            override val descriptor = createTestDescriptor("mcp__github__create_issue", isMcp = true)
            override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
                val title = arguments["title"]?.let { (it as? JsonPrimitive)?.content } ?: ""
                return ToolResult.ok("Created issue with title: $title")
            }
        }
        registry.register(dummyMcpTool)

        // The bridge only reaches what the orchestrator published for this step —
        // that set is already grant-filtered, so publishing it here is what a real
        // turn does. See DeferredToolScope.
        val scope = DeferredToolScope()
        scope.publish(setOf("mcp__github__create_issue"))

        val searchTool = ToolSearchTool(registry, scope)
        val describeTool = ToolDescribeTool(registry, scope)
        val callTool = ToolCallTool(registry, scope)

        // 0. Nothing is reachable before a scope is published.
        val unscoped = ToolCallTool(registry, DeferredToolScope()).execute(
            mapOf("tool_name" to JsonPrimitive("mcp__github__create_issue"))
        )
        assertTrue("bridge must fail closed with no scope", !unscoped.success)

        // 1. Test tool_search
        val searchRes = searchTool.execute(mapOf("query" to JsonPrimitive("github")))
        assertTrue(searchRes.success)
        assertTrue(searchRes.output.contains("mcp__github__create_issue"))

        // 2. Test tool_describe
        val describeRes = describeTool.execute(mapOf("tool_name" to JsonPrimitive("mcp__github__create_issue")))
        assertTrue(describeRes.success)
        assertTrue(describeRes.output.contains("param1"))

        // 3. Test tool_call
        val callRes = callTool.execute(
            mapOf(
                "tool_name" to JsonPrimitive("mcp__github__create_issue"),
                "arguments" to JsonObject(mapOf("title" to JsonPrimitive("Bug in auth")))
            )
        )
        assertTrue(callRes.success)
        assertEquals("Created issue with title: Bug in auth", callRes.output)
    }
}
