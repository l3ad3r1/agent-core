package com.hermes.agent.data.llm

import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallerBudgetTest {

    private fun tool(i: Int) = ToolDescriptor(
        name = "tool_$i",
        description = "Does thing number $i. ".repeat(20),
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "What to do, in a few words.", required = true),
            ToolParameter("target", ToolParameterType.STRING, "What to do it to."),
        ),
    )

    @Test
    fun `declarations are cut to the budget, keeping the leading tools in order`() {
        val tools = (1..40).map(::tool)

        val kept = toolsWithinBudget(tools, MAX_TOOL_CALLER_DECLARATION_CHARS)

        assertTrue("some tools must be dropped for this catalogue", kept.size < tools.size)
        assertEquals(tools.take(kept.size), kept)
        assertTrue(renderFunctionDeclarations(kept).length <= MAX_TOOL_CALLER_DECLARATION_CHARS)
    }

    @Test
    fun `a small catalogue is kept whole`() {
        val tools = (1..3).map(::tool)

        assertEquals(tools, toolsWithinBudget(tools, MAX_TOOL_CALLER_DECLARATION_CHARS))
    }
}
