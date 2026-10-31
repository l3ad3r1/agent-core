package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.ToolCall
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameterType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fills an argument the small model left out when the user's own words settle it.
 *
 * FunctionGemma reliably picks `device_control(action=flashlight)` for "turn on the flashlight" and
 * just as reliably drops `enabled`, even with "Required when action is flashlight" in the
 * declaration. The value is in the request, so it is read from there rather than guessed:
 *
 * - a true/false argument from on/off words ("turn on", "enable" -> true; "off", "disable" -> false),
 *   only when the request says one and not the other;
 * - a closed-list argument when exactly one of its allowed values appears in the request ("silent").
 *
 * Only arguments that [ToolDescriptor.requiredByAction] says the chosen action needs are filled, and
 * only when absent. Anything ambiguous stays missing, so the confidence check still hands the turn on.
 */
internal fun completeFromRequest(calls: List<ToolCall>, tools: List<ToolDescriptor>, turn: String): List<ToolCall> {
    val words = turn.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
    val byName = tools.associateBy { it.name }
    return calls.map { call ->
        val tool = byName[call.name] ?: return@map call
        if (tool.requiredByAction.isEmpty()) return@map call
        val action = (call.arguments["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        val needs = tool.requiredByAction[action ?: "*"].orEmpty()
        val filled = call.arguments.toMutableMap()
        for (need in needs) {
            val alternatives = need.split('|')
            if (alternatives.any { !filled[it].absent() }) continue
            // With alternatives ("level|auto") there is no single argument to fill; leave it.
            val name = alternatives.singleOrNull() ?: continue
            val parameter = tool.parameters.firstOrNull { it.name == name } ?: continue
            inferFromWords(parameter.type, parameter.enumValues, words)?.let { filled[name] = it }
        }
        if (filled == call.arguments) call else call.copy(arguments = filled)
    }
}

private val ON_WORDS = setOf("on", "enable", "enabled", "activate", "start")
private val OFF_WORDS = setOf("off", "disable", "disabled", "deactivate", "stop")

private fun inferFromWords(type: ToolParameterType, enumValues: List<String>?, words: Set<String>): JsonElement? =
    when {
        type == ToolParameterType.BOOLEAN -> {
            val on = words.any { it in ON_WORDS }
            val off = words.any { it in OFF_WORDS }
            if (on == off) null else JsonPrimitive(on)
        }
        enumValues != null -> enumValues
            .filter { value -> value.lowercase().split('_').all { it in words } }
            .singleOrNull()
            ?.let(::JsonPrimitive)
        else -> null
    }

private fun JsonElement?.absent(): Boolean = when {
    this == null || this is JsonNull -> true
    this is JsonPrimitive && isString -> content.isBlank()
    else -> false
}
