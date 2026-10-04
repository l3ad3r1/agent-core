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
    fun `the tool the request names is ranked into the budget`() {
        val catalogue = (1..30).map(::tool) + ToolDescriptor(
            name = "device_control",
            description = "Toggle the flashlight, Wi-Fi or Bluetooth.",
            parameters = listOf(ToolParameter("setting", ToolParameterType.STRING, "flashlight, wifi or bluetooth", required = true)),
        )

        val ranked = rankToolsForTurn(catalogue, "[quick] Turn off the flashlight.")

        assertEquals("device_control", ranked.first().name)
        assertEquals("device_control", toolsWithinBudget(ranked, MAX_TOOL_CALLER_DECLARATION_CHARS).first().name)
        // Unmatched tools keep their catalogue order behind it.
        assertEquals(catalogue.take(30), ranked.drop(1))
    }

    @Test
    fun `the developer turn is the trained preamble and the declarations, nothing else`() {
        val prompt = buildToolCallerPrompt(
            listOf(
                com.hermes.agent.domain.llm.LlmMessage("user", "Turn on the flashlight"),
                com.hermes.agent.domain.llm.LlmMessage("assistant", "Done."),
                com.hermes.agent.domain.llm.LlmMessage("user", "Now turn it off"),
            ),
            listOf(tool(1)),
        )

        assertEquals(TOOL_CALLER_PREAMBLE + renderFunctionDeclarations(listOf(tool(1))), prompt.system)
        assertTrue(prompt.conversation, prompt.conversation.contains("Turn on the flashlight"))
        assertTrue(prompt.conversation, prompt.conversation.endsWith("user: Now turn it off"))
    }

    @Test
    fun `a clear winner is shown alone, close alternatives with it`() {
        val torch = ToolDescriptor("device_control", "Control the flashlight and volume.", emptyList())
        val media = ToolDescriptor("media_control", "Play or pause media and change volume.", emptyList())

        assertEquals(listOf(torch), toolsToShow(scoreToolsForTurn(listOf(media, torch), "turn on the flashlight"), 3))
        assertEquals(2, toolsToShow(scoreToolsForTurn(listOf(media, torch), "volume up"), 3).size)
    }

    @Test
    fun `stray punctuation before call is tolerated`() {
        val (_, calls) = parseFunctionGemmaCalls("<start_function_call>-call:device_settings{value:5}<end_function_call>")

        assertEquals("device_settings", calls.single().name)
    }

    @Test
    fun `a small catalogue is kept whole`() {
        val tools = (1..3).map(::tool)

        assertEquals(tools, toolsWithinBudget(tools, MAX_TOOL_CALLER_DECLARATION_CHARS))
    }

    @Test
    fun `a declaration too large for the budget is not truncated into an invalid schema`() {
        val first = tool(1)
        val budget = renderFunctionDeclarations(listOf(first)).length - 1

        assertTrue(toolsWithinBudget(listOf(first, tool(2)), budget).isEmpty())
    }
}
