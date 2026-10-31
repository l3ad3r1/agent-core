package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRequirementsTest {

    private val deviceControl = ToolDescriptor(
        name = "device_control",
        description = "Control flashlight and brightness.",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "Device action.", required = true,
                enumValues = listOf("flashlight", "set_brightness")),
            ToolParameter("enabled", ToolParameterType.BOOLEAN, "On or off."),
            ToolParameter("level", ToolParameterType.INTEGER, "0 to 255."),
            ToolParameter("auto", ToolParameterType.BOOLEAN, "Automatic brightness."),
        ),
        requiredByAction = mapOf("flashlight" to listOf("enabled"), "set_brightness" to listOf("level|auto")),
    )

    private val speak = ToolDescriptor(
        name = "speak",
        description = "Speak text aloud.",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "speak or stop.", enumValues = listOf("speak", "stop")),
            ToolParameter("text", ToolParameterType.STRING, "What to say."),
        ),
        requiredByAction = mapOf("speak" to listOf("text"), "*" to listOf("text")),
    )

    private fun score(tool: ToolDescriptor, vararg args: Pair<String, JsonElement>) =
        scoreToolCalls(listOf(ToolCall(id = "c", name = tool.name, arguments = mapOf(*args))), listOf(tool))

    private val threshold = 0.60

    @Test
    fun `a flashlight call without enabled is refused though the schema calls it optional`() {
        val r = score(deviceControl, "action" to JsonPrimitive("flashlight"))
        assertTrue("score ${r.score}", r.score < threshold)
        assertTrue(r.reason, "enabled" in r.reason && "flashlight" in r.reason)
    }

    @Test
    fun `the same call with the argument is accepted, including enabled=false`() {
        assertEquals(1.0, score(deviceControl, "action" to JsonPrimitive("flashlight"), "enabled" to JsonPrimitive(true)).score, 0.001)
        assertEquals(1.0, score(deviceControl, "action" to JsonPrimitive("flashlight"), "enabled" to JsonPrimitive(false)).score, 0.001)
    }

    @Test
    fun `either of two alternatives satisfies an or requirement`() {
        assertTrue(score(deviceControl, "action" to JsonPrimitive("set_brightness")).score < threshold)
        assertEquals(1.0, score(deviceControl, "action" to JsonPrimitive("set_brightness"), "level" to JsonPrimitive(120)).score, 0.001)
        assertEquals(1.0, score(deviceControl, "action" to JsonPrimitive("set_brightness"), "auto" to JsonPrimitive(true)).score, 0.001)
    }

    @Test
    fun `an action with no declared requirement is unaffected`() {
        // stop needs nothing; the "*" entry only applies when action is left out.
        assertEquals(1.0, score(speak, "action" to JsonPrimitive("stop")).score, 0.001)
    }

    @Test
    fun `an omitted action uses the default entry`() {
        assertTrue(score(speak, "text" to JsonPrimitive("hello")).score >= threshold)
        // Left out entirely, the call also has no text, and the "*" entry names it.
        val r = score(speak)
        assertTrue("score ${r.score}", r.score < threshold)
    }

    @Test
    fun `a blank value counts as missing`() {
        assertTrue(score(deviceControl, "action" to JsonPrimitive("set_brightness"), "level" to JsonPrimitive("  ")).score < threshold)
    }

    @Test
    fun `tools that declare nothing score as before`() {
        val plain = ToolDescriptor("calculator", "Evaluate.", listOf(ToolParameter("expression", ToolParameterType.STRING, "e", required = true)))
        assertEquals(1.0, score(plain, "expression" to JsonPrimitive("2+2")).score, 0.001)
    }

    @Test
    fun `the declaration tells the model which action needs which argument`() {
        assertEquals(" Required when action is flashlight.", requirementNote(deviceControl, "enabled"))
        assertEquals(" Required when action is set_brightness (unless auto is given).", requirementNote(deviceControl, "level"))
        assertEquals(" Required when action is set_brightness (unless level is given).", requirementNote(deviceControl, "auto"))
        assertEquals(" Required when action is speak or action is omitted.", requirementNote(speak, "text"))
        assertEquals("", requirementNote(deviceControl, "action"))

        val rendered = renderFunctionDeclarations(listOf(deviceControl))
        assertTrue(rendered, "enabled:{description:<escape>On or off. Required when action is flashlight.<escape>" in rendered)
    }

    @Test
    fun `tools without requirements render exactly as before`() {
        val plain = ToolDescriptor("calculator", "Evaluate.", listOf(ToolParameter("expression", ToolParameterType.STRING, "e", required = true)))
        assertTrue("expression:{description:<escape>e<escape>" in renderFunctionDeclarations(listOf(plain)))
    }
}
