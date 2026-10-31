package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ArgumentCompletionTest {

    private val deviceControl = ToolDescriptor(
        name = "device_control",
        description = "Control flashlight, ringer, do not disturb and brightness.",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "Device action.", required = true,
                enumValues = listOf("flashlight", "set_ringer_mode", "set_dnd", "set_brightness")),
            ToolParameter("enabled", ToolParameterType.BOOLEAN, "On or off."),
            ToolParameter("mode", ToolParameterType.STRING, "Ringer mode.",
                enumValues = listOf("normal", "vibrate", "silent")),
            ToolParameter("level", ToolParameterType.INTEGER, "0 to 255."),
            ToolParameter("auto", ToolParameterType.BOOLEAN, "Automatic brightness."),
        ),
        requiredByAction = mapOf(
            "flashlight" to listOf("enabled"),
            "set_dnd" to listOf("enabled"),
            "set_ringer_mode" to listOf("mode"),
            "set_brightness" to listOf("level|auto"),
        ),
    )

    private val noRequirements = ToolDescriptor(
        name = "device_control",
        description = "Same tool, nothing declared.",
        parameters = deviceControl.parameters,
    )

    private fun call(action: String, vararg extra: Pair<String, JsonElement>): ToolCall {
        val args = linkedMapOf<String, JsonElement>("action" to JsonPrimitive(action))
        extra.forEach { (k, v) -> args[k] = v }
        return ToolCall(id = "c", name = "device_control", arguments = args)
    }

    private fun complete(call: ToolCall, turn: String, tool: ToolDescriptor = deviceControl): ToolCall =
        completeFromRequest(listOf(call), listOf(tool), turn).single()

    @Test
    fun `turn on the flashlight fills enabled true`() {
        val result = complete(call("flashlight"), "turn on the flashlight")
        assertEquals(JsonPrimitive(true), result.arguments["enabled"])
    }

    @Test
    fun `turn off the flashlight fills enabled false`() {
        val result = complete(call("flashlight"), "turn off the flashlight")
        assertEquals(JsonPrimitive(false), result.arguments["enabled"])
    }

    @Test
    fun `on and off both present leaves enabled absent`() {
        val result = complete(call("flashlight"), "turn the flashlight on and then off")
        assertEquals(null, result.arguments["enabled"])
    }

    @Test
    fun `not is not an off word so do not disturb turns on`() {
        val result = complete(call("set_dnd"), "turn on do not disturb")
        assertEquals(JsonPrimitive(true), result.arguments["enabled"])
    }

    @Test
    fun `a single allowed ringer value is filled`() {
        val result = complete(call("set_ringer_mode"), "put the phone on silent")
        assertEquals(JsonPrimitive("silent"), result.arguments["mode"])
    }

    @Test
    fun `two allowed ringer values leave mode absent`() {
        val result = complete(call("set_ringer_mode"), "switch it to vibrate or silent")
        assertEquals(null, result.arguments["mode"])
    }

    @Test
    fun `alternatives like level or auto are never filled`() {
        val result = complete(call("set_brightness"), "make it brighter")
        assertEquals(null, result.arguments["level"])
        assertEquals(null, result.arguments["auto"])
    }

    @Test
    fun `an existing enabled value is never overwritten`() {
        val original = call("flashlight", "enabled" to JsonPrimitive(false))
        val result = complete(original, "turn on the flashlight")
        assertEquals(original, result)
    }

    @Test
    fun `a tool with empty requiredByAction is returned unchanged`() {
        val original = call("flashlight")
        val result = completeFromRequest(listOf(original), listOf(noRequirements), "turn on the flashlight").single()
        assertSame(original, result)
    }
}
