package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameterType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * How much to trust a tool call a small on-device model produced.
 *
 * This is *structural* confidence, not probabilistic. The llama.cpp binding in
 * `com.arm.aichat` surfaces tokens and nothing else — no logprobs — so there is
 * no calibrated probability to read. What is available is the shape of what the
 * model emitted, checked against the tool it claims to be calling, and that
 * catches the failure a 270M model actually has: a call that looks right and
 * names a real tool, carrying an invented argument, a missing required one, or
 * a value outside the declared enum.
 *
 * It cannot tell you the model picked the *wrong* valid tool with valid
 * arguments. Replacing this with mean token logprob over the emitted call is
 * the next step; the threshold in [ToolCallerLlmProvider] moves with it.
 *
 * @property score 0.0 (do not use) to 1.0 (clean, well-formed call).
 * @property reason the weakest signal found, for the abstain log.
 */
data class ToolCallConfidence(
    val score: Double,
    val reason: String,
) {
    companion object {
        val NO_CALL = ToolCallConfidence(0.0, "no tool call emitted")
    }
}

// Penalties subtract from 1.0 and are deliberately blunt: this gate only has to
// separate "obviously fine" from "obviously guessed", and every value here is an
// estimate until step 2 replaces the scorer with logprobs. Naming them keeps the
// tuning in one readable place.
private const val PENALTY_MISSING_REQUIRED = 0.45
private const val PENALTY_ENUM_VIOLATION = 0.35
private const val PENALTY_PLACEHOLDER_VALUE = 0.30
private const val PENALTY_UNKNOWN_ARGUMENT = 0.20
private const val PENALTY_LOOSE_RECOVERY = 0.20
private const val PENALTY_NO_ARGUMENTS = 0.20
private const val PENALTY_TYPE_MISMATCH = 0.15
private const val PENALTY_TRAILING_PROSE = 0.10

/** Prose beyond this many characters alongside a call reads as hedging. */
private const val PROSE_TOLERANCE_CHARS = 24

/**
 * Values a small model emits when it knows an argument is needed but not what
 * it should be. These are schema echoes, not answers.
 */
private val PLACEHOLDER_VALUES = setOf(
    "string", "value", "text", "todo", "tbd", "none", "null", "n/a",
    "example", "your_value", "placeholder", "...",
)

/**
 * Scores every call in [calls] and returns the weakest.
 *
 * The weakest rather than the mean: a turn that fires two tools is only as
 * trustworthy as its worse call, and running one good call alongside one
 * guessed call is the outcome this gate exists to prevent.
 */
internal fun scoreToolCalls(
    calls: List<ToolCall>,
    tools: List<ToolDescriptor>,
    leftoverText: String = "",
): ToolCallConfidence {
    if (calls.isEmpty()) return ToolCallConfidence.NO_CALL
    val byName = tools.associateBy { it.name }
    return calls
        .map { scoreOneCall(it, byName[it.name], leftoverText) }
        .minByOrNull { it.score }
        ?: ToolCallConfidence.NO_CALL
}

private fun scoreOneCall(
    call: ToolCall,
    descriptor: ToolDescriptor?,
    leftoverText: String,
): ToolCallConfidence {
    // A name matching no advertised tool is not a low-confidence call, it is a
    // hallucinated one. There is nothing to execute, so there is no score.
    if (descriptor == null) {
        return ToolCallConfidence(0.0, "tool '${call.name}' is not in the advertised set")
    }

    var score = 1.0
    var weakest = "well-formed"

    fun penalise(amount: Double, why: String) {
        // Track the single largest deduction, so the abstain log names the
        // reason that actually sank the call rather than the last one checked.
        if (amount >= 1.0 - score) weakest = why
        score -= amount
    }

    // The parser tags calls it had to rescue from prose. Reaching that path at
    // all means the model could not produce the envelope it was shown, which
    // correlates with it being unsure what it was doing.
    if (call.id.startsWith("recovered_call_")) {
        penalise(
            PENALTY_LOOSE_RECOVERY,
            "call was recovered from loose text, not a tool_call envelope",
        )
    }

    val declared = descriptor.parameters.associateBy { it.name }

    descriptor.parameters
        .filter { it.required && call.arguments[it.name].isAbsent() }
        .forEach { penalise(PENALTY_MISSING_REQUIRED, "required argument '${it.name}' is missing") }

    // Action-style tools (todo, notes, calendar) deliberately declare every
    // per-action argument optional — see ToolParameter.required — so "nothing
    // required is missing" is vacuous for them and carries no evidence. Empty
    // arguments on a tool that declares some is the signal that does work
    // there: it is what a model emits when it has picked a tool and has nothing
    // to say about how to call it.
    if (descriptor.parameters.isNotEmpty() && call.arguments.values.all { it.isAbsent() }) {
        penalise(PENALTY_NO_ARGUMENTS, "tool declares arguments but the call supplied none")
    }

    call.arguments.forEach { (name, value) ->
        val parameter = declared[name]
        if (parameter == null) {
            penalise(
                PENALTY_UNKNOWN_ARGUMENT,
                "argument '$name' is not declared by ${descriptor.name}",
            )
            return@forEach
        }
        if (value.isAbsent()) return@forEach

        val literal = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

        if (literal != null && literal.trim().lowercase() in PLACEHOLDER_VALUES) {
            penalise(
                PENALTY_PLACEHOLDER_VALUE,
                "argument '$name' holds the placeholder '$literal'",
            )
        }

        // Enums are the strongest structural signal on action-style tools,
        // where `action` is a closed set and choosing outside it means the
        // model guessed rather than picked.
        val allowed = parameter.enumValues
        if (allowed != null && literal != null && literal !in allowed) {
            penalise(
                PENALTY_ENUM_VIOLATION,
                "argument '$name' is '$literal', outside ${allowed.joinToString("/")}",
            )
        }

        if (!value.matches(parameter.type)) {
            penalise(
                PENALTY_TYPE_MISMATCH,
                "argument '$name' is not ${parameter.type.jsonSchemaType}",
            )
        }
    }

    // The prompt tells the model to write nothing but the envelope. Commentary
    // alongside it means it did not follow the one formatting rule it was given.
    val prose = leftoverText.trim()
    if (prose.length > PROSE_TOLERANCE_CHARS) {
        penalise(PENALTY_TRAILING_PROSE, "the call arrived with ${prose.length} chars of prose")
    }

    return ToolCallConfidence(score.coerceIn(0.0, 1.0), weakest)
}

/** Absent, null, or a blank string — all "the model did not supply this". */
private fun JsonElement?.isAbsent(): Boolean = when {
    this == null || this is JsonNull -> true
    this is JsonPrimitive && isString -> content.isBlank()
    else -> false
}

/**
 * Whether a value is plausibly the declared type.
 *
 * Deliberately lenient about numbers arriving as strings: models quote numeric
 * arguments constantly and the tools parse them anyway, so treating `"5"` as a
 * type error would abstain on calls that run correctly.
 */
private fun JsonElement.matches(type: ToolParameterType): Boolean {
    val primitive = this as? JsonPrimitive
    return when (type) {
        ToolParameterType.STRING -> primitive != null
        ToolParameterType.INTEGER ->
            primitive?.longOrNull != null || primitive?.content?.trim()?.toLongOrNull() != null
        ToolParameterType.NUMBER ->
            primitive?.doubleOrNull != null || primitive?.content?.trim()?.toDoubleOrNull() != null
        ToolParameterType.BOOLEAN ->
            primitive?.booleanOrNull != null ||
                primitive?.content?.trim()?.lowercase() in setOf("true", "false")
        ToolParameterType.ARRAY -> this is JsonArray
        ToolParameterType.OBJECT -> this is JsonObject
    }
}
