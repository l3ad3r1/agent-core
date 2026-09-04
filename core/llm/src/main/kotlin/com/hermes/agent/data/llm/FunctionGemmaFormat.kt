package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import com.hermes.agent.domain.tool.ToolDescriptor
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * FunctionGemma's own function-calling protocol.
 *
 * Not JSON, and not the `<tool_call>{…}</tool_call>` envelope the Llama-based
 * local path uses. The model has six dedicated tokens in its vocabulary —
 * `<start_function_declaration>`, `<start_function_call>`,
 * `<start_function_response>` and their closers — and a chat template that
 * renders tools into them. Handed anything else it answers, correctly, that the
 * query is unrelated to the functions available to it.
 *
 * Values use `<escape>` as a string delimiter rather than quotes, keys are bare,
 * and pairs are `key:value` rather than `"key": value`:
 *
 *     <start_function_call>call:set_torch{on:true}<end_function_call>
 *     <start_function_call>call:todo{action:<escape>create<escape>}<end_function_call>
 *
 * Both halves live here because they are one protocol: a declaration this file
 * renders is what produces a call this file parses, and they have to move
 * together.
 *
 * The grammar is taken from the chat template embedded in the model's own GGUF
 * (`format_function_declaration` and the `tool_calls` branch), not from prose
 * documentation.
 */

private const val CALL_OPEN = "<start_function_call>"
private const val CALL_CLOSE = "<end_function_call>"
private const val DECL_OPEN = "<start_function_declaration>"
private const val DECL_CLOSE = "<end_function_declaration>"
private const val ESCAPE = "<escape>"

private val CALL_ID = AtomicLong(0)

/**
 * Renders [tools] as the declaration block the model was trained to read.
 *
 * The engine's `chat_add_and_format` passes role and content only — never a
 * `tools` array — so the template's declaration loop never runs and the model
 * sees no functions at all. Rendering the blocks by hand into the system message
 * puts them where that loop would have: the template emits the system content
 * into the `developer` turn immediately before where declarations belong.
 */
internal fun renderFunctionDeclarations(tools: List<ToolDescriptor>): String =
    tools.joinToString("") { tool ->
        buildString {
            append(DECL_OPEN)
            append("declaration:").append(tool.name)
            append("{description:").append(escaped(tool.description.substringBefore('\n')))
            if (tool.parameters.isNotEmpty()) {
                append(",parameters:{properties:{")
                tool.parameters.forEachIndexed { index, parameter ->
                    if (index > 0) append(',')
                    append(parameter.name).append(":{description:")
                    append(escaped(parameter.description.substringBefore('\n')))
                    parameter.enumValues?.let { values ->
                        append(",enum:[")
                        append(values.joinToString(",") { escaped(it) })
                        append(']')
                    }
                    append('}')
                }
                append('}')
                val required = tool.parameters.filter { it.required }
                if (required.isNotEmpty()) {
                    append(",required:[")
                    append(required.joinToString(",") { escaped(it.name) })
                    append(']')
                }
                append(",type:").append(escaped("OBJECT")).append('}')
            }
            append('}')
            append(DECL_CLOSE)
        }
    }

private fun escaped(value: String) = ESCAPE + value.replace(ESCAPE, " ") + ESCAPE

/**
 * Extracts every call in [text], returning the leftover prose and the calls.
 *
 * Mirrors [extractTextToolCalls]'s shape so the provider can swap between the
 * two protocols without changing its own flow.
 *
 * Deliberately lenient in two places, because a 270M model truncates:
 *  - a call missing its closing token is still read, to end of text;
 *  - a name with no `{…}` body still yields a call, so a bare
 *    `<start_function_call>call: turn off` surfaces as the invented name it is
 *    rather than as "no tool call emitted". The confidence gate rejects it on
 *    the name, which is a far more useful abstain reason.
 */
internal fun parseFunctionGemmaCalls(text: String): Pair<String, List<ToolCall>> {
    if (!text.contains(CALL_OPEN)) return text to emptyList()

    val calls = mutableListOf<ToolCall>()
    val leftover = StringBuilder()
    var cursor = 0

    while (true) {
        val open = text.indexOf(CALL_OPEN, cursor)
        if (open < 0) break
        leftover.append(text, cursor, open)

        val bodyStart = open + CALL_OPEN.length
        val close = text.indexOf(CALL_CLOSE, bodyStart)
        val region = if (close >= 0) text.substring(bodyStart, close) else text.substring(bodyStart)
        val (call, consumed) = parseOneCall(region)
        call?.let { calls += it }
        // Without a closing token only the call itself is consumed, so prose the
        // model wrote after it stays prose. Reading to end of text instead would
        // fold a refusal sentence into the tool name.
        cursor = if (close >= 0) close + CALL_CLOSE.length else bodyStart + consumed
    }

    leftover.append(text, cursor.coerceAtMost(text.length), text.length)
    return leftover.toString().trim() to calls
}

/** Returns the call in [region] and how many of its characters it used. */
private fun parseOneCall(region: String): Pair<ToolCall?, Int> {
    var index = 0
    while (index < region.length && region[index].isWhitespace()) index++
    if (region.startsWith("call:", index)) index += "call:".length
    while (index < region.length && (region[index] == ' ' || region[index] == '\t')) index++

    // A name runs to its argument brace or to the end of the line — never past a
    // newline, which is where a model that gave up starts explaining itself.
    val nameStart = index
    while (index < region.length && region[index] != '{' && region[index] != '\n') index++
    val name = region.substring(nameStart, index).trim()
    if (name.isEmpty()) return null to index

    if (index < region.length && region[index] == '{') {
        val end = matchingBrace(region, index)
        val arguments = parsePairs(region.substring(index + 1, end.coerceAtMost(region.length)))
        return call(name, arguments) to (end + 1).coerceAtMost(region.length)
    }
    return call(name, emptyMap()) to index
}

private fun call(name: String, arguments: Map<String, JsonElement>) = ToolCall(
    id = "gemma_call_${CALL_ID.incrementAndGet()}",
    name = name,
    arguments = arguments,
)

/** Index of the `}` closing the `{` at [open], or the end of the string. */
private fun matchingBrace(text: String, open: Int): Int {
    var depth = 0
    var index = open
    while (index < text.length) {
        when (text[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return index
            }
        }
        index++
    }
    return text.length
}

/** Splits `a:1,b:<escape>two<escape>` into a map, respecting nesting. */
private fun parsePairs(body: String): Map<String, JsonElement> {
    val out = LinkedHashMap<String, JsonElement>()
    splitTopLevel(body).forEach { pair ->
        val colon = topLevelColon(pair)
        if (colon <= 0) return@forEach
        val key = pair.substring(0, colon).trim()
        if (key.isNotEmpty()) out[key] = parseValue(pair.substring(colon + 1).trim())
    }
    return out
}

private fun parseValue(raw: String): JsonElement {
    val value = raw.trim()
    return when {
        value.startsWith(ESCAPE) ->
            JsonPrimitive(value.removePrefix(ESCAPE).removeSuffix(ESCAPE))
        value.startsWith("{") ->
            JsonObject(parsePairs(value.substring(1, matchingBrace(value, 0).coerceAtMost(value.length))))
        value.startsWith("[") ->
            JsonArray(splitTopLevel(value.removePrefix("[").removeSuffix("]")).map { parseValue(it) })
        value == "true" -> JsonPrimitive(true)
        value == "false" -> JsonPrimitive(false)
        value.toLongOrNull() != null -> JsonPrimitive(value.toLong())
        value.toDoubleOrNull() != null -> JsonPrimitive(value.toDouble())
        // An unwrapped bare word. The model drops the delimiters often enough
        // that rejecting these would throw away usable calls.
        else -> JsonPrimitive(value)
    }
}

/**
 * Splits on commas that are not inside `{}`, `[]`, or an `<escape>` span.
 *
 * Escape spans matter: a description or a title routinely contains a comma, and
 * splitting inside one silently truncates the argument.
 */
private fun splitTopLevel(body: String): List<String> {
    if (body.isBlank()) return emptyList()
    val parts = mutableListOf<String>()
    var depth = 0
    var inEscape = false
    var start = 0
    var index = 0
    while (index < body.length) {
        if (body.startsWith(ESCAPE, index)) {
            inEscape = !inEscape
            index += ESCAPE.length
            continue
        }
        if (!inEscape) {
            when (body[index]) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                ',' -> if (depth == 0) {
                    parts += body.substring(start, index)
                    start = index + 1
                }
            }
        }
        index++
    }
    parts += body.substring(start)
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}

/** Index of the `key:value` separator, skipping colons inside escape spans. */
private fun topLevelColon(pair: String): Int {
    var inEscape = false
    var index = 0
    while (index < pair.length) {
        if (pair.startsWith(ESCAPE, index)) {
            inEscape = !inEscape
            index += ESCAPE.length
            continue
        }
        if (!inEscape && pair[index] == ':') return index
        index++
    }
    return -1
}
