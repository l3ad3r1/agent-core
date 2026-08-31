package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge (`tool_search` / `tool_describe` / `tool_call`) used to read straight
 * from the registry, so any role could reach any deferred tool. These cover the
 * scope that now gates it.
 */
class DeferredToolScopeTest {

    private class FakeTool(
        name: String,
        capabilities: Set<String>,
    ) : Tool {
        override val descriptor = ToolDescriptor(
            name = name,
            description = "fake $name for testing",
            parameters = listOf(
                ToolParameter(
                    name = "path",
                    type = ToolParameterType.STRING,
                    description = "a path",
                    required = false,
                )
            ),
            category = "files",
            capabilities = capabilities,
        )

        override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult =
            ToolResult.ok("ran ${descriptor.name}")
    }

    private class FakeRegistry(private val tools: List<Tool>) : ToolRegistry {
        override fun all(): List<Tool> = tools
        override fun byName(name: String): Tool? = tools.firstOrNull { it.descriptor.name == name }
        override fun register(tool: Tool) = Unit
        override fun unregister(name: String) = Unit
    }

    private fun registry() = FakeRegistry(
        listOf(
            FakeTool("read_file", setOf("files", "deferrable")),
            FakeTool("write_file", setOf("files", "deferrable")),
            FakeTool("kanban", setOf("kanban", "deferrable")),
        )
    )

    @Test
    fun `tool_call refuses a deferred tool outside the scope`() = runTest {
        val scope = DeferredToolScope()
        // The running role was granted kanban but NOT the file tools.
        scope.publish(setOf("kanban"))

        val result = ToolCallTool(registry(), scope).execute(
            mapOf("tool_name" to JsonPrimitive("read_file"))
        )

        assertFalse("expected refusal, got: $result", result.success)
        assertTrue(
            "refusal should name the tool: ${result.errorMessage}",
            result.errorMessage.orEmpty().contains("read_file"),
        )
    }

    @Test
    fun `tool_call runs a deferred tool inside the scope`() = runTest {
        val scope = DeferredToolScope()
        scope.publish(setOf("kanban"))

        val result = ToolCallTool(registry(), scope).execute(
            mapOf("tool_name" to JsonPrimitive("kanban"))
        )

        assertTrue("expected success, got: ${result.errorMessage}", result.success)
        assertTrue(result.output.contains("ran kanban"))
    }

    @Test
    fun `an empty scope lets the bridge reach nothing`() = runTest {
        // Disclosure inactive: the bridge is not advertised, so any call naming it
        // is a hallucination and must fail closed rather than fall through.
        val scope = DeferredToolScope()

        val result = ToolCallTool(registry(), scope).execute(
            mapOf("tool_name" to JsonPrimitive("kanban"))
        )

        assertFalse(result.success)
    }

    @Test
    fun `tool_search only lists tools inside the scope`() = runTest {
        val scope = DeferredToolScope()
        scope.publish(setOf("kanban"))

        val result = ToolSearchTool(registry(), scope).execute(
            mapOf("query" to JsonPrimitive("file"))
        )

        // "read_file"/"write_file" match the query far better than "kanban" does,
        // but they were never granted to this role.
        assertFalse("read_file leaked: ${result.output}", result.output.contains("read_file"))
        assertFalse("write_file leaked: ${result.output}", result.output.contains("write_file"))
    }

    @Test
    fun `tool_describe does not leak the schema of an ungranted tool`() = runTest {
        val scope = DeferredToolScope()
        scope.publish(setOf("kanban"))

        val result = ToolDescribeTool(registry(), scope).execute(
            mapOf("tool_name" to JsonPrimitive("read_file"))
        )

        assertFalse(
            "schema leaked for an ungranted tool: ${result.output}",
            result.output.contains("\"parameters\""),
        )
    }

    @Test
    fun `clear drops a scope so it cannot outlive its turn`() = runTest {
        val scope = DeferredToolScope()
        scope.publish(setOf("kanban"))
        scope.clear()

        val result = ToolCallTool(registry(), scope).execute(
            mapOf("tool_name" to JsonPrimitive("kanban"))
        )

        assertFalse(result.success)
    }
}
